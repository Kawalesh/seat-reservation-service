package com.paytm.reservation.exception;

import com.paytm.reservation.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String getTraceId() {
        String traceId = MDC.get("trace_id");
        return (traceId != null) ? traceId : "unknown";
    }

    @ExceptionHandler(SeatTakenException.class)
    public ResponseEntity<ErrorResponse> handleSeatTaken(SeatTakenException ex, HttpServletRequest request) {
        log.warn("Reservation declined: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SEAT_ALREADY_TAKEN", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(PerUserLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handlePerUserLimit(PerUserLimitExceededException ex, HttpServletRequest request) {
        log.warn("Reservation declined: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PER_USER_LIMIT_EXCEEDED", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyMismatch(IdempotencyMismatchException ex, HttpServletRequest request) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_PAYLOAD_MISMATCH", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(ShowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleShowNotFound(ShowNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SHOW_NOT_FOUND", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFound(ReservationNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("RESERVATION_NOT_FOUND", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", ex.getMessage(), request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, request.getRequestURI(), getTraceId()));
    }

    /**
     * Translates database-level race constraints (e.g. concurrent unique index violations or lock timeouts)
     * into clean 409 Conflicts, guaranteeing zero 5xx outcomes under extreme concurrency bursts.
     */
    @ExceptionHandler({DataIntegrityViolationException.class, CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConcurrencyConflict(Exception ex, HttpServletRequest request) {
        log.warn("Database lock or constraint contention under concurrency: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENT_LOCK_CONFLICT", "Resource is being concurrently modified; please retry", request.getRequestURI(), getTraceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred", request.getRequestURI(), getTraceId()));
    }
}
