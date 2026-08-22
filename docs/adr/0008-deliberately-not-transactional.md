# ADR 0008 — What is deliberately NOT transactional (and why that's the design)

**Status:** Accepted
**Date:** 2026-08-21
**Phase:** 2a
**Sibling:** [ADR 0007](0007-saga-messaging-outbox-orchestration-dlt.md)

## Context

Phase 1 and 2a both contain writes that could *look* like bugs to a reviewer expecting one atomic transaction around each request: a Redis delete that can't roll back, and idempotency-record updates that commit independently of the business transaction they guard. Neither is an oversight. This ADR records the principle with its two exhibits, because "why isn't this in the transaction?" is the first question a code review (or interview) asks.

**The principle:** some writes derive their entire value from committing at a *different time* than the business transaction. Forcing them into one atomic boundary doesn't make the system safer — it breaks the very function those writes exist to perform. The cost is small, named crash windows, each with a compensating mechanism.

## Exhibit A — the Redis hold `DEL` (Phase 1 compensation)

Releasing a seat hold (`cancel()`, or compensation after a failed reserve) deletes the Redis key **after** the Postgres transaction commits. Redis is not a transactional participant; there is no rollback for a `DEL`.

- Deleting *inside* the tx would release the hold and then possibly roll back the cancellation — a seat marked held in Postgres with no hold in Redis, manufactured by our own code.
- **Crash window:** commit succeeds, `DEL` never runs → an orphaned hold key. **Mitigation:** the key's 10-minute TTL expires it, and lazy reconciliation treats DB `expires_at` as the authority (Redis key absence ≠ expired, key presence ≠ held — the m0 audit's C2/I2 fix). The `DEL` itself is idempotent, so retrying is always safe.

## Exhibit B — the idempotency record (Phase 2a, POST /reservations)

All three idempotency writes commit independently of the business transaction — each for its own reason:

1. **The claim (`IN_PROGRESS` insert)** commits in its own `TransactionTemplate` transaction *before* the handler runs. Its whole purpose is to be **visible to concurrent duplicates while the business work is still in flight** — inside the business tx it would be uncommitted, invisible, and the double-execution window it exists to close would reopen. (Same reasoning applies to the guarded `FAILED→IN_PROGRESS` retry flip: it's a mini-claim.)
2. **The `COMPLETED` mark** (status + stored response) commits in the repository's own transaction immediately after the handler returns. Nothing later in the calling thread can unwind it — a replayed response can never be "taken back" by an unrelated failure during response writing.
3. **The `FAILED` mark** must survive the very exception that caused it — if it shared the business transaction, the rollback that follows the failure would erase the mark, stranding the record `IN_PROGRESS` and bricking the key. It commits separately, then the exception is rethrown.

- **Crash window:** process dies after the claim commits but before `COMPLETED`/`FAILED` is written → the record is stuck `IN_PROGRESS` and that key answers `409 Retry-After` indefinitely. **Mitigation today:** the key is scoped per user+endpoint and client-minted per checkout attempt, so the blast radius is one user's one attempt; a fresh attempt mints a fresh key and proceeds. **Open decision, on record:** a TTL sweep over `created_at` (indexed for this purpose in `V4`, ~24h Stripe-style) that expires stale `IN_PROGRESS` rows — deliberately deferred until the row volume or a real stuck key justifies it.

## Consequences

- Reviewers and interviewers get one link instead of two suspicious-looking code sites.
- The pattern generalizes: before wrapping a write into the business transaction, ask *when* it needs to be visible and *what* must survive a rollback. If either answer is "not at commit time," it doesn't belong inside.
- Both exhibits carry their proofs: Exhibit A's reconciliation semantics in the Redis IT suite; Exhibit B's in `IdempotencyIT` (the in-flight 409 proves claim visibility; the failure-path test proves the `FAILED` mark survives the rethrow).
