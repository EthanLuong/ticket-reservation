# Ticket-Reservation — Phases 2–5 Design Document

> **Audience:** the builder (Ethan, future-you) and any interviewer reading the repo. Every decision below should be defensible without notes.
>
> **Status of phases:**
> - Phase 0 (reservation foundation) — ✅ Shipped
> - Phase 1 (Redis holds + distributed lock) — ✅ Shipped
> - Phase 2 (payment + saga) — Designed, not yet built
> - Phase 3 (frontend) — Designed, not yet built
> - Phase 4 (scale signals) — Designed, not yet built
> - Phase 5 (tickets + polish) — Designed, not yet built
>
> **Created:** 2026-04-28
> **Source of truth for scope:** [`README.md`](../README.md) — Roadmap section.

---

## 1. Target End-State (After Phase 5)

This is what the system looks like after all four planned phases ship. Every phase below is a delta toward this picture.

```
                ┌──────────────────────────────────┐
                │  Browser (Next.js 14 SPA)        │
                │  - Event list                    │
                │  - Seat map (SVG)                │
                │  - My reservations               │
                │  - Ticket QR display             │
                └──────────────┬───────────────────┘
                               │ HTTPS / JWT
                               ▼
              ┌────────────────────────────────────┐
              │  API Gateway (Spring Cloud         │
              │  Gateway) — Phase 4                │
              │  - Rate limit (Bucket4j)           │
              │  - JWT validation                  │
              │  - CORS                            │
              └─┬─────────────────────────────────┬┘
                │                                 │
       ┌────────▼────────┐              ┌────────▼────────┐
       │ reservation-    │              │ payment-        │
       │ service         │◄────Kafka────│ service         │
       │ (saga           │              │ (worker)        │
       │  orchestrator)  │              │                 │
       └─┬───────────┬───┘              └───┬─────────────┘
         │           │                      │
         │           │                      ├──► Stripe
         │           │                      │    (mock for portfolio)
         ▼           ▼                      ▼
       Postgres    Redis                  Postgres
       (system    (coord.)                (payments)
        of record)                        — Phase 2b
                                          (or shared in Phase 2a)
                                  ┌────────────────────┐
                                  │  ticket-service    │
                                  │  (Phase 5)         │
                                  │  - QR issuance     │
                                  │  - Validation      │
                                  └────────────────────┘

         All services emit metrics → Prometheus → Grafana (Phase 4)
         All services emit traces → OTLP collector → Tempo (Phase 4 stretch)
```

The diagram is aspirational — Phase 2a (the recommended scoping) keeps payment in the same JAR. Phase 2b is the actual extraction.

---

## 2. Cross-Phase Concerns

Three decisions made up-front that affect every phase:

### 2.1 Service vs. Package Discipline

**Decision:** start with package boundaries, extract only when the contract is stable.

A "service" in Phase 2 can mean two things:
- **Phase 2a:** payment is a new top-level package (`com.ethanluong.ticketreservation.payment.*`) inside the same JAR. Communicates with reservation via Kafka *as if* it were external.
- **Phase 2b:** payment is split into a separate Spring Boot app, separate database, separate deploy.

Recommendation: **ship Phase 2a first.** It buys you the entire saga/Kafka/outbox learning surface without the dev-environment tax of a second deployment target. Phase 2b is a 1-week split once 2a is solid. Interviewers care about the *pattern*, not the deployment shape.

### 2.2 Event Schema Discipline

Every Kafka event has a stable contract from day one:

```json
{
  "eventId": "uuid (idempotency key, dedupe with this)",
  "eventType": "PaymentConfirmed",
  "eventVersion": 1,
  "occurredAt": "ISO-8601 timestamp",
  "sagaId": "uuid (correlates to a saga state row)",
  "payload": { /* event-specific */ }
}
```

**Why every field matters:**
- `eventId` — the consumer dedupe key. Without it you can't be idempotent.
- `eventVersion` — when the payload shape changes, increment. Consumers either handle both versions or fail loud.
- `occurredAt` — for late-event detection and ordering.
- `sagaId` — the orchestrator's correlation key. The single line that links a flying event back to a row in the `sagas` table.

JSON over Avro/Protobuf for portfolio scope — Avro adds a Schema Registry dependency that's not worth the complexity here. Document the trade-off.

### 2.3 Idempotency Is Non-Negotiable

Every consumer of every event must be idempotent. The mechanism:

```sql
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    consumer  TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Consumer pattern:
```java
@Transactional
public void handle(Event evt) {
    if (processedEvents.exists(evt.eventId(), CONSUMER_NAME)) return;  // dedupe
    doWork(evt);
    processedEvents.insert(evt.eventId(), CONSUMER_NAME);              // mark
}
```

This is **the** load-bearing pattern for Phases 2–5. Memorize it.

---

## 3. Phase 2 — Payment + Saga

### 3.1 Goals

- Add a payment step between "seat held" and "seat sold."
- Coordinate the multi-step flow with a **saga orchestrator** that lives in reservation-service.
- Use Kafka for inter-component (Phase 2a) and inter-service (Phase 2b) messaging.
- Use the **transactional outbox** pattern so DB writes and Kafka publishes are atomic.
- Handle payment timeouts with explicit compensation.

### 3.2 Non-Goals

- Real Stripe integration (use a mock that succeeds/fails/delays based on a test mode).
- Multi-step refund workflows.
- Distributed payment-service across multiple instances (single instance is fine for portfolio).

### 3.3 New Components

| Component | Lives in | Purpose |
|---|---|---|
| `Saga` JPA entity | reservation-service | Persistent state machine row, one per booking flow |
| `SagaOrchestrator` | reservation-service | Drives state transitions in response to events |
| `OutboxEntry` JPA entity | reservation-service AND payment-service | Outbox row for reliable publishing |
| `OutboxPublisher` | both | `@Scheduled` poller that drains outbox to Kafka |
| `ProcessedEvent` JPA entity | both | Idempotency dedup table |
| `PaymentService` | payment package (2a) / payment-service (2b) | Handles `ChargeCard`, `RefundCharge` commands |
| `MockPaymentGateway` | payment package | Stand-in for Stripe; configurable success/failure/delay |
| Saga timeout `@Scheduled` | reservation-service | Sweeps `sagas WHERE state=AWAITING_PAYMENT AND created_at < now()-30s` |

### 3.4 Saga State Machine

States:
```
PENDING ─► AWAITING_PAYMENT ─► COMPLETED
                │
                ├─► PAYMENT_FAILED ─► COMPENSATING ─► CANCELLED
                │
                └─► TIMED_OUT ─► COMPENSATING ─► CANCELLED
```

Transition table:

| From | Event | To | Side effects |
|---|---|---|---|
| (start) | reservation request | PENDING | Insert Reservation row, insert Saga row, insert outbox `ChargeCard` cmd |
| PENDING | (immediate, same tx) | AWAITING_PAYMENT | (none — set in same tx as PENDING) |
| AWAITING_PAYMENT | `PaymentConfirmed` | COMPLETED | Reservation.status = CONFIRMED |
| AWAITING_PAYMENT | `PaymentFailed` | PAYMENT_FAILED | (none yet) |
| AWAITING_PAYMENT | timer (30s) | TIMED_OUT | Insert outbox `CancelChargeIfStarted` cmd |
| PAYMENT_FAILED | (immediate, same tx) | COMPENSATING | Release seat hold, Reservation.status = CANCELLED |
| TIMED_OUT | (immediate, same tx) | COMPENSATING | Release seat hold, Reservation.status = CANCELLED |
| COMPENSATING | `RefundConfirmed` | CANCELLED | (terminal) |
| COMPENSATING | (no refund needed) | CANCELLED | (terminal) |

Terminal states: COMPLETED, CANCELLED. Saga rows are kept indefinitely for audit.

### 3.5 Data Model Changes

New tables (Flyway `V2__phase_2_saga.sql`):

```sql
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
```

For Phase 2b (split): `payments` table moves to payment-service's own DB:

```sql
CREATE TABLE payments (
    id           UUID PRIMARY KEY,
    saga_id      UUID NOT NULL,                -- correlation back to reservation
    amount_cents BIGINT NOT NULL,
    status       TEXT NOT NULL,                -- PENDING | CONFIRMED | FAILED | REFUNDED
    gateway_ref  TEXT,                         -- Stripe charge id or mock id
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.6 Kafka Topics & Contracts

| Topic | Producer | Consumer | Purpose |
|---|---|---|---|
| `payment.cmd` | reservation-service | payment-service | Commands: `ChargeCard`, `CancelChargeIfStarted` |
| `payment.evt` | payment-service | reservation-service | Replies: `PaymentConfirmed`, `PaymentFailed`, `RefundConfirmed` |
| `payment.cmd.DLT` | (Kafka error handler) | manual triage | Poison-pill commands |
| `payment.evt.DLT` | (Kafka error handler) | manual triage | Poison-pill events |

Partitioning:
- `payment.cmd` partitioned by `sagaId` — guarantees commands for the same saga land on the same partition, processed in order.
- `payment.evt` partitioned by `sagaId` — same reasoning.
- 3 partitions per topic for portfolio scope; can increase later.

Producer config:
- `acks=all` (durability > latency for payments)
- `enable.idempotence=true` (no duplicate messages from one producer session)
- `compression.type=lz4` (cheap CPU, meaningful network savings)

Consumer config:
- One consumer group per service (`reservation-service`, `payment-service`)
- `enable.auto.commit=false` (manual ack after `processed_events` insert succeeds)
- `isolation.level=read_committed` (if producer transactions are used; not required for outbox-only)

### 3.7 Failure Modes

| Failure | What happens | Recovery |
|---|---|---|
| reservation-service crashes after DB commit, before outbox publish | Outbox row stays unprocessed; DB has saga in AWAITING_PAYMENT | Outbox publisher restarts, picks up row, publishes |
| payment-service crashes mid-charge | Stripe may or may not have charged; idempotency key on Stripe API prevents double-charge on retry | DLT after retries; manual reconciliation |
| `PaymentConfirmed` event lost (network) | Saga stuck in AWAITING_PAYMENT until timeout | Timer fires at 30s, transitions to TIMED_OUT, sends `CancelChargeIfStarted` |
| Duplicate `PaymentConfirmed` delivered | First copy advances saga; second copy hits dedupe in `processed_events` | No-op |
| Stripe charge succeeded but reservation-service already timed out | `CancelChargeIfStarted` sent; payment-service refunds | Eventually consistent; user sees CANCELLED for ~30s before refund |
| User retries POST /reservations during AWAITING_PAYMENT | Reservation already exists; return 409 with existing reservation id | Idempotent at the API layer (use `Idempotency-Key` header) |

### 3.8 Testing Strategy

Integration tests with Testcontainers Kafka + Postgres:

1. **Happy path** — POST → assert saga reaches COMPLETED, reservation CONFIRMED, payment CONFIRMED.
2. **Payment fails** — mock gateway returns failure → assert saga reaches CANCELLED, reservation CANCELLED, seat AVAILABLE.
3. **Payment times out** — mock gateway delays past 30s → assert TIMED_OUT path.
4. **Duplicate event** — replay a `PaymentConfirmed` → assert no double-confirm.
5. **Crash mid-saga** — abort the JVM after DB commit, restart → assert outbox publisher republishes.
6. **Out-of-order events** — `PaymentConfirmed` arrives before saga is in AWAITING_PAYMENT → assert event is parked or rejected (decide policy).

The first three are mandatory; the rest are senior-level signals.

### 3.9 Deferred to Later Phases

- Real Stripe (use mock).
- Webhook-driven payment confirmation (event-driven only).
- Cross-region payment failover.
- Saga visualization UI (just query the DB).

### 3.10 Open Questions for Phase 2

These are decisions to make before coding, not after:

1. **Idempotency key on POST /reservations** — header or derived? (Recommendation: client-supplied `Idempotency-Key` header; reject duplicates with 409 + cached response body.)
2. **What's the timeout duration?** README says "timeout compensation" but not the value. (Recommendation: 30 seconds; document the assumption.)
3. **Phase 2a or 2b first?** (Recommendation: 2a, then 2b as a clean follow-up.)

---

## 4. Phase 3 — Frontend

### 4.1 Goals

- Build a Next.js 14 (App Router) SPA that consumes the existing API.
- Demonstrate seat-map UX, real-time seat status, and the full booking flow.
- Polish enough to be screenshot-able for the resume.

### 4.2 Non-Goals

- Mobile-native app.
- Server-side rendering of authenticated pages (CSR is fine for the booking flow).
- Internationalization, accessibility audits beyond basic semantics.

### 4.3 New Components

```
frontend/                          (new sibling to ticket-reservation/ ?
                                    or subdir? — see §4.7)
├── app/
│   ├── login/page.tsx
│   ├── register/page.tsx
│   ├── events/page.tsx            (list)
│   ├── events/[id]/page.tsx       (seat map + booking)
│   ├── reservations/page.tsx      (my reservations)
│   └── tickets/[id]/page.tsx      (Phase 5)
├── lib/
│   ├── api.ts                     (typed fetch wrapper)
│   ├── auth.ts                    (JWT storage + refresh)
│   └── types.ts                   (mirror of API DTOs)
├── components/
│   ├── SeatMap.tsx                (SVG seat map)
│   ├── ReservationTimer.tsx       (10-min countdown)
│   └── ...
└── package.json
```

### 4.4 Auth Decision

**Decision:** JWT in `localStorage` for portfolio scope. Document the trade-off.

Trade-off:
- ✅ Simpler — no CSRF, no cookie config, works across subdomains.
- ❌ Vulnerable to XSS — any `<script>` injection reads the token.
- Production answer would be: HTTP-only secure cookie + CSRF token + short-lived access token + refresh token rotation. That's Phase 4-or-later territory if you want to add it.

### 4.5 Real-Time Seat Status

Polling at 5-second intervals on the seat-map page. Why polling over WebSocket:
- Simpler — no new endpoint, no connection management.
- Acceptable for portfolio — typical event has tens to hundreds of seats, refresh latency of 5s is fine.
- WebSocket pushes are a Phase 4 stretch goal if you want a "wow" demo.

### 4.6 Backend Changes Required

- CORS config: allow the frontend's origin (`localhost:3000` dev, `*.vercel.app` prod).
- Possibly a `GET /api/events/{id}/seats?since=<timestamp>` for delta polling — optional optimization.

### 4.7 Repo Structure Decision

Two options:
- **Same repo (monorepo):** `ticket-reservation/{backend, frontend}`. Easier for resume linking — one URL.
- **Separate repo:** `ticket-reservation-frontend`. Cleaner build pipelines but two URLs to share.

Recommendation: **monorepo**. Move existing code to `backend/`, add `frontend/` sibling. Single pinned URL on the resume.

### 4.8 Deferred

- SSR / SEO optimization.
- Admin/event-creation UI (DB-only for now).
- Real WebSocket-based seat updates.

---

## 5. Phase 4 — Scale Signals

### 5.1 Goals

- Demonstrate awareness of production concerns: rate limiting, circuit breakers, hot-event caching, observability.
- Add Grafana dashboards that visualize meaningful business + technical metrics.
- Prove correctness still holds under horizontal scale (3+ JVM instances).

### 5.2 Components

| Concern | Library | Where |
|---|---|---|
| Rate limiting | Bucket4j | Spring Cloud Gateway filter (or `OncePerRequestFilter` if no gateway yet) |
| Circuit breaker | Resilience4j | Around the Kafka publish to `payment.cmd` and around any external HTTP (Stripe, etc.) |
| Caching | Spring Cache + Caffeine (or Redis) | `EventService.findById`, `SeatService.findByEventId` |
| Metrics | Micrometer + Prometheus | Already wired by Spring Actuator; add custom timers |
| Dashboards | Grafana | One per service, key metrics: req/s, p99 latency, saga state distribution, outbox lag |
| Tracing (stretch) | OpenTelemetry | Spring Boot starter; export to Tempo or Jaeger |

### 5.3 Rate Limiting Design

Two layers:
- **Per-user, per-endpoint:** 60 reservation requests/min per JWT subject. Bucket4j token bucket, key by `principal.id + endpoint`.
- **Per-IP, global:** 1000 req/min per IP across all endpoints. Defense against unauthenticated bursts.

Storage: in-memory for portfolio (acceptable across one JVM); Redis-backed for multi-JVM is a one-line config swap with Bucket4j-Redis integration.

### 5.4 Circuit Breaker Design

On the Kafka publish path (Phase 2 outbox publisher):
- **Failure rate threshold:** 50% over 10 calls
- **Wait duration in OPEN state:** 30s
- **Half-open allowed calls:** 3
- **Fallback:** outbox row stays unpublished; publisher retries on next sweep

On any external HTTP call (Stripe, etc.):
- Same shape, fallback = mark payment as `PENDING_RETRY`, surface to ops dashboard.

### 5.5 Hot-Event Caching

Problem: a popular event (think Taylor Swift on-sale) gets thousands of `GET /api/seats?eventId=X` requests/second. Each one is a Postgres query.

Solution: cache `seatService.findByEventId(eventId)` with a short TTL (1s). Why 1s:
- Seats change rarely on the read path (only when reservations happen).
- 1s is invisible to humans but absorbs the spike.
- Cache eviction on reservation create/cancel keeps it fresh.

```java
@Cacheable(value = "seats-by-event", key = "#eventId")
public List<SeatResponse> findByEventId(UUID eventId) { ... }

@CacheEvict(value = "seats-by-event", key = "#seat.eventId")
public void onReservationChange(Seat seat) { ... }
```

(This is a SpEL learning callback — see [`vault/learning/spring-boot-roadmap/02-spring-core/spel.md`](../../../vault/learning/spring-boot-roadmap/02-spring-core/spel.md).)

### 5.6 Multi-Instance Correctness Proof

Run 3 reservation-service instances behind a load balancer. Run a contention test that fires 100 concurrent reservation requests for the same seat across all 3 instances. Assert exactly 1 succeeds, 99 fail.

This is the test that validates Phase 1's Redisson lock + reconciliation actually holds across JVMs. **Should be part of CI.**

### 5.7 Deferred

- Auto-scaling (Railway doesn't expose this cleanly anyway).
- Multi-region deployment.
- DDoS mitigation beyond rate limiting (Cloudflare / Railway-provided).

---

## 6. Phase 5 — Tickets + Polish

### 6.1 Goals

- Once payment confirms, issue a ticket (QR code) the user can present at the venue.
- Validate tickets server-side (anti-counterfeiting).
- Refund flow for cancellations after payment.
- Group bookings (multi-seat in one transaction).

### 6.2 New Components

| Component | Where |
|---|---|
| `Ticket` JPA entity | ticket package or ticket-service |
| `TicketIssuer` | reacts to `PaymentConfirmed`, generates QR |
| QR generator | use `com.google.zxing` or similar |
| `TicketValidationController` | `POST /api/tickets/{id}/validate` — checks signature, returns status |
| Refund flow | extend saga with `CANCELLATION_REQUESTED → REFUNDING → REFUNDED` states |
| Group booking | extend `POST /api/reservations` to accept `seatIds: UUID[]` (atomic in DB) |

### 6.3 Ticket Design

A ticket is:
- A row in `tickets` (id, reservation_id, issued_at, used_at, signature)
- A QR code containing `{ticketId, signature}` where `signature = HMAC_SHA256(secret, ticketId)`
- Validated by recomputing the HMAC server-side

Why HMAC and not "just the ticket id":
- Anti-counterfeiting — without the secret, you can't forge a valid QR.
- The QR itself is sufficient (no DB lookup needed for signature check, only for `used_at` check).
- Stateless verification — same as JWT philosophy.

### 6.4 Refund Flow

Extends the saga from Phase 2:

```
COMPLETED ─► CANCELLATION_REQUESTED ─► REFUNDING ─► REFUNDED
                                          │
                                          └─► REFUND_FAILED (terminal, manual triage)
```

Triggered by `DELETE /api/reservations/{id}` when the reservation is already CONFIRMED. Saga sends `RefundCharge` command; payment-service refunds via Stripe; emits `RefundConfirmed`; saga transitions; reservation marked CANCELLED; seat returns to AVAILABLE.

### 6.5 Group Bookings — The Hard Part

Reserving N seats atomically requires either:
- **Acquire all N Redisson locks in a deterministic order** (sort seat IDs to prevent deadlock), then run the reservation logic, then release in reverse order.
- **Or use a single coarser-grained lock** (e.g., per event), trading concurrency for simplicity.

Recommendation: per-seat locks in sorted order. Document the deadlock-avoidance reasoning. This is interview gold.

### 6.6 Deferred

- Ticket transfer between users.
- Refund partial (only some seats in a group).
- Re-issue lost tickets.

---

## 7. Decision Log

| # | Decision | Date | Why | Alternatives rejected |
|---|---|---|---|---|
| 1 | Saga orchestration, not choreography | 2026-04-28 | Timeout compensation needs a single timer owner; reservation entity is the workflow's natural identity | Choreography — no good place for the timer |
| 2 | Saga lives in reservation-service | 2026-04-28 | Same DB transaction can update Reservation + Saga + Outbox atomically | Separate orchestrator service — overkill |
| 3 | Outbox pattern for Kafka publishes | 2026-04-28 | Atomic with DB write; survives crashes | Direct kafkaTemplate.send — dual-write hazard |
| 4 | JSON event format, no Schema Registry | 2026-04-28 | Avro + Registry adds infra without portfolio benefit | Avro + Confluent Schema Registry |
| 5 | Phase 2a (package) before Phase 2b (split) | 2026-04-28 | Buys all the learning surface without dev-env tax | Skip 2a, go straight to 2b — slower iteration |
| 6 | localStorage JWT in frontend | 2026-04-28 | Simpler portfolio scope; trade-off documented | HTTP-only cookie + CSRF — more correct but more work |
| 7 | Polling, not WebSocket, for seat status | 2026-04-28 | 5s lag acceptable for demo | WebSocket — Phase 4 stretch |
| 8 | Mock Stripe gateway | 2026-04-28 | Real Stripe wallet/keys are friction; the *pattern* is what's interview-relevant | Real Stripe — Phase 5 polish if time permits |
| 9 | Per-seat locks in sorted order for group bookings | 2026-04-28 | Avoids deadlock, preserves per-seat concurrency | Per-event lock — kills concurrency |

---

## 8. Glossary

- **Saga** — A long-running business workflow modeled as a state machine, with explicit forward steps and compensating actions.
- **Orchestration** — Saga pattern where one service drives the workflow.
- **Choreography** — Saga pattern where services react to each other's events with no central coordinator.
- **Outbox pattern** — Solving the dual-write problem by writing the "intent to publish" to a DB table inside the same transaction as the business write.
- **Compensating transaction** — The undo for a forward step in a saga (e.g., `Refund` compensates `Charge`).
- **Idempotent consumer** — A consumer that produces the same result whether an event is delivered once or many times.
- **Dead letter topic (DLT)** — A topic where messages that exhausted retries are parked for manual triage.
- **Dual-write problem** — See [`vault/wiki/system-design/dual-write-problem.md`](../../../vault/wiki/system-design/dual-write-problem.md). Two systems, no shared transaction.

---

## 9. References

- [Phase 2 prep study plan](../../../vault/learning/ticket-reservation-phase-2-prep.md)
- [Dual-write problem wiki article](../../../vault/wiki/system-design/dual-write-problem.md)
- [SpEL teaching article](../../../vault/learning/spring-boot-roadmap/02-spring-core/spel.md)
- [Kafka roadmap](../../../vault/learning/kafka-roadmap/_index.md)
- [Microservices roadmap](../../../vault/learning/spring-boot-roadmap/08-microservices/_index.md)
- README — [`README.md`](../README.md)

---

## 10. How to Use This Document

1. **Before starting a phase:** re-read that phase's section (Goals, Non-Goals, Open Questions).
2. **Before writing code in a phase:** make sure every Open Question has a documented answer.
3. **During the phase:** update the Decision Log when you make new decisions, or change existing ones.
4. **At the end of the phase:** update the Status (top of doc) and add a "What changed from design" subsection if you deviated.
5. **For interviews:** the Decision Log + Glossary are your prep material. The diagrams are your whiteboard practice.
