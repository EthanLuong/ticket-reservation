package com.ethanluong.ticketreservation.saga.events;

/**
 * Payload for {@link EventTypes#PAYMENT_FAILED} on {@code payment.evt}.
 * Drives AWAITING_PAYMENT → PAYMENT_FAILED → COMPENSATING (design §3.4).
 */
public record PaymentFailed(
        // TODO(you): a reason/code field? The saga doesn't branch on it, but
        //  "declined" vs "gateway timeout" is the difference between telling the
        //  user to try another card and telling them to retry. Decide, document.
) {
}
