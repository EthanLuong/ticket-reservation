# ADR 0011 — Deploying two services as two containers in one ECS task definition

**Status:** Proposed — drafted 2026-09-03 for the author's review (Decision D4 default from REFOCUS-DESIGN §5); becomes Accepted when rewritten in his words and the deploy in R4 Task 3 has run
**Date:** 2026-09-03
**Phase:** R4
**Sibling:** [ADR 0009](0009-payment-db-one-container-two-databases.md), [ADR 0010](0010-elk-structured-logging.md)

## Context

After R2 there are two deployable images. The AWS footprint from July (handbook Phase 6) runs one
ECS Fargate service, `ticket-reservation-app`, with one task definition family and one container,
behind an ALB target group on port 8080. R4 has to run both images on that footprint without
spending on infrastructure the demo doesn't need. The two services talk only over Kafka
(`payment.cmd` / `payment.evt`); payment-service exposes no HTTP that anything outside the task
calls — its `:8081` actuator is for its own healthcheck.

Options considered:

- **(a) Two containers in the one task definition, one service** *(chosen)* — `containerDefinitions`
  becomes `[app, payment]`, both `essential`, one `update-service` rolls both.
- **(b) Two task definitions, two services** — independent deploys and scaling; needs service
  discovery only if the services ever call each other over HTTP (they don't), plus a second
  service, deployment configuration and per-service alarms.
- **(c) Keep the monolith image deployed and call the split a local-only artifact** — rejected
  without discussion: the resume line is "two deployables", and a deploy that doesn't run them is
  a claim, not a proof.

## Decision

**(a): two containers, one task, one service.** The deploy proves what R2 claims — two images,
two processes, two databases, one wire — at zero added infrastructure and zero added cost.

Why (a) is honest and not a shortcut:

- **The seam is Kafka, and Kafka is already outside the task.** Both containers point at the same
  `SPRING_KAFKA_BOOTSTRAP_SERVERS`; neither needs to find the other. Containers in one awsvpc task
  share a network namespace, so `localhost:8081` would work if it were ever needed — it isn't. This
  is exactly why (a) needs no Cloud Map and (b) would.
- **Isolation that matters is preserved.** Separate images, separate JVMs with per-container
  memory limits, separate databases and credentials (`PAYMENT_DB_PASSWORD` from its own SSM
  parameter — [ADR 0009](0009-payment-db-one-container-two-databases.md)'s prod echo), separate
  CloudWatch stream prefixes (`app`, `payment`). What (a) gives up is deployment independence,
  which is the next paragraph.
- **The pipeline is already per-service.** The GitHub Actions matrix builds and pushes one image
  per service to its own ECR repository; the deploy step swaps both images into one revision.
  Moving to (b) later changes the deploy job, not the build.

What (a) costs, stated plainly: a payment-only change still restarts reservation-service; both
containers are `essential`, so either dying replaces the whole task; they scale together. None of
that hurts at two Fargate tasks serving a demo. **The trigger to move to (b)** is the first time
payment needs to scale or deploy on its own schedule — at that point the seam is already clean
enough that (b) is a second task-definition file and a second `create-service`, not a redesign.

Mechanics on record: task memory 512 CPU / 2048 MiB with per-container `memory` (app 1280,
payment 768) because `-XX:MaxRAMPercentage=75` reads the container limit, and without one both
JVMs size against the task total and over-commit. The `payments` database and `payment_user` on
RDS are created once by hand (`scripts/initdb/rds-payments.sql`) since the compose initdb hook
only runs on empty volumes.

## Consequences

- One `docker compose` file locally and one task definition in prod describe the same two
  processes; the README's architecture diagram is literally true in both places.
- A payment-service hotfix rolls reservation-service too. Acceptable now; the documented trigger
  above says when it stops being acceptable.
- Logs: one CloudWatch group, two stream prefixes; `filter sagaId = "…"` across the group is the
  prod version of the Kibana saved search ([ADR 0010](0010-elk-structured-logging.md)).
- ELK does not deploy to AWS (cost); CloudWatch Logs is production's answer, deliberately.
- Cost unchanged at ~$95/month while up; the footprint is torn down between demos per the ratified
  cadence, with this task definition and the RDS script committed so redeploy is a checklist.
