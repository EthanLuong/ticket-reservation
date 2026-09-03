# ADR 0010 — Centralized logging: structured at the source, ELK behind a profile

**Status:** Proposed — drafted 2026-09-03 from the R3 hop-check record; becomes Accepted when the author has rewritten it in his own words (the decisions below were made and hit live, this text just records them)
**Date:** 2026-09-03
**Phase:** R3
**Sibling:** [ADR 0007](0007-saga-messaging-outbox-orchestration-dlt.md), [ADR 0008](0008-deliberately-not-transactional.md), [ADR 0009](0009-payment-db-one-container-two-databases.md)

## Context

After R2 the booking saga spans two deployables. A single reservation now produces log lines in two
containers, on HTTP threads, Kafka listener threads and a `@Scheduled` sweeper, and the question
"what happened to reservation X" cannot be answered from either container's `docker logs` alone.
R3's goal is one query that shows a reservation's whole life across both services. Four decisions
had to be made to get there; each is recorded with the option it beat.

## Decision 1 — Structure at the source, not parse later

**Chosen:** every log line is a JSON object written by `logstash-logback-encoder`'s
`LogstashEncoder` (same `logback-spring.xml` in both services, `service` injected from
`spring.application.name`), and correlation ids ride on every line through **MDC** — `sagaId`
set-and-cleared at seven seams (orchestrator, sweeper per saga, both outbox relays per row, both
Kafka listeners from the envelope) plus `requestId` from a servlet filter on HTTP threads only.

**Rejected:** human-readable pattern logs, with Logstash `grok` extracting fields downstream.

**Why:** correlation is free at the source and expensive everywhere else. MDC entries become JSON
keys with zero log-statement rewrites; grok would have to re-derive them from text that was never
designed to be parsed. The load-bearing subtlety is thread hygiene: MDC is a `ThreadLocal` and
every thread here is pooled, so a seam that sets `sagaId` without clearing it makes the *next* unit
of work on that thread lie about its saga. A servlet filter covers HTTP threads only; listener and
scheduler threads are set-and-clear by hand. Verified by the lie-detector run: a second saga on the
same pooled threads added zero lines to the first saga's query (17 → 17).

## Decision 2 — Filebeat → Logstash → Elasticsearch, not Filebeat → Elasticsearch

**Chosen:** Filebeat autodiscovers the two app containers (image name contains
`ticket-reservation`) and ships to Logstash; Logstash unwraps and enriches; Elasticsearch stores.

**Rejected:** Filebeat straight into Elasticsearch, with an ingest pipeline for the JSON parse.

**Why:** Logstash earned its slot three times in one evening. (1) The two-layer unwrap — Docker's
`json-file` driver wraps the app's JSON line in `{log, stream, time}`; Filebeat's container parser
removes Docker's layer, and the `json` filter removes the app's, putting `level`, `logger_name`,
`service`, `sagaId` at the event root. (2) Lines that aren't JSON (the Spring banner, pre-logback
startup output) carry no `service`, which left the index name unresolved and the event dropped
with a warning; a `mutate` fallback to Filebeat's `container.name` keeps them findable. (3) A place
to drop noise — the health-probe rule turned out to be dead code (actuator logs at DEBUG, root is
INFO), but the real noise, Kafka client initialization inheriting the sagaId (8 of a happy-path
saga's 17 lines), is a one-line drop in the same block. An ingest pipeline could do (1); (2) and
(3) are where a filter stage pays for itself. It is also, literally, the E-L-K the resume claims.

## Decision 3 — Classic daily indices `applogs-<service>-<date>`, not data streams

**Chosen:** Logstash's `elasticsearch` output writes `applogs-%{[service]}-%{+YYYY.MM.dd}` with
`ilm_enabled => false` and installs a composable template (`applogs-*`: one shard, zero replicas)
at startup so a cold start reproduces the shape.

**Rejected:** Elasticsearch data streams (`data_stream => true`, dataset per service).

**Why:** one readable index per service per day is the classic log layout, `_cat/indices` shows
the pipeline working at a glance, and the Kibana data view is one wildcard. The prefix is not
cosmetic: the first attempt used `logs-<service>-<date>`, which matches Elasticsearch 9's built-in
`logs-*-*` index template (`data_stream: true`, priority 100). Elasticsearch silently created data
streams, which accept only `create` operations, and every Logstash `index` operation came back
`400` while `_cat/indices` showed empty `.ds-*` backing indices. Renaming the prefix was the fix;
going all-in on data streams would have been the other honest option, and is the shape a managed
production stack would use.

## Decision 4 — The local shape is deliberately not the production shape

**Chosen (local, behind `docker compose --profile elk`):** one Elasticsearch node with
`discovery.type=single-node`, `xpack.security.enabled=false` (no TLS, no auth — anyone on the
network is admin), a 512 MB heap, zero replicas, no ILM, `json-file` log rotation capped at
3 × 10 MB per app container, Kibana on host port 15601 because 5601 sits in a Windows WinNAT
excluded range. The profile means everyday `docker compose up` still runs only the core five.

**Rejected:** deploying the ELK trio to AWS alongside the services.

**Why:** cost and honesty. Production's answer on this footprint is CloudWatch Logs via the
`awslogs` driver already in the task definition — same JSON lines, same `sagaId` field, queried
with CloudWatch Logs Insights instead of KQL. What a real ELK deployment would add over the local
stack, in one breath: TLS on both HTTP and transport plus API keys, three master-eligible nodes
with replicas, an ILM policy rolling `applogs-*` hot → delete on age, and a managed offering
(Elastic Cloud or OpenSearch) instead of a self-run node. Saying that unprompted is the point of
running it locally at all.

## Consequences

- One KQL query — `sagaId : "<id>"` on the `applogs-*` data view — shows a reservation
  interleaved across both services in `@timestamp` order. Happy path: 17 lines. Declined card:
  5 lines, all INFO, because a declined charge is a business outcome, not a fault — the demo query
  is `sagaId`, never `level`.
- Every future seam that runs work on a non-HTTP thread must set-and-clear MDC or it corrupts
  correlation silently. This is now a review checklist item, not folklore.
- Index names must never start with `logs-` on Elasticsearch 8+/9 unless data streams are
  intended. Documented in the pipeline config and the learning notes.
- The ELK stack is a demo, not infrastructure: nothing in the application depends on it, and
  `docker compose down -v` removes it without trace.
- Not done, by choice: metrics, tracing, Kafka UI (design §4.2). Correlated logs were the cheapest
  observability win with the highest interview value; the others are named, not built.
