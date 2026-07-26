package com.ethanluong.ticketreservation.saga.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event on {@code payment.evt}: payment component → orchestrator.
 * Drives AWAITING_PAYMENT → PAYMENT_FAILED → COMPENSATING (design §3.4).
 */
public record PaymentFailed(
        UUID eventId,
        UUID sagaId,
        OffsetDateTime occurredAt
        // TODO(you): a reason/code field? The saga doesn't branch on it, but
        //  "declined" vs "gateway timeout" is the difference between telling the
        //  user to try another card and telling them to retry. Decide, document.
) {
}
