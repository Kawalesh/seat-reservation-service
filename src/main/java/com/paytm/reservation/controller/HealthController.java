package com.paytm.reservation.controller;

import com.paytm.reservation.dto.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Overall service health. Fails closed (503) if database is down.
     */
    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        boolean dbHealthy = checkDatabase();
        if (dbHealthy) {
            return ResponseEntity.ok(new HealthResponse("UP", "UP", Map.of("database", "connected")));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("DOWN", "DOWN", Map.of("database", "unreachable")));
        }
    }

    /**
     * Liveness probe: returns 200 as long as application process is running.
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, String>> liveness() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Readiness probe: checks downstream database connectivity and fails closed (503) if unreachable.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean dbHealthy = checkDatabase();
        if (dbHealthy) {
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "database", "UP"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "database", "DOWN",
                            "error", "Database dependency is unreachable"
                    ));
        }
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
