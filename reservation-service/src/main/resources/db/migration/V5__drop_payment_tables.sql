-- R2 cutover: payment-service owns payment state now (its own database's V1
-- recreated these tables there — ADR 0009). Dropping rather than orphaning is
-- schema honesty: a service that no longer owns a concern shouldn't carry its
-- ghost, and a stale copy of payments data lying around invites exactly the
-- cross-service reads the split forbids.
--
-- Data note: any rows in these tables are Phase 2a dev/test artifacts; the new
-- service starts empty by design (its schema story starts at its birth).

DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS payment_processed_events;
