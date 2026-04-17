# Event Ticket Reservation System

A multi-service event ticket reservation platform, built as an interview portfolio piece to showcase distributed-systems backend work in Spring Boot.

## Target

Ship a deployed, demoable v1 by **2026-05-25**, aligned with active job applications.

## Tech Stack

- Java 21, Spring Boot 4.0.x, Maven
- Spring Data JPA, Spring Security, Spring Validation, Actuator
- PostgreSQL + Flyway migrations
- Redis + Redisson (Phase 1)
- Kafka (Phase 2)
- JUnit 5 + Testcontainers
- Docker, Fly.io, GitHub Actions, OpenTelemetry + Grafana Cloud

Frontend (Phase 3): Next.js 14, Tailwind.

## Phased Plan

| Phase | Scope | Duration |
|-------|-------|----------|
| 0 | Reservation foundation: entities, JWT auth, JPA `@Version` optimistic locking, TTL reservations, Testcontainers race test, Docker + Fly.io deploy | 2w |
| 1 | Redis holds + distributed locking via Redisson | 1w |
| 2 | Payment + saga orchestration (hold → charge → confirm, with compensation) | 2w |
| 3 | Next.js frontend for browse + reserve + pay flow | 1w |
| 4 | Scale signals: OpenTelemetry, load test, runbook | 1w |
| 5 | Ticket issuance + polish | 0.5w |

Full plan: [`vault/areas/career/projects/ticket-reservation-plan.md`](../../vault/areas/career/projects/ticket-reservation-plan.md) (local).

## Status

**Phase 0 — scaffolded, in progress.**

- [x] Spring Boot 4 skeleton via Initializr
- [ ] Domain entities + Flyway V1 migration
- [ ] JWT auth
- [ ] `@Version` optimistic locking on seat reservations
- [ ] TTL reservation expiry job
- [ ] Testcontainers race-condition test
- [ ] 60%+ unit coverage
- [ ] Docker image + Fly.io deploy

## Local Development

Requires Docker (for Testcontainers Postgres).

```bash
./mvnw spring-boot:test-run    # dev run with Testcontainers Postgres
./mvnw verify                  # tests + package
```

## Interview Talking Points (tracked as built)

- Optimistic locking (DB) vs distributed locking (Redis) — when each is correct
- Saga orchestration with compensation on payment failure
- Idempotent endpoint design for retries
- Observability choices and what gets paged
