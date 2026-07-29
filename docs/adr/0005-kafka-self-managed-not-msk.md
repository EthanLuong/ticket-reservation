# ADR 0005 — Kafka hosting: self-managed single broker on EC2, not Amazon MSK

**Status:** Accepted (ratified by Ethan 2026-07-28 — "q3 ratify p1"; drafted by Claude same day)
**Date:** 2026-07-28
**Phase:** M4 (AWS deployment), decision P1 in `docs/aws/decisions.md`

## Context

Phase 2a made Kafka load-bearing: the payment saga runs over `payment.cmd`/`payment.evt` through a
transactional outbox, and the deployed system needs a broker. AWS offers three ways to have one:

1. **Amazon MSK Provisioned** — managed Kafka on broker instances you size.
2. **Amazon MSK Serverless** — managed Kafka billed per cluster-hour + partition-hour.
3. **Self-managed** — run the same `apache/kafka:4.0.0` container from docker-compose on an EC2 instance.

The numbers (us-east-2, from the MSK pricing page, 2026-07):

| Option | Math | ~$/month |
|---|---|---|
| MSK Serverless | $0.75/cluster-hr × 730h (+ $0.0015/partition-hr, storage $0.10/GB-mo) | **~$550+** |
| MSK Provisioned, 2 × kafka.m7g.large | 2 × $0.204/hr × 730h + storage | **~$300+** |
| MSK Provisioned, legacy small brokers (t3.small-class, no longer featured on the pricing page) | 2 × ~$0.05/hr × 730h | **~$70+** |
| **Self-managed: 1 × t4g.small EC2 + 20 GB gp3** | $0.0168/hr × 730h + ~$1.60 | **~$14** |

This is a portfolio system: one producer-set, two consumer groups, 4 topics × 3 partitions,
traffic measured in events per *demo*, torn down between sessions (decision P2).

## Decision

**Self-managed: one t4g.small EC2 running the docker-compose broker config via user-data**
(`docs/aws/artifacts/kafka-ec2-user-data.sh`), data on the instance's EBS volume, reachable only
from the app security group on 9092. `ADVERTISED_LISTENERS` is rewritten to the instance's private
IP — the same dual-listener discipline the compose file already established.

Reasoning, in the order that decides it:

1. **Cost is not close.** The cheapest realistic MSK footprint costs 5–20× the entire rest of this
   deployment's compute. Managed Kafka's value — patching, broker replacement, rebalancing,
   multi-AZ failover — is priced for production traffic and on-call SLAs this system doesn't have.
2. **Durability needs are already covered one layer up.** The transactional outbox and
   `processed_events`/`payment_processed_events` dedup tables live in Postgres (RDS). If the broker
   dies with its data, unpublished outbox rows republish and consumers dedup — the at-least-once
   contract the E2E suite (`SagaE2EIT`) proves. Broker EBS loss is an inconvenience, not data loss.
3. **Ops exposure is the point.** Running KRaft by hand (listeners, storage, health) is exactly the
   operational understanding interviews probe; MSK would hide it.
4. **The exception that would flip this:** real multi-team traffic, compliance/SLA requirements, or
   anyone besides the author depending on the cluster — i.e., production. **In prod I'd take MSK**
   and say so: paying ~$300/mo to not be the Kafka on-call is the correct trade the moment the
   cluster matters.

Fargate + EFS was considered as a middle road (broker as an ECS service, data on EFS) and rejected:
EFS's latency profile is a known poor fit for Kafka's fsync-heavy log writes, it costs more than the
EC2, and it trades a well-understood single VM for a less-understood storage coupling.

## Consequences

- Broker termination loses in-flight topic data; acceptable per point 2, and topics auto-recreate via
  `KafkaTopicConfig` on next app boot. Teardown script terminates the instance by its `kafka-broker` tag.
- Single broker = single AZ; an AZ outage takes the saga loop down. Accepted for a demo system;
  noted as the first thing MSK would fix in prod.
- The instance is patched only when relaunched. Acceptable for a torn-down-between-sessions box.

## Revisit triggers

- The system gains real users or a second dependent service.
- AWS free-tier/pricing changes make small MSK brokers competitive with the EC2.
- The teardown cadence (P2) ends and the broker becomes always-on infrastructure.
