package com.paytm.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.reservation.domain.*;
import com.paytm.reservation.dto.ReservationResponse;
import com.paytm.reservation.dto.ReservationResult;
import com.paytm.reservation.dto.ReserveSeatRequest;
import com.paytm.reservation.exception.*;
import com.paytm.reservation.metrics.ReservationMetrics;
import com.paytm.reservation.repository.*;
import com.paytm.reservation.security.HashUtils;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ReservationMetrics reservationMetrics;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    private boolean isPostgres = false;

    // Per-user per-show JVM lock striping for thread-level concurrency within this JVM
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();
    // Per-user per-idempotency-key lock striping to serialize concurrent retries of the same key
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              ReservationSeatRepository reservationSeatRepository,
                              IdempotencyRecordRepository idempotencyRecordRepository,
                              ReservationMetrics reservationMetrics,
                              EntityManager entityManager,
                              ObjectMapper objectMapper,
                              DataSource dataSource,
                              TransactionTemplate transactionTemplate) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.reservationMetrics = reservationMetrics;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        try (Connection conn = dataSource.getConnection()) {
            String dbName = conn.getMetaData().getDatabaseProductName();
            this.isPostgres = (dbName != null && dbName.toLowerCase().contains("postgres"));
            log.info("ReservationService initialized. Detected database: {} (isPostgres={})", dbName, this.isPostgres);
        } catch (Exception e) {
            this.isPostgres = false;
            log.warn("Could not determine database product name: {}", e.getMessage());
        }
    }

    /**
     * Executes atomic, race-free seat reservation.
     * Deadlock avoidance: locks rows in deterministic lexicographical order.
     * Per-user limit: serialized using transaction advisory lock + JVM lock.
     * Idempotency: exact-once execution with payload validation.
     */
    public ReservationResult reserve(String showId, String userId, ReserveSeatRequest request, String headerIdempotencyKey) {
        if (request.getSeats() == null || request.getSeats().isEmpty()) {
            throw new InvalidRequestException("Seats list cannot be empty");
        }

        // 1. Canonicalize seats: trim, remove duplicates, sort lexicographically
        List<String> requestedSeats = request.getSeats().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (requestedSeats.isEmpty()) {
            throw new InvalidRequestException("Seats list cannot be empty");
        }

        // 2. Resolve effective idempotency key (header takes precedence, then body)
        String idempotencyKey = (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank())
                ? headerIdempotencyKey.trim()
                : (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()
                ? request.getIdempotencyKey().trim()
                : null);

        // 3. Compute canonical request hash
        String requestHash = HashUtils.sha256(showId + ":" + requestedSeats);

        // 4. Serialize concurrent attempts for the exact same idempotency key
        Object keyLock = (idempotencyKey != null)
                ? idempotencyLocks.computeIfAbsent(userId + ":" + idempotencyKey, k -> new Object())
                : new Object();

        synchronized (keyLock) {
            // Check for existing idempotency record
            if (idempotencyKey != null) {
                Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
                if (existingRecord.isPresent()) {
                    IdempotencyRecord record = existingRecord.get();
                    if (record.getRequestHash().equals(requestHash)) {
                        log.info("Idempotent replay detected for key={} user={} show={}", idempotencyKey, userId, showId);
                        reservationMetrics.incrementReplayed();
                        try {
                            ReservationResponse cached = objectMapper.readValue(record.getResponsePayload(), ReservationResponse.class);
                            return new ReservationResult(cached, true);
                        } catch (JsonProcessingException e) {
                            log.error("Failed to deserialize cached idempotency response", e);
                            throw new IllegalStateException("Corrupt idempotency record payload", e);
                        }
                    } else {
                        log.warn("Idempotency conflict for key={} user={}: payload mismatch", idempotencyKey, userId);
                        reservationMetrics.incrementDeclined("idempotency_mismatch");
                        throw new IdempotencyMismatchException("Idempotency key '" + idempotencyKey + "' was already used with different request parameters");
                    }
                }
            }

            // 5. Serialize reservation attempts per (show, user) to enforce per-user limits under concurrency
            Object userLock = userLocks.computeIfAbsent(showId + ":" + userId, k -> new Object());
            synchronized (userLock) {
                return transactionTemplate.execute(txStatus -> {
                    acquireAdvisoryLock(showId, userId);

                    Show show = showRepository.findById(showId)
                            .orElseThrow(() -> new ShowNotFoundException("Show not found with id: " + showId));

                    // Verify per-user seat limit
                    long activeSeats = reservationSeatRepository.countActiveSeatsForUser(showId, userId);
                    if (activeSeats + requestedSeats.size() > show.getPerUserLimit()) {
                        log.warn("Declined reservation for user={}: limit exceeded (current={}, requested={}, limit={})",
                                userId, activeSeats, requestedSeats.size(), show.getPerUserLimit());
                        reservationMetrics.incrementDeclined("per_user_limit");
                        throw new PerUserLimitExceededException(String.format(
                                "Per-user booking limit exceeded. You already hold %d seats; limit is %d for this show",
                                activeSeats, show.getPerUserLimit()));
                    }

                    // 6. Pessimistic Row-level Locking on requested seats in strict alphabetical order (deadlock-free)
                    List<Seat> lockedSeats = seatRepository.findSeatsForUpdate(showId, requestedSeats);

                    // Verify all requested seats exist
                    if (lockedSeats.size() != requestedSeats.size()) {
                        log.warn("Declined reservation: requested {} seats, but only {} exist", requestedSeats.size(), lockedSeats.size());
                        reservationMetrics.incrementDeclined("seat_taken");
                        throw new SeatTakenException("One or more requested seats do not exist in this show");
                    }

                    // 7. Atomic availability check (All-or-Nothing policy)
                    for (Seat seat : lockedSeats) {
                        if (seat.getStatus() != SeatStatus.AVAILABLE) {
                            log.info("Declined reservation: seat {} is already {}", seat.getSeatNumber(), seat.getStatus());
                            reservationMetrics.incrementDeclined("seat_taken");
                            throw new SeatTakenException("Seat " + seat.getSeatNumber() + " is already " + seat.getStatus().name().toLowerCase());
                        }
                    }

                    // 8. Confirm seats atomically
                    Instant now = Instant.now();
                    for (Seat seat : lockedSeats) {
                        seat.setStatus(SeatStatus.CONFIRMED);
                        seat.setUpdatedAt(now);
                        seatRepository.save(seat);
                    }

                    // 9. Persist reservation and associated seats
                    long totalAmountPaise = show.getPricePaise() * requestedSeats.size();
                    String reservationId = UUID.randomUUID().toString();
                    Reservation reservation = new Reservation(reservationId, showId, userId, totalAmountPaise, ReservationStatus.CONFIRMED);
                    reservationRepository.save(reservation);

                    List<ReservationSeat> reservationSeats = requestedSeats.stream()
                            .map(seatNum -> new ReservationSeat(reservationId, showId, seatNum))
                            .toList();
                    reservationSeatRepository.saveAll(reservationSeats);

                    ReservationResponse response = new ReservationResponse(
                            reservationId,
                            showId,
                            userId,
                            requestedSeats,
                            totalAmountPaise,
                            "confirmed"
                    );

                    // 10. Persist idempotency record
                    if (idempotencyKey != null) {
                        try {
                            String payloadJson = objectMapper.writeValueAsString(response);
                            IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, userId, showId, requestHash, reservationId, payloadJson);
                            idempotencyRecordRepository.save(record);
                        } catch (JsonProcessingException e) {
                            log.error("Failed to serialize reservation response for idempotency", e);
                        }
                    }

                    // 11. Metrics
                    reservationMetrics.incrementConfirmed(requestedSeats.size());

                    log.info("Successfully confirmed reservation id={} for user={} show={} seats={}",
                            reservationId, userId, showId, requestedSeats);

                    return new ReservationResult(response, false);
                });
            }
        }
    }

    private void acquireAdvisoryLock(String showId, String userId) {
        if (!this.isPostgres) {
            return;
        }
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                    .setParameter(1, showId + ":" + userId)
                    .getSingleResult();
        } catch (Exception e) {
            log.warn("Postgres advisory lock could not be acquired: {}", e.getMessage());
        }
    }
}
