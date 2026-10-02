package com.paytm.reservation.dto;

public class ReservationResult {

    private final ReservationResponse response;
    private final boolean isReplay;

    public ReservationResult(ReservationResponse response, boolean isReplay) {
        this.response = response;
        this.isReplay = isReplay;
    }

    public ReservationResponse getResponse() {
        return response;
    }

    public boolean isReplay() {
        return isReplay;
    }
}
