# ADR 0003 — Event typing: in-payload discriminator via generic envelope

**Status:** Accepted
**Date:** 2026-07-26
**Phase:** 2a
**Sibling:** [ADR 0007](0007-saga-messaging-outbox-orchestration-dlt.md) (outbox, orchestration, ordering, DLT for the same topics)
**Supersedes:** the flat per-record contracts committed with the orchestrator skeleton (each record carried its own eventId/sagaId/occurredAt; no type discriminator existed)

## Context

Phase 2a introduces Kafka messaging between the reservation-side saga orchestrator and the payment component. A consumer pulling bytes off `payment.cmd` / `payment.evt` must know which contract type a message is before it can deserialize and dispatch — `ChargeCard` vs `CancelChargeIfStarted` on the command topic, three event types on the reply topic.

The deciding constraint is the **transactional outbox** (design §3, ADR-worthy in its own right): events are serialized to JSON at business-transaction time and stored in the `outbox` table; the relay publisher later ships that **pre-serialized string** to Kafka. The publisher never has the Java object in hand — any typing scheme that depends on serializer-time knowledge of the class doesn't fit this producer.

## Decision

Every message on both topics is a **generic envelope** (design §2.2), serialized as one JSON document:

```json
{
  "eventId":      "uuid — fresh per message; the processed_events dedup key",
  "eventType":    "ChargeCard — the discriminator, one of EventTypes.*",
  "eventVersion": 1,
  "occurredAt":   "ISO-8601",
  "sagaId":       "uuid — correlation + partition key",
  "payload":      { "amountCents": 4200 }
}
```

- `EventEnvelope` (record, `payload` as `JsonNode`) carries the common metadata; type-specific bodies are separate payload records (`ChargeCard(amountCents)`, …).
- The discriminator strings live in `EventTypes` as compile-time constants, sibling to `KafkaTopics`.
- Consumers do one parse to `EventEnvelope`, `switch` on `eventType()`, then `treeToValue` the payload — one pass, no double-deserialize.
- **Marker events** (PaymentConfirmed, PaymentFailed, RefundConfirmed, CancelChargeIfStarted currently carry no data): payload is the **empty object `{}`**, never `null`. Proven by `EventEnvelopeRoundTripTest`.

## Consequences

- **Wire contract is frozen:** `EventTypes` string values, envelope field names, and payload field names must never be renamed once messages exist. Payload shape changes bump `eventVersion`; consumers handle both versions or fail loud.
- Unknown `eventType` at a consumer → not an exception loop: log + route to the topic's DLT (poison-pill handling, design §3.7).
- **Out-of-order policy:** an event arriving when the saga is not in a state that accepts it (e.g. `PaymentConfirmed` for an already-CANCELLED saga) is logged and skipped; `processed_events` still records its eventId so a redelivery stays a no-op.
- `EventEnvelopeRoundTripTest` is the contract regression net. Known gap: it uses a bare `ObjectMapper` while outbox rows are written by Spring's configured mapper — if the Spring mapper is ever customized, harden the test to assert against a known-good JSON literal.

## Considered and rejected

- **Spring Kafka type headers (`__TypeId__` + `JsonDeserializer` type mappings).** Idiomatic Spring and least code for a `kafkaTemplate.send(object)` producer — but our producer publishes pre-serialized strings from the outbox. The type would have to be persisted in a new `outbox` column and manually stamped onto each `ProducerRecord`, splitting the contract across payload and header, and any non-Spring consumer must replicate a framework convention to participate.
- **Sealed interface + Jackson polymorphic typing (`@JsonTypeInfo(property = "eventType")`).** Same wire format as the chosen design with better ergonomics in Java (single `readValue` to the sealed type, compiler-exhaustive `switch`). Rejected as more machinery than the project needs right now; the wire contract is identical, so migrating to it later is a consumer-side refactor, not a wire change.
