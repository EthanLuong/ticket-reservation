# AWS artifacts — what each file is, and the R4 activation order

Updated 2026-09-03 for the two-service shape (R4). Everything here is committed so that a
teardown → redeploy is a checklist, not archaeology.

| File | What | State |
|---|---|---|
| `task-def-app.json` | Task definition **revision 6 shape**: containers `app` (reservation-service, :8080, ALB target) + `payment` (payment-service, :8081 internal). Images say `REPLACED_BY_WORKFLOW`; the deploy job swaps them by container name. Derived from the live revision 5 on 2026-09-03 (same RDS/ElastiCache/Kafka endpoints, same SSM parameters, plus payment's). | draft — register via the workflow or by hand once |
| `github-deploy-policy.json` | Inline policy for the `github-deploy` OIDC role: ECR push to **both** repositories, ECS describe/register/update, `iam:PassRole` for the two task roles. (The old draft's S3/CloudFront statements belong to `deploy-frontend.yml`; keep them if you reuse one role for both workflows.) | draft |
| `../../../.github/workflows/deploy-backend.yml` | The backend workflow — moved out of this folder. `workflow_dispatch` only until the first green run; then uncomment `push`. | active file, inert trigger |
| `deploy-frontend.yml` | S3 upload + CloudFront invalidation for the SPA. Unchanged by R4. | draft |
| `kafka-ec2-user-data.sh` | KRaft broker bootstrap for the t4g.small. Unchanged. | as deployed |
| `teardown.sh` | Deletes the billable footprint. Run at the end of R4 Task 6. | as before |
| `../../../scripts/initdb/rds-payments.sql` | One-time `payment_user` + `payments` DB + `REVOKE CONNECT` on RDS (ADR 0009's prod echo). | run once before the first two-container deploy |

## R4 activation order (Tasks 2–3 of the guide)

1. **ECR:** create `payment-service`; either rename `ticket-reservation` → `reservation-service` or create `reservation-service` and let the old repo age out. Lifecycle policy (keep last 10) on both.
2. **SSM:** `MSYS_NO_PATHCONV=1 aws ssm put-parameter --name /ticketres/prod/payment-db-password --type SecureString --value "$(openssl rand -hex 24)"` (the prefix stops Git Bash mangling the leading slash). No IAM change: the execution role's `ticketres-read-params` policy already grants `ssm:GetParameters` on `parameter/ticketres/prod/*`.
3. **RDS:** run `scripts/initdb/rds-payments.sql` from the Kafka EC2 box via Session Manager (header of the file has the command and the two caveats: no psql on the box, and the db SG needed a rule from the kafka SG — added 2026-09-03).
4. **OIDC:** IAM → Identity providers → `token.actions.githubusercontent.com`, audience `sts.amazonaws.com`; role `github-deploy` with web-identity trust pinned to `repo:EthanLuong/ticket-reservation:ref:refs/heads/main`; attach `github-deploy-policy.json`.
5. **First run:** Actions → deploy-backend → Run workflow. Watch: 2 test legs → 2 build legs → deploy. Against the single-container revision 5 the deploy's name-based `jq` edit is a no-op for `payment`, so the first run produces a **one-container revision 6** on the new reservation image (done 2026-09-03). The two-container shape has to be registered by hand once from `task-def-app.json` (Task 3); every run after that swaps both images.
6. **Two-container revision:** fill the two `REPLACED_BY_WORKFLOW` tags with a SHA that exists in both repos, `aws ecs register-task-definition --cli-input-json file://…`, then `aws ecs update-service --cluster ticket-reservation --service ticket-reservation-app --task-definition ticket-reservation-app` and wait for stable.
7. **Smoke:** `SMOKE_BASE_URL=https://d1bsa4m1s90vp2.cloudfront.net bash scripts/e2e-smoke.sh`.
8. Uncomment the `push` trigger; commit.

## Known-stale bits from July, fixed here

- `paths: ['src/**', 'pom.xml', 'Dockerfile']` — none exist at the repo root since R1; the workflow would never have fired.
- `docker build … .` — the build context is now a module directory, and there are two.
- `.containerDefinitions[0].image` — index-based; the deploy job now selects by `.name`.
