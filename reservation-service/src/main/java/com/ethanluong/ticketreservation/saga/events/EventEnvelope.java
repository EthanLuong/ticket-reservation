package com.ethanluong.ticketreservation.saga.events;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The generic wire envelope for every message on {@code payment.cmd} / {@code payment.evt}
 * (design §2.2, ADR 0003). Common metadata lives here; the type-specific payload rides as
 * raw JSON and is materialized by the consumer <i>after</i> switching on {@link #eventType()}
 * — one parse, no double-deserialize.
 *
 * <p><b>Wire contract:</b> these field names and the {@link EventTypes} strings are frozen
 * once real messages exist. A payload shape change bumps {@link #eventVersion()} instead.
 */
public record EventEnvelope(
        UUID eventId,          // fresh per message — what processed_events dedups on
        String eventType,      // discriminator — one of EventTypes.*
        int eventVersion,      // 1 until a payload's shape changes
        OffsetDateTime occurredAt,
        UUID sagaId,           // correlation + Kafka partition key
        JsonNode payload       // type-specific body; empty object {} for marker events
) {
}
