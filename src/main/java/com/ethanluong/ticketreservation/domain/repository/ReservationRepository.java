package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.Reservation;
import com.ethanluong.ticketreservation.domain.type.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<Reservation> findAllByStatusAndExpiresAtBefore(ReservationStatus status, OffsetDateTime cutoff);
}
