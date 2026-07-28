# M4 AWS Deployment — Decisions

Prepared 2026-07-28 (Claude, pre-deploy prep session). Everything below is either **DECIDED**
(defaults chosen for you — override any of them, but they don't block starting) or
**NEEDS INPUT** (blocks a specific phase; decide before reaching it, not before starting).

## Decided

| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Region | **us-east-2 (Ohio)** | Closest region to Quad Cities; full service coverage; identical pricing to us-east-1 with less legacy weirdness |
| D2 | Compute | **ECS Fargate, 2 app tasks** (0.5 vCPU / 1 GB each), x86_64 | Fargate is the mid-level answer; 2 tasks makes the multi-instance story live. x86 keeps CI builds trivial (GitHub runners are amd64); Graviton is a later cost tweak, not a v1 concern |
| D3 | Network | **Public subnets only, 2 AZs, strict security groups, NO NAT Gateway** | NAT is ~$32/mo just so private tasks can pull images. Portfolio-grade alternative: tasks get public IPs but their SGs accept traffic ONLY from the ALB SG. The ADR states the prod-correct version (private subnets + VPC endpoints) — knowing the tradeoff *is* the interview value |
| D4 | Edge / HTTPS | **CloudFront, two origins**: S3 (OAC) serves the SPA, `/api/*` behavior forwards to the ALB | Works because `api.ts` defaults `VITE_API_BASE` to same-origin and all API routes sit under `/api/*`. One URL, HTTPS via the default `*.cloudfront.net` cert, **zero CORS config, no domain purchase required to ship**. ALB SG locked to CloudFront's origin-facing managed prefix list |
| D5 | Database | **RDS Postgres 17, db.t4g.micro, single-AZ, 20 GB gp3** | Managed-where-state-lives. Single-AZ is the honest portfolio call (say it, don't hide it). Free tier covers 750 h/mo for 12 months on a new account |
| D6 | Redis | **ElastiCache, cache.t4g.micro, single node** — pick **Valkey** engine if offered (cheaper), else Redis OSS | Protocol-compatible with the Lettuce + Redisson clients; managed-where-state-lives again |
| D7 | Secrets | **SSM Parameter Store (SecureString, free tier)** injected via ECS task-definition `secrets` | Zero code changes — ECS injects parameters as env vars (`SPRING_DATASOURCE_PASSWORD` etc.) that Spring already reads. Secrets Manager ($0.40/secret/mo) adds rotation we don't need. Spring Cloud AWS config-import is a stretch goal, not v1 |
| D8 | CI/CD | **GitHub Actions + OIDC federation** (no long-lived AWS keys in repo secrets) | Drafts staged in `docs/aws/artifacts/` — move to `.github/workflows/` only when the AWS side exists |
| D9 | CLI auth (you → AWS) | **IAM admin user + access key** for `aws` CLI, MFA on console, root locked away | IAM Identity Center is the prod answer; for a solo learning account it's ceremony. Documented as a known simplification |
| D10 | App profile | **`SPRING_PROFILES_ACTIVE=prod`** + real JWT secret from Parameter Store | Your own `JwtSecretGuard` fails boot on the dev default under any non-dev profile — the deploy exercises the guard you built (I5) |
| D11 | DB seeding | Temporarily set RDS **publicly accessible** with its SG locked to your home IP, run `dev-seed.sql` from Windows, then flip it back off | Pragmatic basic-level answer; the alternatives (ECS Exec, bastion) are ceremony for a one-time seed. Keep VIP-1 ($150) — the decline demo works in prod too |
| D12 | Kafka topics | Auto-created by the app's `KafkaTopicConfig` (KafkaAdmin) on first boot | Already in the code — 4 topics, 3 partitions each. Nothing to do |
| D13 | Sequencing | **Ticket-reservation IS the first AWS deploy** — the kitchen runbook is superseded as the on-ramp (your call, this session: "first real aws deploy") | Its EC2/RDS/S3 concepts are folded into this handbook where relevant |

## Provisional (decided, but review at the flagged phase)

| # | Decision | Choice | Review because |
|---|---|---|---|
| P1 | Kafka hosting | **One t4g.small EC2** (~$12/mo) running the same `apache/kafka:4.0.0` container as docker-compose, via user-data, data on its EBS volume | The alternative (Fargate + EFS volume) is more "serverless-consistent" but EFS+Kafka has fsync-latency warts and costs more. Review at Phase 4 before creating anything. Either way the ADR says "MSK in prod (~$100+/mo), self-managed for portfolio" — that ADR is the interview answer |
| P2 | Run cadence | **Deploy → verify → screenshot/demo → tear down**; redeploy takes ~15 min via the pipeline once CI/CD lands | Always-on is ~$75–85/mo (~$55 with RDS free tier) vs the $20/mo budget tripwire. If you decide you want it always-on, raise the budget consciously — don't let the alarm decide for you |

## Needs your input (none block starting)

| # | Question | Blocks | Recommendation |
|---|---|---|---|
| Q1 | **Buy a custom domain?** (e.g. `ethanluong.dev`, ~$12/yr Route 53) | Nothing — D4 ships HTTPS on `*.cloudfront.net` without one | Yes, eventually: one domain serves this project, kitchen, and a portfolio page, and looks better on a resume. Can be added after the deploy works (CloudFront alternate domain + ACM cert, ~30 min). Not a v1 blocker |
| Q2 | **AWS account email + payment card** | Phase 0 — everything | Use a dedicated email alias (e.g. `ethaniluong+aws@gmail.com`) so account mail is filterable |
| Q3 | Ratify P1 (Kafka on EC2) | Phase 4 | Keep EC2 unless you want to say "everything on Fargate" in interviews badly enough to pay the EFS tax |
| Q4 | Ratify P2 (teardown cadence) | Nothing until the first month's bill | Teardown between sessions until M4 is done end-to-end once |
