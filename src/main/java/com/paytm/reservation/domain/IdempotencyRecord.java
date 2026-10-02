package com.paytm.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "idempotency_records",
    indexes = {
        @Index(name = "idx_idemp_user_key", columnList = "user_id, idempotency_key", unique = true)
    }
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "show_id", nullable = false, length = 64)
    private String showId;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "reservation_id", nullable = false, length = 64)
    private String reservationId;

    @Lob
    @Column(name = "response_payload", nullable = false, columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String userId, String showId, String requestHash, String reservationId, String responsePayload) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.showId = showId;
        this.requestHash = requestHash;
        this.reservationId = reservationId;
        this.responsePayload = responsePayload;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getShowId() {
        return showId;
    }

    public void setShowId(String showId) {
        this.showId = showId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
