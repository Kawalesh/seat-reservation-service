package com.paytm.reservation.dto;

public class ShowCounts {

    private long available;
    private long held;
    private long confirmed;

    public ShowCounts() {
    }

    public ShowCounts(long available, long held, long confirmed) {
        this.available = available;
        this.held = held;
        this.confirmed = confirmed;
    }

    public long getAvailable() {
        return available;
    }

    public void setAvailable(long available) {
        this.available = available;
    }

    public long getHeld() {
        return held;
    }

    public void setHeld(long held) {
        this.held = held;
    }

    public long getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(long confirmed) {
        this.confirmed = confirmed;
    }
}
