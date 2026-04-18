package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.domain.entity.Reservation;

import java.util.List;
import java.util.UUID;

public interface ReservationService {

    /**
     * Attempts to place a TTL hold on a seat for the given user.
     * <p>
     * Must be safe under concurrent callers racing for the same seat:
     * exactly one caller succeeds; the rest either fail fast with
     * {@link com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException}
     * or surface {@link org.springframework.dao.OptimisticLockingFailureException}
     * via the {@code @Version} on {@code Seat}.
     */
    Reservation reserve(UUID userId, UUID seatId);

    Reservation cancel(UUID userId, UUID reservationId);

    List<Reservation> myReservations(UUID userId);
}
