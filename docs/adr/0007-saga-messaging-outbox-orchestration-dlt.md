# ADR 0007 — Saga messaging: transactional outbox, orchestration, per-saga ordering, DLT policy

**Status:** Accepted (records decisions shipped incrementally 2026-07-25 → 2026-08-18)
**Date:** 2026-08-21
**Phase:** 2a
**Sibling:** [ADR 0003](0003-event-typing.md) (wire format for the same topics) · [ADR 0008](0008-deliberately-not-transactional.md) (what these flows deliberately leave outside the transaction)

## Context

Phase 2a runs a booking saga between the reservation side and the payment component over Kafka (`payment.cmd` / `payment.evt`). Four decisions shape everything on those topics: how events leave the database, who owns saga state, what ordering is guaranteed, and what happens to messages that cannot be processed. They shipped one card at a time; this ADR records them and their rejected alternatives in one place.

## Decision 1 — Transactional outbox (not dual-write, not Kafka transactions)

Business state and the events describing it commit **atomically in one Postgres transaction**: `reserve()` writes the reservation, the saga row, and an `outbox` row containing the pre-serialized envelope. A `@Scheduled` relay drains `processed_at IS NULL` rows oldest-first, sends with a blocking `send().get()` per row, and marks `processed_at` **only after broker ack** (confirmed-send-then-mark), stopping on first failure so order holds.

- **Dual-write rejected:** commit-then-send loses the event if the app dies between the two; send-then-commit publishes a ghost event for state that never committed. There is no ordering of two independent systems that closes the window — that's the textbook dual-write problem, and the reason the outbox pattern exists.
- **Kafka transactions rejected:** they make *producer sends and consumer offsets* atomic, not *Postgres and producer* atomic. Bridging DB and Kafka commit still requires two-phase machinery Spring has been deprecating (ChainedTransactionManager) and it still doesn't survive the crash-between-commits window. Wrong tool for this seam.
- **Consequence — at-least-once, by contract:** a crash between send and mark republishes the row. Duplicates are *the consumer's problem by design*: every consumer dedups on `eventId` via its own `processed_events` table (insert-first, PK violation = already seen). `SagaE2EIT.republishAfterLostMark_consumerDedups` proves the full circle.

## Decision 2 — Orchestration (not choreography)

A central `SagaOrchestrator` owns an explicit `Saga` row with a state machine (`STARTED → AWAITING_PAYMENT → COMPLETED | COMPENSATING → CANCELLED | TIMED_OUT` path). The payment component is a dumb worker: it answers commands and emits events; it holds no saga state.

- "Where is this saga and why?" is answered by **one row**, not by reconstructing a story from N services' logs — that's the operational argument.
- The **30s timeout sweeper** needs an owner. In choreography nobody is responsible for noticing that nothing happened; the orchestrator is exactly the component whose job that is.
- The out-of-order policy (ADR 0003: log + skip, marker makes the skip permanent) needs a state to check against — only the orchestrator has one.
- **Choreography rejected**, not disparaged: with two participants it saves nothing today, and its real strength (decoupled growth of participants) is a Phase-2b+ concern. If the participant count grows, revisit.

## Decision 3 — Ordering: partition key = `sagaId`

Both topics have 3 partitions; every message is keyed by `sagaId`. Kafka guarantees order **within a partition**, so each saga's messages form a total order while different sagas interleave freely — which is the only ordering the domain needs (no cross-saga invariants exist).

- This is also the test-harness lever: `SagaE2EIT` proves async consumption by sending a sentinel with the same key — same key ⇒ same partition ⇒ the sentinel is consumed strictly after everything sent before it.
- Consequence: repartitioning the topics breaks per-saga ordering mid-flight. Partition count is effectively frozen while messages are in flight.

## Decision 4 — DLT policy: 3 attempts, matched partitions, explicit `.DLT` resolver

`DefaultErrorHandler` with `FixedBackOff(1000ms, 2)` — one initial attempt plus two retries — then `DeadLetterPublishingRecoverer` publishes the failed record to `<topic>.DLT`, **same partition number** (DLT topics are declared with matching partition counts, so provenance survives).

- **Not-retryable short-circuit:** `JacksonException` (tools.jackson — Jackson 3; a fasterxml exception type would never match) and `IllegalArgumentException` skip the retries entirely — a malformed payload will not become well-formed by waiting 1s. Straight to DLT; the listener moves on (poison pills cannot wedge the partition — `PaymentDltIT` proves the consumer keeps processing).
- **Explicit destination resolver, deliberately:** Spring Kafka 4.x changed the recoverer's default suffix to `-dlt`; our declared topics use `.DLT`. Without the explicit resolver the recoverer silently published to auto-created `-dlt` topics nobody watched — `PaymentDltIT` caught the framework default change. (Interview story; also the argument for testing infrastructure config at all.)
- **Manual triage story:** read the `.DLT` record (its headers carry the exception and original offset), fix the cause, republish the original envelope to the source topic. Consumer dedup on `eventId` makes the replay safe if the original had partially processed; a *fixed* payload should keep its `eventId` for the same reason.

## Consequences (cross-cutting)

- "Exactly-once" is deliberately not claimed anywhere: the honest contract is **at-least-once delivery + idempotent consumers**, stated per Decision 1 and enforced by dedup tables on both sides.
- Every guarantee above has a named test: outbox republish (`SagaE2EIT.republishAfterLostMark`), dedup (`duplicatePaymentConfirmed_noOps`), ordering (sentinel technique), out-of-order skip (`outOfOrderEvent_skippedByWrongStateGuard`), poison pill (`PaymentDltIT`).
