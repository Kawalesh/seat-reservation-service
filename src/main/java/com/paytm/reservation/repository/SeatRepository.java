package com.paytm.reservation.repository;

import com.paytm.reservation.domain.Seat;
import com.paytm.reservation.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Acquires pessimistic row-level write locks in strictly deterministic ascending
     * seat_number order. This eliminates cyclic wait conditions and guarantees deadlock-free
     * multi-seat locking under extreme concurrency.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.showId = :showId AND s.seatNumber IN :seatNumbers ORDER BY s.seatNumber ASC")
    List<Seat> findSeatsForUpdate(@Param("showId") String showId, @Param("seatNumbers") Collection<String> seatNumbers);

    List<Seat> findByShowIdOrderBySeatNumberAsc(String showId);

    long countByShowIdAndStatus(String showId, SeatStatus status);

    long countByShowId(String showId);
}
