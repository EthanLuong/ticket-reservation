package com.ethanluong.ticketreservation.api.exception;

import java.util.UUID;

/**
 * Thrown when the Redisson critical-section lock for a seat cannot be
 * acquired within the short wait window.
 * "another request is currently in flight for this seat, retry in a moment."
 */
public class SeatContentionException extends RuntimeException {
    private final UUID seatId;

    public SeatContentionException(UUID seatId) {
        super("Seat " + seatId + " is currently contested by another request; retry");
        this.seatId = seatId;
    }

    public UUID getSeatId() {
        return seatId;
    }
}
