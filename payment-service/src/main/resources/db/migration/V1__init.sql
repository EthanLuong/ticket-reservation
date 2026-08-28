CREATE TABLE payments (
                          id           UUID PRIMARY KEY,             -- app-assigned (@GeneratedValue UUID)
                          saga_id      UUID NOT NULL,                -- correlation only — no FK
                          amount_cents BIGINT NOT NULL,
                          status       TEXT NOT NULL,                -- 'AUTHORIZED' | 'FAILED' | 'REFUNDED'
                          gateway_ref  TEXT,                         -- mock charge id (answers ChargeCard's gatewayRef TODO)
                          created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment_processed_events (
                                          event_id     UUID PRIMARY KEY,             -- the dedup constraint IS the idempotency
                                          processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_saga_id ON payments (saga_id);  -- CancelChargeIfStarted looks up by saga

CREATE TABLE outbox (
                        id           UUID PRIMARY KEY,
                        aggregate_type TEXT NOT NULL,              -- 'Payment'
                        aggregate_id UUID NOT NULL,
                        topic        TEXT NOT NULL,
                        payload      JSONB NOT NULL,
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        processed_at TIMESTAMPTZ                   -- NULL = unpublished
);

CREATE INDEX idx_outbox_unprocessed ON outbox (created_at)
    WHERE processed_at IS NULL;                -- partial index — small even at scale