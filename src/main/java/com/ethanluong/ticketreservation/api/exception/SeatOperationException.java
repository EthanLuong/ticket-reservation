package com.ethanluong.ticketreservation.api.exception;

public class SeatOperationException extends RuntimeException {
    public SeatOperationException(String message) {
        super(message);
    }
}
