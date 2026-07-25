# ADR 0002 — Redis for reservation holds and distributed locks

**Status:** Accepted
**Date:** 2026-04-24
**Phase:** 1
**Supersedes:** none (Phase 0 used a Postgres-only `@Scheduled` sweeper for hold expiry)

## Context

Phase 0 shipped a single-service reservation system whose hold expiry was driven by a Postgres `@Scheduled` job. That worked for the single-instance deployment but had three weaknesses that block scale-out:

1. **Polling cost.** The sweeper queried Postgres every 5 seconds for `status=HELD AND expires_at < now()` rows. Latency-cheap but wasteful — most polls return zero rows.
2. **No cross-JVM coordination for hot events.** With multiple instances behind a load balancer, two `reserve()` calls for the same seat could both pass `seat.status == AVAILABLE` and both write — caught at the DB layer by the `@Version` optimistic lock and the partial unique index, but only after wasted DB round trips. Under heavy hot-event load (a 1000-seat venue selling out in seconds), the wasted-work cost is real.
3. **Sweeper ownership in multi-instance deploy.** Multiple JVMs running `@Scheduled` would all sweep, doubling DB load. Mitigation (ShedLock, single-pod-only deploy) is awkward.

Phase 1 introduces Redis to address all three. This ADR documents the choices made.

## Decisions

### D1. Redis-native TTL (`SET NX EX`) over `@Scheduled` polling

**Decision:** Replace the Postgres TTL sweeper with `SET NX EX hold:seat:{seatId} {reservationId} 600` — Redis expires the key automatically at T+600s.

**Why:**
- One atomic round trip — no race between "is it held?" and "claim it."
- Zero polling — Redis fires the expiry; we don't ask.
- Cross-JVM consistent — single source of truth visible to all instances.
- Eliminates the entire `@Scheduled` mechanism (and its multi-instance complications).

**Considered and rejected:**
- *DB-level row TTL* (Postgres has no native TTL; `pg_cron` would re-introduce polling).
- *Application-side caching with TTL* (e.g., Caffeine) — process-local, not cross-JVM.

### D2. Two-layer Redis coordination — TTL hold + Redisson `RLock`

**Decision:** Use BOTH primitives:
- `hold:seat:{seatId}` — `SET NX EX` for the **TTL hold** (10 min, business duration). The persistent "this seat is reserved" marker.
- `lock:seat:{seatId}` — Redisson `RLock` with `tryLock(100ms wait, 2s lease)` for the **critical-section lock**. Held only for the duration of a single `reserve()` or `cancel()` op (single-digit ms).

**Why:**
- The TTL hold gives correctness — `SET NX` is atomic so only one caller can claim.
- The RLock gives **efficiency under hot-event load**: 100 concurrent callers don't all hit `SET NX EX` and the DB; only the lock winner does. Other 99 fail fast at the lock with 409 (`SeatContentionException`).
- Layered with Postgres `@Version` (Phase 0 backstop) and the partial unique index, the system has three independent correctness defenses — interview-legible defense in depth.

**Considered and rejected:**
- *Manual `SET NX EX`-based lock with our own release-by-owner Lua script* — works, but reimplements what Redisson already provides correctly (release-by-owner via Lua, lease renewal, reentrancy semantics).
- *Postgres advisory locks* (`pg_advisory_lock`) — works for cross-JVM serialization, but conflates the "where do I lock?" decision with the database. Would also force every instance to share a Postgres connection per active lock.

### D3. Redisson over manual SETNX-based lock

**Decision:** Use Redisson's `RLock` API rather than rolling our own SETNX-based lock.

**Why:**
- Release-by-owner is non-trivial — Redisson's unlock is a Lua script that checks the lock value matches the caller's UUID before deleting. Easy to get wrong by hand (the well-known "TTL expires, holder times out, holder finishes, holder unlocks → deletes someone else's lock" bug).
- Reentrancy: Redisson tracks lock count per holder. Same-thread re-acquisition is non-blocking.
- Watchdog (when used) auto-extends the lease for unbounded-duration work. We use **explicit lease** here (2 s) to avoid the watchdog thread overhead and bound the crash blast radius.
- Library is well-maintained, well-documented, and the de-facto choice for Java distributed locks. Interview-legible — saying "I used Redisson `RLock`" communicates exactly what was done.

### D4. Per-seat lock granularity

**Decision:** Lock keyed on `lock:seat:{seatId}`, not `lock:event:{eventId}` or coarser.

**Why:**
- A reservation contests one seat. Two reservations for two different seats in the same event have no resource conflict — they should not serialize.
- Per-event would create a scalability ceiling: a 1000-seat event with bursty traffic would funnel all reserves through a single lock.
- Per-seat scales with seat count, which is the natural cardinality of the contention.

**Trade-off accepted:** under extreme bot scenarios where attackers hit every seat in an event simultaneously, we have N concurrent locks active rather than 1. This is the right answer — finer granularity preserves throughput for legitimate users.

### D5. Fail-closed on Redis outage (503 SERVICE_UNAVAILABLE)

**Decision:** When Redis is unreachable, reservation endpoints return 503 with a `retryable: true` `ProblemDetail`. They do **not** silently fall back to a DB-only path.

**Why:**
- Redis owns BOTH the TTL hold (correctness primitive) AND the critical-section lock (efficiency primitive).
- A "fall back to Postgres-only" path would lose cross-JVM serialization — two app instances could both let a `reserve()` through and create double-holds. The DB-layer `@Version` + partial unique index would catch some, but not all (e.g., a user could see their hold succeed while the seat is also held by someone else, until one of them tries to commit further state).
- Better to refuse service entirely until Redis recovers than violate the invariant the user relies on (one holder per seat).
- Implementation: `GlobalExceptionHandler` catches `RedisConnectionFailureException` (Spring Data Redis path) and `org.redisson.client.RedisException` (Redisson path), maps both to 503 with `Retry-After: 5`.

**Verified by:** `RedisOutageIT` — injects `@MockitoBean RedissonClient` whose `getLock()` throws, asserts the exception bubbles to the handler and zero rows hit Postgres.

### D6. Single-node Redis for Phase 1 (Upstash); Redlock as Phase 4+ hardening

**Decision:** Use a single-node Redis instance (Upstash managed) for Phase 1. Acknowledge but do not implement the Redlock multi-node algorithm.

**Why:**
- Upstash provides durability via replication and snapshotting on the managed side.
- Redlock (Antirez's multi-node algorithm) addresses the scenario where Redis primary fails over to a replica that hasn't caught up — a window where two clients could both believe they hold the lock. For our scale and Upstash's failover characteristics, this risk is acceptable.
- Kleppmann's 2016 critique of Redlock argues that without **fencing tokens** (monotonic IDs the resource server validates), even Redlock can permit a paused holder to mutate state after its lease expired. For our system, the resource (Postgres) already has fencing equivalents — `@Version` and partial unique index — so the distributed lock is an **efficiency** layer, not the sole correctness guarantee.
- Phase 4+ (scale signals) revisits this if real load measurements justify the complexity.

**See also:** the [distributed-locks deep dive](../../../../vault/wiki/concurrency/distributed-locks.md) for the full Kleppmann/antirez framing.

## Consequences

### Positive

- Sweeper retired entirely — no `@Scheduled`, no polling load on Postgres.
- Multi-instance deploys serialize correctly via the Redisson lock — no application-level changes needed when scaling out.
- `myReservations()` and `reserve()` both reconcile stale HELD state lazily — Postgres is self-healing on read.
- Three-layer defense in depth (Postgres `@Version` + unique index, Redis `SET NX EX`, Redisson `RLock`) — each layer catches a different failure mode.
- Failure-closed behavior on Redis outage is explicit, tested, and documented — clients can implement intelligent retry.

### Negative

- New external dependency (Redis). Application can no longer run without it; local dev requires Docker Compose Redis or Upstash. This is the cost of the cross-JVM coordination — accepted.
- Redisson adds ~5 MB of JAR weight + a Netty connection pool (we observed 24 connections at startup). Acceptable for the scale.
- Two clients (Spring Data Redis Lettuce + Redisson) coexist in the app, each with its own connection pool. Both speak RESP to the same Redis instance — no logical conflict, just two sets of connections. Documented in the Phase 1 teachings.

### Neutral / future work

- **Lazy reconciliation cost** is borne by `GET /api/reservations/me` callers — N Redis `EXISTS` round trips for a user with N HELD rows. In practice N is small (1–3); at scale, batch via `MGET` or pipeline. Not a Phase 1 concern.
- **`spring.data.redis.repositories.enabled=false`** silences a cosmetic JPA-repo-as-Redis-repo warning. Reverse if Spring Data Redis repositories are introduced later.
- **Fencing tokens** are not implemented. If Phase 4+ identifies a correctness scenario the existing defenses don't cover, revisit.

## References

- Antirez, [Distributed locks with Redis](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/) — Redlock specification.
- Martin Kleppmann, [How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html) — fencing token critique.
- [Spring Data Redis reference](https://docs.spring.io/spring-data/redis/reference/) — `StringRedisTemplate`, `setIfAbsent`, connection details.
- [Redisson documentation](https://redisson.pro/docs/) — `RLock`, `tryLock`, configuration.
- Internal: `vault/wiki/concurrency/distributed-locks.md` — full theory + Spring-specific patterns.
- Internal: `vault/areas/career/projects/ticket-reservation-phase-1-teachings.md` — implementation walkthrough with gotchas.
