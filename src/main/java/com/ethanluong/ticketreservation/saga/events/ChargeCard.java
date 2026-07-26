package com.ethanluong.ticketreservation.saga.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Command on {@code payment.cmd}: reservation-side orchestrator → payment component.
 * Wire contract (design §2.2) — a field change here is an API change.
 */
public record ChargeCard(
        UUID eventId,          // fresh per message — what processed_events dedups on
        UUID sagaId,           // correlation + Kafka partition key
        OffsetDateTime occurredAt,
        long amountCents
) {
}
