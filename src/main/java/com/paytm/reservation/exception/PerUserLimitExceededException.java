package com.paytm.reservation.exception;

public class PerUserLimitExceededException extends RuntimeException {
    public PerUserLimitExceededException(String message) {
        super(message);
    }
}
