package com.ethanluong.ticketreservation.saga.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * The wire-contract regression net (ADR 0003). Plain unit test — no Spring, no
 * containers: the envelope is just JSON in, JSON out. If this test breaks, a
 * message on the wire broke with it.
 */
class EventEnvelopeRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("ChargeCard envelope round-trips with visible eventType discriminator")
    void chargeCardEnvelope_roundTrips() {
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), EventTypes.CHARGE_CARD, 1, OffsetDateTime.parse("2026-07-26T12:00:00Z"), UUID.randomUUID(), mapper.valueToTree(new ChargeCard(4200)));

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"eventType\":\"ChargeCard\"");

        EventEnvelope back = mapper.readValue(json, EventEnvelope.class);
        assertThat(mapper.treeToValue(back.payload(), ChargeCard.class).amountCents()).isEqualTo(4200);
        assertThat(back.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-07-26T12:00:00Z"));

    }

    @Test
    @DisplayName("empty-payload envelope (PaymentConfirmed) round-trips")
    void emptyPayloadEnvelope_roundTrips() {
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), EventTypes.PAYMENT_CONFIRMED, 1, OffsetDateTime.parse("2026-07-26T12:00:00Z"), UUID.randomUUID(), mapper.createObjectNode());

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"eventType\":\"PaymentConfirmed\"");

        EventEnvelope back = mapper.readValue(json, EventEnvelope.class);
        assertThat(back.payload().isEmpty()).isTrue();
        assertThat(back.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-07-26T12:00:00Z"));

    }
}
