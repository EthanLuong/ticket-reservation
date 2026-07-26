package com.ethanluong.ticketreservation.domain.type;

/**
 * Booking-saga states per docs/PHASES-2-5-DESIGN.md §3.4.
 * Terminal states: COMPLETED, CANCELLED — rows are kept for audit, never deleted.
 */
public enum SagaState {
    PENDING,
    AWAITING_PAYMENT,
    COMPLETED,
    PAYMENT_FAILED,
    TIMED_OUT,
    COMPENSATING,
    CANCELLED
}
