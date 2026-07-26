package com.ethanluong.ticketreservation.saga.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event on {@code payment.evt}: payment component → orchestrator.
 * Closes the compensation loop: COMPENSATING → CANCELLED (design §3.4).
 */
public record RefundConfirmed(
        UUID eventId,
        UUID sagaId,
        OffsetDateTime occurredAt
) {
}
