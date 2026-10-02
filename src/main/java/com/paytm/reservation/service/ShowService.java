package com.paytm.reservation.service;

import com.paytm.reservation.domain.Seat;
import com.paytm.reservation.domain.SeatStatus;
import com.paytm.reservation.domain.Show;
import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.ShowCounts;
import com.paytm.reservation.dto.ShowResponse;
import com.paytm.reservation.exception.InvalidRequestException;
import com.paytm.reservation.exception.ShowNotFoundException;
import com.paytm.reservation.metrics.ReservationMetrics;
import com.paytm.reservation.repository.SeatRepository;
import com.paytm.reservation.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationMetrics reservationMetrics;

    public ShowService(ShowRepository showRepository,
                       SeatRepository seatRepository,
                       ReservationMetrics reservationMetrics) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationMetrics = reservationMetrics;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        if (request.getSeats() == null || request.getSeats().isEmpty()) {
            throw new InvalidRequestException("Seats list cannot be empty");
        }

        List<String> cleanSeats = request.getSeats().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();

        if (cleanSeats.isEmpty()) {
            throw new InvalidRequestException("Seats list cannot be empty");
        }

        int perUserLimit = (request.getPerUserLimit() != null && request.getPerUserLimit() > 0)
                ? request.getPerUserLimit()
                : 4;

        String showId = UUID.randomUUID().toString();
        Show show = new Show(showId, request.getName(), request.getPricePaise(), perUserLimit, cleanSeats.size());
        showRepository.save(show);

        List<Seat> seatEntities = cleanSeats.stream()
                .map(seatNum -> new Seat(showId, seatNum, SeatStatus.AVAILABLE))
                .toList();
        seatRepository.saveAll(seatEntities);

        log.info("Created show id={} name='{}' with {} available seats (per-user limit={})",
                showId, show.getName(), cleanSeats.size(), perUserLimit);

        Map<String, String> seatMap = new LinkedHashMap<>();
        for (String s : cleanSeats) {
            seatMap.put(s, "available");
        }

        ShowCounts counts = new ShowCounts(cleanSeats.size(), 0, 0);
        reservationMetrics.updateShowGauges(showId, cleanSeats.size(), 0, 0);

        return new ShowResponse(showId, show.getName(), show.getPricePaise(), show.getTotalSeats(), counts, seatMap);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(String showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("Show not found with id: " + showId));

        List<Seat> seats = seatRepository.findByShowIdOrderBySeatNumberAsc(showId);

        Map<String, String> seatMap = new LinkedHashMap<>();
        long available = 0;
        long held = 0;
        long confirmed = 0;

        for (Seat seat : seats) {
            String statusLower = seat.getStatus().name().toLowerCase();
            seatMap.put(seat.getSeatNumber(), statusLower);
            switch (seat.getStatus()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }

        // Enforce the core reconciliation invariant: available + held + confirmed == total_seats
        long accountedTotal = available + held + confirmed;
        if (accountedTotal != show.getTotalSeats()) {
            log.error("CRITICAL: Reconciliation invariant broken for show {}! Accounted={}, Total={}",
                    showId, accountedTotal, show.getTotalSeats());
        }

        ShowCounts counts = new ShowCounts(available, held, confirmed);
        reservationMetrics.updateShowGauges(showId, available, held, confirmed);

        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(), show.getTotalSeats(), counts, seatMap);
    }
}
