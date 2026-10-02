package com.paytm.reservation.domain;

import jakarta.persistence.*;

@Entity
@Table(
    name = "reservation_seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_reservation_seat", columnNames = {"reservation_id", "seat_number"})
    },
    indexes = {
        @Index(name = "idx_res_seats_res_id", columnList = "reservation_id"),
        @Index(name = "idx_res_seats_show_seat", columnList = "show_id, seat_number")
    }
)
public class ReservationSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, length = 64)
    private String reservationId;

    @Column(name = "show_id", nullable = false, length = 64)
    private String showId;

    @Column(name = "seat_number", nullable = false, length = 32)
    private String seatNumber;

    public ReservationSeat() {
    }

    public ReservationSeat(String reservationId, String showId, String seatNumber) {
        this.reservationId = reservationId;
        this.showId = showId;
        this.seatNumber = seatNumber;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
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
}
