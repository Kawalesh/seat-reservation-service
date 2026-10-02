package com.paytm.reservation.controller;

import com.paytm.reservation.domain.Reservation;
import com.paytm.reservation.domain.ReservationSeat;
import com.paytm.reservation.dto.ReservationResponse;
import com.paytm.reservation.exception.ReservationNotFoundException;
import com.paytm.reservation.repository.ReservationRepository;
import com.paytm.reservation.repository.ReservationSeatRepository;
import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.CancellationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final CancellationService cancellationService;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;

    public ReservationController(CancellationService cancellationService,
                                 ReservationRepository reservationRepository,
                                 ReservationSeatRepository reservationSeatRepository) {
        this.cancellationService = cancellationService;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
    }

    /**
     * Endpoint 3: Release / cancel a reservation
     * POST /reservations/{id}/cancel
     * Only the reservation owner can cancel.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelReservation(@PathVariable("id") String id) {
        String userId = UserContext.getUserId();
        Reservation cancelled = cancellationService.cancelReservation(id, userId);

        return ResponseEntity.ok(Map.of(
                "reservation_id", cancelled.getId(),
                "status", cancelled.getStatus().name().toLowerCase(),
                "message", "Reservation successfully cancelled and seats released"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable("id") String id) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ReservationNotFoundException("Reservation not found with id: " + id));

        List<String> seats = reservationSeatRepository.findByReservationId(id).stream()
                .map(ReservationSeat::getSeatNumber)
                .sorted()
                .toList();

        ReservationResponse response = new ReservationResponse(
                reservation.getId(),
                reservation.getShowId(),
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.getStatus().name().toLowerCase()
        );

        return ResponseEntity.ok(response);
    }
}
