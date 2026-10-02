package com.paytm.reservation.service;

import com.paytm.reservation.domain.Reservation;
import com.paytm.reservation.domain.ReservationSeat;
import com.paytm.reservation.domain.ReservationStatus;
import com.paytm.reservation.domain.Seat;
import com.paytm.reservation.domain.SeatStatus;
import com.paytm.reservation.exception.ForbiddenException;
import com.paytm.reservation.exception.ReservationNotFoundException;
import com.paytm.reservation.repository.ReservationRepository;
import com.paytm.reservation.repository.ReservationSeatRepository;
import com.paytm.reservation.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final SeatRepository seatRepository;

    public CancellationService(ReservationRepository reservationRepository,
                               ReservationSeatRepository reservationSeatRepository,
                               SeatRepository seatRepository) {
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public Reservation cancelReservation(String reservationId, String authenticatedUserId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("Reservation not found with id: " + reservationId));

        if (!reservation.getUserId().equals(authenticatedUserId)) {
            log.warn("User {} attempted to cancel reservation {} owned by user {}",
                    authenticatedUserId, reservationId, reservation.getUserId());
            throw new ForbiddenException("You are not authorized to cancel reservations belonging to other users");
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("Reservation {} already cancelled (idempotent cancel)", reservationId);
            return reservation;
        }

        // Fetch reserved seats
        List<ReservationSeat> resSeats = reservationSeatRepository.findByReservationId(reservationId);
        List<String> seatNumbers = resSeats.stream()
                .map(ReservationSeat::getSeatNumber)
                .sorted()
                .toList();

        // Lock seats in alphabetical order and release them back to AVAILABLE
        List<Seat> seats = seatRepository.findSeatsForUpdate(reservation.getShowId(), seatNumbers);
        Instant now = Instant.now();
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setUpdatedAt(now);
            seatRepository.save(seat);
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setUpdatedAt(now);
        reservationRepository.save(reservation);

        log.info("Cancelled reservation {} for user {} - released seats: {}",
                reservationId, authenticatedUserId, seatNumbers);

        return reservation;
    }
}
