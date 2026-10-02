package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class ReserveSeatRequest {

    @NotEmpty(message = "seats list cannot be empty")
    private List<String> seats;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    public ReserveSeatRequest() {
    }

    public ReserveSeatRequest(List<String> seats, String idempotencyKey) {
        this.seats = seats;
        this.idempotencyKey = idempotencyKey;
    }

    public List<String> getSeats() {
        return seats;
    }

    public void setSeats(List<String> seats) {
        this.seats = seats;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
