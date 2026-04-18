# Event Ticket Reservation System

A Spring Boot event ticketing service handling concurrent seat reservations with a three-layer correctness defense: app-layer fast-fail, JPA optimistic locking via `@Version`, and a DB-level partial unique index as a backstop. Built as an interview portfolio piece.

**Live demo:** [https://ticket-reservation-production-7193.up.railway.app](https://ticket-reservation-production-7193.up.railway.app)
**Repo:** [github.com/EthanLuong/ticket-reservation](https://github.com/EthanLuong/ticket-reservation)

---

## Status

**Phase 0 (reservation foundation) — SHIPPED.** Single-service reservation system with JWT auth, optimistic-locked seat holds, TTL expiry, and Testcontainers-proven race invariants. Deployed on Railway with managed Postgres.

Phased roadmap (Phase 1–5) below.

---

## Quick start

### Option A — Docker Compose (prod-parity local)

```bash
docker compose up --build
```

Spins up Postgres 17-alpine + the service, gated on a Postgres healthcheck. App listens on `http://localhost:8080`. Health probe:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Option B — Maven dev loop (fastest)

Requires Docker Desktop running (for the Testcontainers-backed dev Postgres).

```bash
./mvnw spring-boot:test-run    # dev run, auto-provisions Testcontainers Postgres
./mvnw verify                  # full test suite + package
```

The `test-run` goal auto-wires a disposable Postgres container via `@ServiceConnection`, so no manual DB setup is needed.

---

## Architecture

Single service, single database. Phase 0 intentionally avoids service splitting — concurrency correctness is the interesting problem at this stage; distributed coordination comes in Phase 1+.

```
                    ┌────────────────────────────┐
                    │  Client (curl / Postman)   │
                    └──────────────┬─────────────┘
                                   │ HTTPS + Bearer JWT
                                   ▼
     ┌─────────────────────────────────────────────────────────┐
     │                   Spring Boot App                       │
     │                                                         │
     │  ┌──────────────┐   ┌──────────────┐   ┌────────────┐   │
     │  │  Controllers │──▶│   Services   │──▶│  Repos     │   │
     │  │  (REST)      │   │  (@Trans-    │   │  (JPA)     │   │
     │  │              │   │   actional)  │   │            │   │
     │  └──────────────┘   └──────┬───────┘   └─────┬──────┘   │
     │                            │                 │          │
     │  ┌──────────────────┐      │                 │          │
     │  │  Security Filter │      │                 │          │
     │  │  Chain (JWT)     │      │                 │          │
     │  └──────────────────┘      │                 │          │
     │                            │                 │          │
     │  ┌──────────────────┐      │                 │          │
     │  │  @Scheduled TTL  │──────┘                 │          │
     │  │  Sweeper (5s)    │                        │          │
     │  └──────────────────┘                        │          │
     └──────────────────────────────────────────────┼──────────┘
                                                    │
                                                    ▼
                           ┌──────────────────────────────┐
                           │   PostgreSQL 17              │
                           │   - Flyway migrations        │
                           │   - Partial unique index     │
                           │     backstop                 │
                           │   - @Version optimistic col  │
                           └──────────────────────────────┘
```

### Domain

```
Event  1───N  Seat  1───N  Reservation  N───1  User
                                status ∈ {HELD, CONFIRMED, EXPIRED, CANCELLED}
```

Full schema: [`src/main/resources/db/migration/V1__init.sql`](src/main/resources/db/migration/V1__init.sql).

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

Error responses follow [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) (`application/problem+json`). Example for a double-book attempt:

```json
{
  "type": "https://ticket-reservation.example/errors/optimistic-lock",
  "title": "Concurrent modification",
  "status": 409,
  "detail": "Another request modified this seat first. Retry with fresh state."
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

---

## Testing

### Concurrency invariant — the headline test

[`SeatReservationConcurrencyIT`](src/test/java/com/ethanluong/ticketreservation/SeatReservationConcurrencyIT.java) races 10 threads at the same seat through a `CountDownLatch` start-gate, then asserts:

- Exactly one `Outcome.SUCCESS`
- Zero `Outcome.FAILURE_OTHER` (unexpected errors)
- Single reservation row in DB
- Seat final state: `HELD`, `version == 1`

Categorized outcomes distinguish app-layer losses (`FAILURE_NOT_AVAILABLE`) from DB-layer losses (`FAILURE_OPTIMISTIC_LOCK`, `FAILURE_DATA_INTEGRITY`), proving which layer caught each racing thread. Ran 50× clean — invariant is stable, not flaky.

### TTL expiry — the scheduler test

[`ReservationSweeperIT`](src/test/java/com/ethanluong/ticketreservation/ReservationSweeperIT.java) covers:
- Expired `HELD` → `EXPIRED`, seat flips back to `AVAILABLE`
- Future `HELD` untouched
- `CONFIRMED` never swept
- Mixed batch — only the expired ones move

### Stack

- JUnit 5 + AssertJ
- Testcontainers with `@ServiceConnection` (real Postgres 17-alpine per test class)
- No H2, no mocks for DB-backed behavior — Testcontainers because H2's partial index and `gen_random_uuid()` don't match Postgres semantics

Run the full suite:

```bash
./mvnw verify
```

---

## Tech stack

- **Runtime:** Java 21, Spring Boot 4.0.x
- **Data:** PostgreSQL 17-alpine, Flyway migrations, Spring Data JPA (Hibernate)
- **Security:** Spring Security 6, jjwt 0.12.x
- **Testing:** JUnit 5, AssertJ, Testcontainers
- **Packaging:** Multi-stage Dockerfile (Java 21 JDK builder → JRE-alpine runtime)
- **Deploy:** Railway (managed Postgres + container)
- **Build:** Maven (via `mvnw`)

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| **0. Reservation foundation** | Entities, JWT auth, `@Version` optimistic lock, TTL sweeper, Testcontainers race proof, Railway deploy | ✅ Shipped |
| **1. Redis holds + distributed lock** | Swap DB TTL for Redis-native TTL; Redisson lock for hot events | Planned |
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
| `APP_SECURITY_JWT_SECRET` | HS256 signing key, ≥32 bytes |
| `SERVER_PORT` | On Railway/Fly, bind to `${PORT}` |

Local dev via `docker compose up` supplies all of these with sane defaults. A dev JWT secret is baked into `application.properties` — **never use it in production**.
