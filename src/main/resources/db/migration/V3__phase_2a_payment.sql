-- Phase 2a payment component schema (design §3.5).
-- LLM-BUILT 2026-07-27 (skeleton request — migrations are known ground).
--
-- payment_processed_events is payment's OWN dedup table (ratified Q2, 2026-07-27):
-- when the package splits into a service (2b), its dedup state travels with it.
-- Deliberately NO foreign key from payments.saga_id to sagas(id): the payment
-- component may only know the saga by id-in-the-envelope — an FK would weld the
-- two schemas together and break the 2b database split. Same reason there is no
-- consumer column here: one table, one consumer, by construction.

CREATE TABLE payments (
    id           UUID PRIMARY KEY,             -- app-assigned (@GeneratedValue UUID)
    saga_id      UUID NOT NULL,                -- correlation only — no FK, see header
    amount_cents BIGINT NOT NULL,
    status       TEXT NOT NULL,                -- 'AUTHORIZED' | 'FAILED' | 'REFUNDED'
    gateway_ref  TEXT,                         -- mock charge id (answers ChargeCard's gatewayRef TODO)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_saga_id ON payments (saga_id);  -- CancelChargeIfStarted looks up by saga

CREATE TABLE payment_processed_events (
    event_id     UUID PRIMARY KEY,             -- the dedup constraint IS the idempotency
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
