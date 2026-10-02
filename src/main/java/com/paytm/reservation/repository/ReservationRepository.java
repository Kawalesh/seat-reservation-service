package com.paytm.reservation.repository;

import com.paytm.reservation.domain.Reservation;
import com.paytm.reservation.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    List<Reservation> findByShowIdAndUserIdAndStatus(String showId, String userId, ReservationStatus status);
}
