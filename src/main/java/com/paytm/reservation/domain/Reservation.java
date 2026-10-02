package com.paytm.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "reservations",
    indexes = {
        @Index(name = "idx_reservations_show_user", columnList = "show_id, user_id, status")
    }
)
public class Reservation {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Column(name = "show_id", nullable = false, length = 64)
    private String showId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Reservation() {
    }

    public Reservation(String id, String showId, String userId, Long amountPaise, ReservationStatus status) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getShowId() {
        return showId;
    }

    public void setShowId(String showId) {
        this.showId = showId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
