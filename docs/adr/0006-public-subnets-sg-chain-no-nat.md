# ADR 0006 — Network: public subnets + security-group chain, no NAT Gateway

**Status:** Accepted (ratified by Ethan 2026-07-28 with P1/P2; drafted by Claude same day)
**Date:** 2026-07-28
**Phase:** M4 (AWS deployment), decision D3 in `docs/aws/decisions.md`

## Context

The textbook VPC shape puts compute in **private subnets**: no public IPs, all egress through a NAT
Gateway, ingress only via a public-subnet load balancer. But Fargate tasks must pull their image
from ECR and write logs to CloudWatch — from a private subnet that requires either:

- a **NAT Gateway**: ~$0.045/hr ≈ **$32/mo** + $0.045/GB processed, or
- **VPC interface endpoints** for ECR (×2), CloudWatch Logs, and S3: ~$7/endpoint/mo ≈ **$22–29/mo**.

Either roughly doubles this deployment's fixed network cost to protect services that have exactly
one operator and no secrets beyond what IAM already guards.

## Decision

**Everything lives in public subnets; isolation comes from the security-group chain, not subnet
privacy.** Tasks get public IPs (that's how they reach ECR); nothing accepts traffic on them:

```
CloudFront (managed prefix list) → alb-sg :80 → app-sg :8080 → {kafka-sg :9092, db-sg :5432, redis-sg :6379}
```

Every SG source is another SG (or CloudFront's `origin-facing` managed prefix list) — no `0.0.0.0/0`
ingress anywhere. RDS additionally keeps `publicly accessible = No` (its public-subnet placement
grants nothing without it). A public IP with a closed SG is unreachable; the SG chain *is* the
network policy, and it's readable straight off the matrix in the handbook.

Reasoning:

1. **Threat model honesty.** Private subnets defend against SG misconfiguration and lateral
   movement — real concerns for teams, over-engineering for a solo demo where IAM + SG sources
   are reviewed in one sitting. The $32/mo NAT buys defense-in-depth this system doesn't need yet.
2. **Cost proportionality.** NAT alone would be the second-largest line item, ~40% of the whole
   stack's cost, spent on moving `docker pull` traffic.
3. **The interview answer is the tradeoff, not the topology.** "I put tasks in public subnets with
   SG-chained sources and documented why — in prod I'd use private subnets with VPC endpoints for
   ECR/Logs and a NAT for the rest, because then misconfiguration blast-radius matters" shows more
   judgment than silently paying for the textbook shape.

## Consequences

- A future SG mistake (e.g., someone opens 8080 to the world) is exposure, not a non-event. Mitigation:
  the matrix in the handbook is the review checklist; five groups is auditable at a glance.
- Task public IPs rotate per deployment — nothing may ever reference them (everything goes through
  the ALB / service discovery by design).
- The Kafka EC2 also sits public-subnet for the same pull-images reason, with 9092 SG-chained and
  shell access via SSM only (no SSH ingress at all).

## Revisit triggers

- Any second operator or real user data → move to private subnets + VPC endpoints (ECR ×2,
  CloudWatch Logs, S3 gateway endpoint at $0) + NAT for residual egress.
- AWS pricing changes (NAT-free egress paths, cheaper endpoints).
- A compliance requirement naming network segmentation.
