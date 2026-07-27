package com.ethanluong.ticketreservation.saga.events;

/**
 * Payload for {@link EventTypes#CANCEL_CHARGE_IF_STARTED} on {@code payment.cmd}:
 * sent when the saga times out (design §3.4) — "if you charged, refund; if you
 * didn't, don't." Payment side decides which.
 */
public record CancelChargeIfStarted(
        // TODO(you): does payment need anything else to find "the charge for this
        //  saga"? Look at the payments table in design §3.5 before answering —
        //  the answer is probably "no, the envelope's sagaId is enough," but know WHY.
) {
}
