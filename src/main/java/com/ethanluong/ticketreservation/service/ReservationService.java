package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.domain.entity.Reservation;

import java.util.List;
import java.util.UUID;

public interface ReservationService {

    /**
     * Attempts to place a Redis-backed TTL hold on a seat for the given user.
     * <p>
     * Must be safe under concurrent callers racing for the same seat: exactly
     * one caller succeeds. Serialization is Redisson {@code RLock}
     * ({@code lock:seat:{id}}) for the critical section, plus {@code SET NX EX}
     * on {@code hold:seat:{id}} for the TTL hold itself. Loser outcomes are:
     * <ul>
     *   <li>{@link com.ethanluong.ticketreservation.api.exception.SeatNotAvailableException}
     *       — the seat is currently held by someone else.</li>
     *   <li>{@link com.ethanluong.ticketreservation.api.exception.SeatContentionException}
     *       — another operation for the same seat is in flight right now; retry.</li>
     * </ul>
     */
    Reservation reserve(UUID userId, UUID seatId);

    Reservation cancel(UUID userId, UUID reservationId);

    /**
     * Returns the user's reservations, reconciling any {@code HELD} rows
     * whose Redis TTL hold has already expired (flips them to {@code EXPIRED}
     * and frees the associated seat). Replaces the retired {@code @Scheduled}
     * sweeper — reconciliation is now lazy, driven by reads.
     */
    List<Reservation> myReservations(UUID userId);
}
