package com.ethanluong.ticketreservation.domain.repository;

import com.ethanluong.ticketreservation.domain.entity.Seat;
import com.ethanluong.ticketreservation.domain.type.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    List<Seat> findAllByEvent_IdOrderBySeatLabelAsc(UUID eventId);

    List<Seat> findAllByEvent_IdAndStatusOrderBySeatLabelAsc(UUID eventId, SeatStatus status);
}
