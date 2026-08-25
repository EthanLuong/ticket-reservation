package com.ethanluong.ticketreservation.api.exception;

public class CancellationWindowClosedException extends RuntimeException {
    public CancellationWindowClosedException(String message) {
        super(message);
    }
}
