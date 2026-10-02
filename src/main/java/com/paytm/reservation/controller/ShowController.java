package com.paytm.reservation.controller;

import com.paytm.reservation.dto.CreateShowRequest;
import com.paytm.reservation.dto.ReservationResponse;
import com.paytm.reservation.dto.ReservationResult;
import com.paytm.reservation.dto.ReserveSeatRequest;
import com.paytm.reservation.dto.ShowResponse;
import com.paytm.reservation.security.UserContext;
import com.paytm.reservation.service.ReservationService;
import com.paytm.reservation.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;
    private final ReservationService reservationService;

    public ShowController(ShowService showService, ReservationService reservationService) {
        this.showService = showService;
        this.reservationService = reservationService;
    }

    /**
     * Endpoint 1: Create a show (admin)
     * POST /shows
     */
    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse response = showService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Endpoint 4: Show state
     * GET /shows/{id}
     * Returns per-seat status and counts. Invariant: available + held + confirmed == total_seats.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ShowResponse> getShow(@PathVariable("id") String id) {
        ShowResponse response = showService.getShow(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint 2: Reserve a seat (authenticated user)
     * POST /shows/{id}/reserve
     * Identity comes from auth token via UserContext.
     */
    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeat(
            @PathVariable("id") String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody ReserveSeatRequest request) {

        String userId = UserContext.getUserId();
        ReservationResult result = reservationService.reserve(id, userId, request, headerIdempotencyKey);

        if (result.isReplay()) {
            return ResponseEntity.ok(result.getResponse());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.getResponse());
    }
}
