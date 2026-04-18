package com.ethanluong.ticketreservation.api.exception;

import java.util.UUID;

public class SeatNotAvailableException extends RuntimeException {
    private final UUID seatId;

    public SeatNotAvailableException(UUID seatId) {
        super("Seat " + seatId + " is not available");
        this.seatId = seatId;
    }

    public UUID getSeatId() {
        return seatId;
    }
}
