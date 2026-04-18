-- V1: initial schema for ticket reservation.
--
-- Design notes:
--   * UUID primary keys (gen_random_uuid, built into Postgres 13+) — no sequence contention,
--     safe to generate client-side, standard for distributed systems.
--   * TIMESTAMPTZ everywhere (never naked TIMESTAMP).
--   * CHECK constraints instead of Postgres ENUM types — easier to evolve (adding a value
--     requires only a CHECK rewrite, not an ALTER TYPE).
--   * seats.version supports JPA @Version optimistic locking.
--   * Partial unique index on reservations enforces "at most one active reservation per seat"
--     at the DB level — a correctness backstop independent of the app-layer @Version check.

CREATE TABLE users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE events (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    venue           VARCHAR(255) NOT NULL,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT events_ends_after_starts CHECK (ends_at > starts_at)
);

CREATE INDEX idx_events_starts_at ON events (starts_at);

CREATE TABLE seats (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_label      VARCHAR(50)  NOT NULL,
    price_cents     BIGINT       NOT NULL CHECK (price_cents >= 0),
    status          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
                                 CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT seats_event_label_unique UNIQUE (event_id, seat_label)
);

CREATE INDEX idx_seats_event_status ON seats (event_id, status);

CREATE TABLE reservations (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id),
    seat_id         UUID         NOT NULL REFERENCES seats(id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'HELD'
                                 CHECK (status IN ('HELD', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Business-rule guarantee: at most one active reservation per seat.
-- Partial unique index means "non-active" rows (EXPIRED, CANCELLED) don't participate,
-- so users can retry and history is preserved.
CREATE UNIQUE INDEX idx_reservations_active_seat
    ON reservations (seat_id)
    WHERE status IN ('HELD', 'CONFIRMED');

-- Expiry sweeper job scans active holds approaching their deadline.
CREATE INDEX idx_reservations_held_expiry
    ON reservations (expires_at)
    WHERE status = 'HELD';

CREATE INDEX idx_reservations_user ON reservations (user_id);
