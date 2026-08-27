-- Idempotency-Key claims for POST /reservations (current-task card, 2026-08-18).
-- LLM-BUILT (boilerplate request — the claim/replay semantics live in IdempotencyService).
--
-- The UNIQUE constraint IS the concurrency control (same pattern as processed_events'
-- PK, lifted to the REST layer): two concurrent requests with the same key race the
-- INSERT; exactly one wins, the loser gets a unique violation and must decide
-- 409 / 422 / replay from the winner's row. Scope is per user + endpoint so one
-- user's key can never collide with another's (card Step 1 Hint 5).
-- created_at is indexed for the eventual TTL cleanup (~24h, Stripe precedent) —
-- whether rows are swept or kept is a deliberate open decision on the card.

CREATE TABLE idempotency_records (
    id              UUID PRIMARY KEY,            -- app-assigned (@GeneratedValue UUID)
    user_id         UUID NOT NULL,
    endpoint        TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash    TEXT NOT NULL,               -- SHA-256 of canonical request body
    status          TEXT NOT NULL,               -- 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
    response_status INT,                         -- null until COMPLETED
    response_body   TEXT,                        -- original response JSON, replayed verbatim
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_user_endpoint_key UNIQUE (user_id, endpoint, idempotency_key)
);
CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);
