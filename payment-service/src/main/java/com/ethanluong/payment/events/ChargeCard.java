package com.ethanluong.payment.events;

/**
 * Payload for {@link EventTypes#CHARGE_CARD} on {@code payment.cmd}.
 * eventId / sagaId / occurredAt moved to {@link EventEnvelope} (ADR 0003) —
 * this record now carries only the type-specific body.
 * Still wire contract: a field change here is an eventVersion bump.
 */
public record ChargeCard(
        long amountCents
) {
}
