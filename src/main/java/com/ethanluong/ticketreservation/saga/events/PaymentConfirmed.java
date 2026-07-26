package com.ethanluong.ticketreservation.saga.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event on {@code payment.evt}: payment component → orchestrator.
 * Drives AWAITING_PAYMENT → COMPLETED (design §3.4).
 */
public record PaymentConfirmed(
        UUID eventId,
        UUID sagaId,
        OffsetDateTime occurredAt
        // TODO(you): gatewayRef (the mock/Stripe charge id)? The orchestrator
        //  doesn't need it to transition — but think about what you'd want in
        //  the saga audit trail when a customer disputes a charge.
) {
}
