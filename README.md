# Event Ticket Reservation System

A Spring Boot event ticketing service handling concurrent seat reservations with cross-JVM coordination: Redis-native TTL holds, a Redisson distributed lock for hot-event contention, and a Postgres `@Version` + partial unique index backstop. Built as an interview portfolio piece.

**Live demo:** [https://ticket-reservation-production-7193.up.railway.app](https://ticket-reservation-production-7193.up.railway.app)
**Repo:** [github.com/EthanLuong/ticket-reservation](https://github.com/EthanLuong/ticket-reservation)

---

## Status

**Phase 1 (Redis holds + distributed lock) — SHIPPED.** Reservation TTL is now Redis-native (atomic `SET NX EX`), critical sections are wrapped in a Redisson `RLock` for cross-JVM serialization, and the `@Scheduled` Postgres sweeper has been retired in favor of two-path lazy reconciliation. Failure-closed (503) on Redis outage — see [`docs/adr/0002-redis-for-holds-and-locks.md`](docs/adr/0002-redis-for-holds-and-locks.md) for the design decisions.

**Phase 0 (reservation foundation) — SHIPPED.** Single-service reservation system with JWT auth, optimistic-locked seat holds, and Testcontainers-proven race invariants. Deployed on Railway with managed Postgres.

Phased roadmap (Phase 1–5) below.

---

## Quick start

### Option A — Docker Compose (prod-parity local)

```bash
docker compose up --build
```

Spins up Postgres 17-alpine + Redis 7-alpine + the service, gated on healthchecks for both. App listens on `http://localhost:8080`. Health probe:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}    # 200 when Postgres + Redis are both up
```

If Redis is unreachable, reservation endpoints return 503 with a retryable `ProblemDetail`; reads (events, seats) continue to work since they don't touch Redis.

### Option B — Maven dev loop (fastest)

Requires Docker Desktop running (for the Testcontainers-backed dev Postgres).

```bash
./mvnw spring-boot:test-run    # dev run, auto-provisions Testcontainers Postgres
./mvnw verify                  # full test suite + package
```

The `test-run` goal auto-wires a disposable Postgres container via `@ServiceConnection`, so no manual DB setup is needed.

---

## Architecture

Single service, two backing stores (Postgres for system of record, Redis for ephemeral coordination). Phase 1 introduces Redis specifically for cross-JVM coordination — Phase 0's `@Version` + partial unique index remain as the DB-level correctness backstop.

```
                    ┌────────────────────────────┐
                    │  Client (curl / Postman)   │
                    └──────────────┬─────────────┘
                                   │ HTTPS + Bearer JWT
                                   ▼
     ┌─────────────────────────────────────────────────────────┐
     │                   Spring Boot App                       │
     │                                                         │
     │  ┌──────────────┐   ┌────────────────┐  ┌────────────┐  │
     │  │  Controllers │──▶│   Services     │─▶│  Repos     │──┼──▶ Postgres
     │  │  (REST)      │   │  (Tx Template) │  │  (JPA)     │  │
     │  └──────────────┘   └────────┬───────┘  └────────────┘  │
     │                              │                          │
     │  ┌──────────────────┐        │                          │
     │  │  Security Filter │        │                          │
     │  │  Chain (JWT)     │        │                          │
     │  └──────────────────┘        ▼                          │
     │                     ┌──────────────────┐                │
     │                     │ ReservationHold- │                │
     │                     │ Store            │────────────────┼──▶ Redis
     │                     │  - SET NX EX     │   Lettuce      │
     │                     │    (TTL hold)    │   (Spring Data)│
     │                     │  - Redisson      │                │
     │                     │    RLock         │   Redisson     │
     │                     │    (crit. sec.)  │                │
     │                     │  - hasHold (read)│                │
     │                     └──────────────────┘                │
     └─────────────────────────────────────────────────────────┘
                  │                              │
                  ▼                              ▼
   ┌──────────────────────────────┐   ┌──────────────────────────────┐
   │   PostgreSQL 17              │   │   Redis 7                    │
   │   - Flyway migrations        │   │   - hold:seat:{id} (TTL key) │
   │   - Partial unique index     │   │   - lock:seat:{id} (RLock)   │
   │     backstop                 │   │   - Upstash in prod          │
   │   - @Version optimistic col  │   │                              │
   │   - System of record         │   │   Coordination only —        │
   │                              │   │   no durable user state      │
   └──────────────────────────────┘   └──────────────────────────────┘
```

### Domain

```
Event  1───N  Seat  1───N  Reservation  N───1  User
                                status ∈ {HELD, CONFIRMED, EXPIRED, CANCELLED}
```

Full schema: [`src/main/resources/db/migration/V1__init.sql`](src/main/resources/db/migration/V1__init.sql).

### Reservation flow (Phase 1)

```
reserve(userId, seatId)
  ├─ withSeatLock(seatId) — Redisson tryLock(100ms wait, 2s lease)
  │      └─ contention → SeatContentionException → 409 (retryable=true)
  │
  ├─ tryHold(seatId, reservationId, 10min) — SET NX EX hold:seat:{id}
  │      └─ collision → SeatNotAvailableException → 409
  │
  ├─ TransactionTemplate.execute:
  │      ├─ load seat from Postgres
  │      ├─ if seat.status == HELD → reconcile stale rows to EXPIRED, continue
  │      ├─ flip seat.status = HELD, save (saveAndFlush — @Version checked)
  │      └─ insert reservation row (id pre-assigned by service)
  │
  └─ on tx exception → holdStore.release(seatId)  (compensating DEL)
```

Cancellation mirrors the structure: lock → tx (set CANCELLED, free seat) → DEL Redis hold key after commit.

`myReservations()` lazily reconciles HELD-with-no-Redis-key rows to EXPIRED on read — replaces the retired `@Scheduled` sweeper.

---

## API reference

All endpoints are JSON. Protected endpoints require `Authorization: Bearer <token>` obtained from `/api/auth/login`.

### Auth

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | Public | Create account, returns JWT |
| `POST` | `/api/auth/login` | Public | Exchange credentials for JWT |

### Events (read-only, public)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/events?page=0&size=20` | Paged list of upcoming events |
| `GET` | `/api/events/{id}` | Single event by ID |

### Seats (read-only, public)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/seats?eventId={uuid}&status={AVAILABLE\|HELD\|SOLD}` | Seats for an event; `status` filter optional |

### Reservations (JWT required)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/reservations` | Hold a seat (body: `{"seatId":"<uuid>"}`) |
| `DELETE` | `/api/reservations/{id}` | Cancel own reservation, release seat |
| `GET` | `/api/reservations/me` | List caller's reservations |

### Operational

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | Liveness probe |
| `GET` | `/actuator/info` | Build info |

### Example: full booking flow

```bash
BASE=https://ticket-reservation-production-7193.up.railway.app

# 1. Register
TOKEN=$(curl -s -X POST $BASE/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"password12345","displayName":"Demo"}' \
  | jq -r .token)

# 2. Pick an event + seat
EVENT_ID=$(curl -s $BASE/api/events | jq -r '.content[0].id')
SEAT_ID=$(curl -s "$BASE/api/seats?eventId=$EVENT_ID&status=AVAILABLE" | jq -r '.[0].id')

# 3. Reserve
curl -X POST $BASE/api/reservations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seatId\":\"$SEAT_ID\"}"
```

Error responses follow [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) (`application/problem+json`). Headline cases:

| Status | `type` slug | When | Client action |
|---|---|---|---|
| `409` | `seat-not-available` | Seat is currently held by another user | Pick another seat |
| `409` | `seat-contention` | Another reserve/cancel for this seat is in flight (Redisson lock contention) | **Auto-retry** — `retryable: true` flag set |
| `409` | `optimistic-lock` | DB-level `@Version` race lost (rare under Phase 1's lock + TTL) | Refresh state, retry |
| `503` | `redis-unavailable` | Redis is unreachable — coordination layer is down | Retry after `retryAfterSeconds` (5s) |

Example for the double-book attempt:

```json
{
  "type": "https://ticket-reservation.example/errors/seat-not-available",
  "title": "Seat not available",
  "status": 409,
  "detail": "Seat 9c8f... is not available",
  "seatId": "9c8f..."
}
```

Example for Redis outage:

```json
{
  "type": "https://ticket-reservation.example/errors/redis-unavailable",
  "title": "Service temporarily unavailable",
  "status": 503,
  "detail": "Reservation service is temporarily unavailable. Please retry shortly.",
  "retryable": true,
  "retryAfterSeconds": 5
}
```

---

## Design decisions

### 1. Three-layer concurrency defense (not one)

Booking the same seat from multiple clients at the same instant must allow **exactly one** to win. I layered three independent defenses, each catching a different race class:

| Layer | Check | Race class caught |
|---|---|---|
| 1. App-layer fast-fail | `seat.status == AVAILABLE` at service entry | Naive sequential double-book |
| 2. JPA optimistic lock | `@Version` bump fails on concurrent commit | Two threads both passed the status check |
| 3. Partial unique index | `CREATE UNIQUE INDEX ... WHERE status IN ('HELD','CONFIRMED')` | Any race that bypasses Hibernate (bulk SQL, manual insert) |

Layer 1 is cheap and catches the normal case. Layer 2 is the primary correctness guarantee. Layer 3 is a DB-level backstop — interviewers specifically ask *"what if the app is bypassed?"*

**Non-obvious gotcha I hit:** Hibernate flushes INSERTs before UPDATEs by default. Without `saveAndFlush(seat)` before the reservation insert, the partial unique index would catch the race *before* `@Version` had a chance to fire — masking the optimistic-lock design. `saveAndFlush` inverts the flush order so `@Version` is the primary catcher, as intended.

### 2. Partial unique index over CHECK constraint

The "one active reservation per seat" rule could be a CHECK constraint or a unique index. I chose a **partial unique index on `seat_id WHERE status IN ('HELD','CONFIRMED')`** because:

- CHECK can't enforce cross-row uniqueness (it's a per-row predicate).
- Partial unique index preserves history: expired and cancelled rows stay in the table and don't compete.
- B-tree lookups for the active-reservation check use the index, so reads are also faster.

### 3. Java-side `@PreUpdate` for `updated_at` (not a DB trigger)

Two common ways to keep `updated_at` fresh on every update: DB trigger or JPA lifecycle callback. I chose `@PreUpdate`:

- Simpler deploy — no trigger install/rollback to manage.
- Works identically across Testcontainers-Postgres and production.
- **Known tradeoff:** doesn't fire on JPQL bulk `UPDATE` statements. Acceptable for Phase 0 — no bulk updates in the code path. If bulk operations are added later (e.g., expiry sweep rewritten as one bulk UPDATE), this moves to a trigger.

### 4. JWT stateless auth, no server-side session

`/api/auth/login` returns a JWT signed with HS256. The auth filter validates signature + expiry on each request and populates `SecurityContext`. No session store.

- Works across horizontal scale without sticky sessions or a session DB.
- Secret sourced from `APP_SECURITY_JWT_SECRET` env var, ≥32 bytes for HS256 key-length requirement.
- Rotation is a Phase 4 concern.

### 5. `ddl-auto=validate` + Flyway owns schema

`spring.jpa.hibernate.ddl-auto=validate` means Hibernate does not mutate the schema — it only verifies that entities and tables match. Flyway V1 migration is the source of truth.

- Catches entity/schema drift at startup — app fails to boot rather than silently emitting malformed SQL.
- Makes schema changes auditable (one migration file per change, version-controlled).
- Validated in CI: startup itself is the smoke test.

### 6. Phase 1 — Redis for coordination, Postgres for system of record

Full rationale in [`docs/adr/0002-redis-for-holds-and-locks.md`](docs/adr/0002-redis-for-holds-and-locks.md). Headline decisions:

- **Two-layer Redis**: `SET NX EX` for the TTL hold (atomic, 10 min business duration) and Redisson `RLock` for the critical-section lock (2 s lease, prevents wasted work on hot events). Both are needed — the SET NX gives correctness, the RLock gives efficiency.
- **Drop the `@Scheduled` sweeper**: Redis-native TTL replaces it. Stale rows are reconciled on-the-fly inside `reserve()` (under the RLock) and lazily inside `myReservations()` (on user read). Self-healing without a polling thread.
- **Per-seat lock granularity**: `lock:seat:{id}`, not per-event. Two reservations for two different seats in the same event don't serialize.
- **Fail-closed on Redis outage** (503 SERVICE_UNAVAILABLE): a fall-back-to-DB-only path would lose cross-JVM serialization and permit double-holds. Better to refuse service. Verified by `RedisOutageIT`.
- **Single-node Redis** (Upstash managed) for Phase 1; Redlock multi-node hardening deferred to Phase 4+ if real load measurements justify it.

### 7. `Persistable<UUID>` + drop `@GeneratedValue` for pre-assigned reservation ids

Phase 1's "Redis-first ordering" needs the reservation id **before** the DB insert (so the id can be the Redis value at `SET NX EX` time). Pre-assigning a UUID to a Hibernate entity with `@GeneratedValue(strategy = UUID)` triggered both Spring Data's "is this new?" heuristic AND Hibernate's transient-vs-detached classifier — two independent layers that needed to agree. The fix:

```java
@Id
@Column(columnDefinition = "uuid", updatable = false, nullable = false)
private UUID id;          // no @GeneratedValue

// + implements Persistable<UUID> with @Transient boolean isNew flag
```

Drops `@GeneratedValue` (so Hibernate doesn't classify pre-set ids as detached) and implements `Persistable` (so Spring Data routes to `persist()`, not `merge()`). Four rounds of failure (StaleObjectStateException → PersistentObjectException → still PersistentObjectException with Persistable alone → finally INSERT works once `@GeneratedValue` is dropped) before the right combination clicked — interview-grade JPA gotcha story.

---

## Testing

### Concurrency invariant — the headline test

[`SeatReservationConcurrencyIT`](src/test/java/com/ethanluong/ticketreservation/SeatReservationConcurrencyIT.java) races 10 threads at the same seat through a `CountDownLatch` start-gate, then asserts:

- Exactly one `Outcome.SUCCESS`
- Zero `Outcome.FAILURE_OTHER` (unexpected errors)
- Single reservation row in DB
- Seat final state: `HELD`, `version == 1`

Categorized outcomes distinguish app-layer losses (`FAILURE_NOT_AVAILABLE`) from DB-layer losses (`FAILURE_OPTIMISTIC_LOCK`, `FAILURE_DATA_INTEGRITY`), proving which layer caught each racing thread. Ran 50× clean — invariant is stable, not flaky.

### Phase 1 Redis tests

| Test class | What it verifies |
|---|---|
| [`RedisTTLHoldIT`](src/test/java/com/ethanluong/ticketreservation/RedisTTLHoldIT.java) | `reserve()` writes `hold:seat:{id}` with TTL ≈ 600s; collision blocks second reserve; `cancel()` releases the key; on-the-fly + lazy reconciliation paths |
| [`RedissonLockContentionIT`](src/test/java/com/ethanluong/ticketreservation/RedissonLockContentionIT.java) | `reserve()` fast-fails with `SeatContentionException` (<500ms) when the `RLock` is held on a different thread; succeeds normally once released |
| [`RedisOutageIT`](src/test/java/com/ethanluong/ticketreservation/RedisOutageIT.java) | `@MockitoBean RedissonClient` injects connection failure; asserts exception bubbles to handler, zero DB drift, 503 ProblemDetail mapping |

### Stack

- JUnit 5 + AssertJ
- Testcontainers with `@ServiceConnection` for both Postgres 17-alpine and Redis 7-alpine
- `@MockitoBean` (Spring Framework 6.2+) for failure injection
- No H2, no mocks for DB-backed behavior — Testcontainers because H2's partial index and `gen_random_uuid()` don't match Postgres semantics

Run the full suite (17 tests across 6 classes):

```bash
./mvnw verify
```

---

## Tech stack

- **Runtime:** Java 21, Spring Boot 4.0.x
- **Data:** PostgreSQL 17-alpine, Flyway migrations, Spring Data JPA (Hibernate)
- **Coordination:** Redis 7-alpine, Spring Data Redis (Lettuce) for TTL holds, Redisson 3.50.0 for distributed locks
- **Security:** Spring Security 6, jjwt 0.12.x
- **Testing:** JUnit 5, AssertJ, Testcontainers, `@MockitoBean` for failure injection
- **Packaging:** Multi-stage Dockerfile (Java 21 JDK builder → JRE-alpine runtime)
- **Deploy:** Railway (managed Postgres + container) + Upstash (managed Redis)
- **Build:** Maven (via `mvnw`)

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| **0. Reservation foundation** | Entities, JWT auth, `@Version` optimistic lock, TTL sweeper, Testcontainers race proof, Railway deploy | ✅ Shipped |
| **1. Redis holds + distributed lock** | Redis-native TTL holds, Redisson `RLock` per seat, sweeper retired, lazy reconciliation, fail-closed (503) on Redis outage | ✅ Shipped |
| **2. Payment + saga** | Extract payment service; Kafka between services; outbox; saga orchestration with timeout compensation | Planned |
| **3. Frontend** | Next.js 14 browse + seat-map booking flow | Planned |
| **4. Scale signals** | Rate limiting (Bucket4j), Resilience4j circuit breakers, Grafana dashboards, hot-event caching | Planned |
| **5. Tickets + polish** | Ticket issuance service, QR validation, refund flow, group bookings | Planned |

Each phase ships polished — deployed, tested, documented. At any checkpoint there is an interview-ready artifact.

---

## Env vars

Required at runtime:

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL — must start with `jdbc:postgresql://` |
| `SPRING_DATASOURCE_USERNAME` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `SPRING_DATA_REDIS_URL` | Redis connection — `redis://host:port` for plaintext, `rediss://host:port` for TLS (Upstash). Defaults to `redis://localhost:6379` for local dev. |
| `APP_SECURITY_JWT_SECRET` | HS256 signing key, ≥32 bytes |
| `SERVER_PORT` | On Railway/Fly, bind to `${PORT}` |

Local dev via `docker compose up` supplies all of these with sane defaults. A dev JWT secret is baked into `application.properties` — **never use it in production**.

### Upstash Redis setup (production)

1. Create a free-tier Redis database at [upstash.com](https://upstash.com).
2. Copy the connection URL — it begins with `rediss://` (note the second `s` for TLS).
3. Set on Railway: `SPRING_DATA_REDIS_URL=rediss://<host>:<port>` with the password embedded in the URL (`rediss://default:<password>@<host>:<port>`).
4. Verify in `/actuator/health` — Redis component should report `UP`.
5. Smoke test: POST a reservation, wait > 10 minutes, GET `/api/reservations/me` — the row should now be `EXPIRED` and the seat re-reservable. Validates Redis-native TTL + lazy reconciliation in production.
