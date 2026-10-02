package com.paytm.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_show_seat", columnNames = {"show_id", "seat_number"})
    },
    indexes = {
        @Index(name = "idx_seats_show_status", columnList = "show_id, status")
    }
)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false, length = 64)
    private String showId;

    @Column(name = "seat_number", nullable = false, length = 32)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SeatStatus status;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Seat() {
    }

    public Seat(String showId, String seatNumber, SeatStatus status) {
        this.showId = showId;
        this.seatNumber = seatNumber;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShowId() {
        return showId;
    }

    public void setShowId(String showId) {
        this.showId = showId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
