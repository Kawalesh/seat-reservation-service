package com.paytm.reservation.dto;

import java.time.Instant;
import java.util.Map;

public class HealthResponse {

    private String status;
    private String database;
    private Instant timestamp;
    private Map<String, Object> details;

    public HealthResponse() {
    }

    public HealthResponse(String status, String database, Map<String, Object> details) {
        this.status = status;
        this.database = database;
        this.timestamp = Instant.now();
        this.details = details;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
