package com.ethanluong.ticketreservation.saga.events;

/**
 * Payload for {@link EventTypes#REFUND_CONFIRMED} on {@code payment.evt}.
 * Closes the compensation loop: COMPENSATING → CANCELLED (design §3.4).
 *
 * <p>Empty payload — see the keep-or-drop TODO in {@link PaymentConfirmed}.
 */
public record RefundConfirmed(
) {
}
