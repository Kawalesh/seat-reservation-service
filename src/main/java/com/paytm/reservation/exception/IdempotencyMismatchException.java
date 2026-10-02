package com.paytm.reservation.exception;

public class IdempotencyMismatchException extends RuntimeException {
    public IdempotencyMismatchException(String message) {
        super(message);
    }
}
