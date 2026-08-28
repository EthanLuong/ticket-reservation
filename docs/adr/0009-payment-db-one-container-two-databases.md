# ADR 0009 — Payment data isolation: one Postgres container, two databases

**Status:** Accepted
**Date:** 2026-08-27
**Phase:** R2
**Sibling:** [ADR 0007](0007-saga-messaging-outbox-orchestration-dlt.md), [ADR 0008](0008-deliberately-not-transactional.md)

## Context

R2 extracts payment into its own deployable service. Database-per-service is the pattern being
instantiated: each service owns its schema, runs its own Flyway migrations, and connects with its
own credentials. The question this ADR records is what *physical* shape that logical isolation
takes in the dev compose stack, where one `postgres:17-alpine` container already exists.

Options considered:

- **(i) Second Postgres container** — full process isolation in dev.
- **(ii) One container, two databases** *(chosen)* — `payments` database owned by `payment_user`,
  created by an init script (`scripts/initdb/01-payments.sh`).
- **(iii) Two schemas in one database** — namespace separation inside `ticketreservation`.

## Decision

**One container, two databases.** Database-per-service is an *ownership* rule, not a hardware
rule — what it demands is that each service has its own schema, its own migration history, and
credentials that cannot reach the other's data. Container count is deployment topology; the
pattern lives one level up.

Why (ii) delivers the isolation that matters:

- **Credential isolation is real, not conventional.** `payment_user` owns `payments`, and
  `REVOKE CONNECT ON DATABASE ticketreservation FROM PUBLIC` removes the blanket CONNECT grant
  Postgres gives every role by default. Cross-service access now fails at *connection
  establishment* (`FATAL: permission denied for database` — verified live), not as a
  per-table grant accident that could silently widen as tables are added.
- **Cross-database joins are impossible, not just forbidden.** A single Postgres connection
  cannot query across databases. The boundary the microservice pattern asks for is enforced by
  the engine itself — the strongest guarantee available short of separate hardware.
- **Zero added weight.** No second container to version-align, memory-budget, and healthcheck in
  a dev stack that already runs four services and is about to run five.

Why not (i): a second container adds stack weight and version-alignment overhead but buys no
additional *logical* isolation over (ii) — and no meaningful prod parity either, because prod
isn't two containers on one host, it's RDS (see Consequences). Process-level isolation becomes
worth paying for exactly when the deployment target provides it anyway.

Why not (iii): schemas share a database, so one connection can join across them freely. The
boundary would be `search_path` convention plus table grants — precisely the "isolation by
accident" this decision exists to avoid, and nothing about it can be demoed as a refusal.

Mechanics on record: the init script is a `.sh` (not `.sql`) so the password comes from the
container environment instead of git; it runs only when `pgdata` initializes empty, so dev picks
it up via one deliberate `docker compose down -v`, while any fresh environment (CI included) gets
it automatically.

## Consequences

- **What is now impossible: cross-service SQL joins and single-transaction writes spanning both
  databases.** This is not a new cost — it is the cost the design already paid on purpose. The
  saga (ADR 0007) owns cross-service consistency through the outbox, and
  [ADR 0008](0008-deliberately-not-transactional.md) records why atomicity across the payment
  boundary was *rejected*, not lost. This ADR extends the same decision down into the storage
  layer: the databases refuse the coupling the transaction design already refused.
- **Prod maps to two RDS instances.** Separate blast radius, scaling, version upgrades, and
  backup/restore per service. The services cannot tell the difference between dev and prod
  because each one knows only its own JDBC URL and credentials from the environment — collapsing
  both databases into one dev container is pure weight-saving, invisible to the code.
- **The isolation check is one-directional in dev.** Reservation still connects as the `postgres`
  superuser, which bypasses every privilege check, so only "payment cannot reach reservation" is
  provable. Giving reservation its own non-superuser role is the remaining least-privilege step
  toward prod parity — and when that happens, the role needs an explicit
  `GRANT CONNECT ON DATABASE ticketreservation`, because the PUBLIC revoke applies to every
  non-superuser role, future ones included.
- Each service's Flyway history starts at its own V1 — payment's schema story begins at the
  service's birth instead of dragging reservation's V1–V4 along, which would re-couple the
  deploys the split just separated.
