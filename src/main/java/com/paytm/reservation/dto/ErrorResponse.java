package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class ErrorResponse {

    private String error;
    private String message;
    private Instant timestamp;
    private String path;

    @JsonProperty("trace_id")
    private String traceId;

    public ErrorResponse() {
    }

    public ErrorResponse(String error, String message, String path, String traceId) {
        this.error = error;
        this.message = message;
        this.timestamp = Instant.now();
        this.path = path;
        this.traceId = traceId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
