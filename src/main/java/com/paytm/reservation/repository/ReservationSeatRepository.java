package com.paytm.reservation.repository;

import com.paytm.reservation.domain.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    List<ReservationSeat> findByReservationId(String reservationId);

    @Query("SELECT COUNT(rs) FROM ReservationSeat rs JOIN Reservation r ON rs.reservationId = r.id " +
           "WHERE r.showId = :showId AND r.userId = :userId AND r.status = 'CONFIRMED'")
    long countActiveSeatsForUser(@Param("showId") String showId, @Param("userId") String userId);
}
