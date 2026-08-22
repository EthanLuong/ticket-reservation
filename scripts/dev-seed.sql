-- Dev seed data for local manual testing / demos.
--
-- There is no event-creation endpoint (EventController is read-only), so this
-- script is the only way to get browsable events + seats into a fresh local
-- database. Safe to re-run: every INSERT uses a fixed UUID + ON CONFLICT (id)
-- DO NOTHING, so running it twice is a no-op the second time.
--
-- Usage (see README.md "Frontend" section for the full docker compose command):
--   docker compose exec -T db psql -U postgres -d ticketreservation < scripts/dev-seed.sql
--
-- Schema reference: src/main/resources/db/migration/V1__init.sql

-- 2 events: one ~30 days out, one ~45 days out (relative to whenever this
-- script runs, so seed data never looks stale in a demo).
INSERT INTO events (id, name, description, venue, starts_at, ends_at)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'Rooftop Jazz Night',
     'An evening of live jazz on the rooftop terrace.',
     'The Bijou',
     NOW() + INTERVAL '30 days',
     NOW() + INTERVAL '30 days' + INTERVAL '3 hours'),
    ('22222222-2222-2222-2222-222222222222',
     'Indie Rock Showcase',
     'Four local bands, one stage, one night.',
     'Analog Hall',
     NOW() + INTERVAL '45 days',
     NOW() + INTERVAL '45 days' + INTERVAL '3 hours')
ON CONFLICT (id) DO NOTHING;

-- Event 1 (Rooftop Jazz Night): 24 seats, rows A and B, 12 seats each.
-- Seat ids are deterministic (prefix '111a1111'/'111b1111' + zero-padded seat
-- number) so re-running this script is idempotent.
INSERT INTO seats (id, event_id, seat_label, price_cents, status, version)
SELECT
    ('111a1111-1111-1111-1111-' || lpad(seat_num::text, 12, '0'))::uuid,
    '11111111-1111-1111-1111-111111111111',
    'A-' || seat_num,
    5000,
    'AVAILABLE',
    0
FROM generate_series(1, 12) AS seat_num
ON CONFLICT (id) DO NOTHING;

INSERT INTO seats (id, event_id, seat_label, price_cents, status, version)
SELECT
    ('111b1111-1111-1111-1111-' || lpad(seat_num::text, 12, '0'))::uuid,
    '11111111-1111-1111-1111-111111111111',
    'B-' || seat_num,
    5000,
    'AVAILABLE',
    0
FROM generate_series(1, 12) AS seat_num
ON CONFLICT (id) DO NOTHING;

-- Event 2 (Indie Rock Showcase): 12 seats, row A only.
INSERT INTO seats (id, event_id, seat_label, price_cents, status, version)
SELECT
    ('222a2222-2222-2222-2222-' || lpad(seat_num::text, 12, '0'))::uuid,
    '22222222-2222-2222-2222-222222222222',
    'A-' || seat_num,
    5000,
    'AVAILABLE',
    0
FROM generate_series(1, 12) AS seat_num
ON CONFLICT (id) DO NOTHING;

-- One deliberately over-limit seat (LLM-added 2026-07-27): reserving VIP-1
-- exercises the decline → compensation path — MockPaymentGateway declines
-- amounts > 10_000 cents, so $150.00 fails while every $50 seat succeeds.
INSERT INTO seats (id, event_id, seat_label, price_cents, status, version)
VALUES (
    '111f1111-1111-1111-1111-000000000001',
    '11111111-1111-1111-1111-111111111111',
    'VIP-1',
    15000,
    'AVAILABLE',
    0
)
ON CONFLICT (id) DO NOTHING;
