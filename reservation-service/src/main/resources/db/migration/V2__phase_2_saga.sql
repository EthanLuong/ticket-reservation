CREATE TABLE sagas (
                       id           UUID PRIMARY KEY,
                       type         TEXT NOT NULL,                -- 'BOOKING'
                       state        TEXT NOT NULL,                -- enum above
                       reservation_id UUID NOT NULL REFERENCES reservations(id),
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                       version      BIGINT NOT NULL DEFAULT 0     -- @Version optimistic
);
CREATE INDEX idx_sagas_state_created ON sagas (state, created_at)
    WHERE state IN ('AWAITING_PAYMENT');       -- partial index for timeout sweep

CREATE TABLE outbox (
                        id           UUID PRIMARY KEY,
                        aggregate_type TEXT NOT NULL,              -- 'Saga' | 'Payment'
                        aggregate_id UUID NOT NULL,
                        topic        TEXT NOT NULL,
                        payload      JSONB NOT NULL,
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        processed_at TIMESTAMPTZ                   -- NULL = unpublished
);
CREATE INDEX idx_outbox_unprocessed ON outbox (created_at)
    WHERE processed_at IS NULL;                -- partial index — small even at scale

CREATE TABLE processed_events (
                                  event_id     UUID PRIMARY KEY,
                                  consumer     TEXT NOT NULL,
                                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);