package com.paytm.reservation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "service", "Seat Reservation at Scale (Paytm Money)",
                "status", "UP",
                "version", "1.0.0",
                "endpoints", Map.of(
                        "health_ready", "/health/ready",
                        "health_live", "/health/live",
                        "metrics", "/metrics",
                        "shows", "POST /shows, GET /shows/{id}",
                        "reserve", "POST /shows/{id}/reserve",
                        "cancel", "POST /reservations/{id}/cancel"
                )
        ));
    }
}
