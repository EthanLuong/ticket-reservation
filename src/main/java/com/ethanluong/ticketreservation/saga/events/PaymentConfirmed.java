package com.ethanluong.ticketreservation.saga.events;

/**
 * Payload for {@link EventTypes#PAYMENT_CONFIRMED} on {@code payment.evt}.
 * Drives AWAITING_PAYMENT → COMPLETED (design §3.4).
 *
 * <p>Currently an empty payload — the envelope's eventType alone carries the meaning.
 */
public record PaymentConfirmed(
        // TODO(you): gatewayRef (the mock/Stripe charge id)? The orchestrator
        //  doesn't need it to transition — but think about what you'd want in
        //  the saga audit trail when a customer disputes a charge.
        // TODO(you): decide whether empty-payload records like this stay as named
        //  vocabulary (documentation value) or die — for an empty body the consumer
        //  never deserializes anything; the EventTypes string is authoritative.
) {
}
