/**
 * Payment component — in-process for Phase 2a, split-ready for 2b (design §2.1).
 * LLM-BUILT boundary note, 2026-07-27.
 *
 * <p><b>Import discipline (the Q1 answer, enforced by review not tooling):</b>
 * this package may import:
 * <ul>
 *   <li>{@code saga.events.*} — the shared wire contracts (EventEnvelope, payload
 *       records, EventTypes, KafkaTopics). These would be a shared library after 2b.</li>
 *   <li>{@code domain.entity.OutboxEntry} + {@code domain.repository.OutboxEntryRepository}
 *       — sanctioned shared infrastructure (ratified Q3: one outbox table, one publisher,
 *       {@code aggregate_type='Payment'} rows). After 2b, payment gets its own copy.</li>
 * </ul>
 * It must NOT import {@code SagaOrchestrator}, {@code SagaRepository}, or any
 * reservation-side entity — if a bean from the saga side appears in here, the
 * Kafka boundary (the whole point of the phase) has been silently deleted.
 */
package com.ethanluong.ticketreservation.payment;
