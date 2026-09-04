-- One-time RDS step for the two-service deploy (R4 Task 3) — the prod echo of ADR 0009.
-- Compose gets this from scripts/initdb/01-payments.sh, which only runs when a Postgres volume
-- initializes EMPTY. RDS initialized in July, so this must be run by hand, once.
--
-- Run it from inside the VPC. The Kafka EC2 box (Session Manager) works, with two caveats
-- learned on 2026-09-03: (1) it has Docker but no psql — use the postgres image; (2) the RDS
-- security group only admitted ticketres-app-sg, so a 5432 rule from ticketres-kafka-sg was
-- added (sgr-033ddc5258063e4fc). Put the password in SSM FIRST:
--   MSYS_NO_PATHCONV=1 aws ssm put-parameter --name /ticketres/prod/payment-db-password \
--       --type SecureString --value "$(openssl rand -hex 24)"
-- The execution role's ticketres-read-params policy already covers parameter/ticketres/prod/*,
-- so no IAM change is needed for the container to read it.
--
-- On the box (write this file to /tmp first, then):
--   sudo docker run -it --rm -v /tmp:/s postgres:17-alpine \
--     psql "host=ticket-reservation-db.czc0aweocw7v.us-east-2.rds.amazonaws.com dbname=ticketreservation user=postgres" \
--     -v payment_password='<value from SSM>' -f /s/rds-payments.sql
--
-- Idempotent: safe to re-run.

-- psql interpolates :'var' as a quoted literal OUTSIDE string/dollar-quoted bodies, so the
-- role is created via SELECT … \gexec rather than a DO block (no interpolation inside $$…$$).
SELECT format('CREATE ROLE payment_user WITH LOGIN PASSWORD %L', :'payment_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_user') \gexec

-- PG16+: a non-superuser that creates a role gets ADMIN on it but not SET, and the RDS master
-- user is not a superuser. CREATE DATABASE ... OWNER checks SET, so without this line the next
-- statement fails with: must be able to SET ROLE "payment_user". Re-granting is a no-op.
GRANT payment_user TO postgres;

SELECT 'CREATE DATABASE payments OWNER payment_user'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'payments') \gexec

-- Credential isolation is the point (ADR 0009): payment_user can never even connect to the
-- reservation database. Cross-service SQL fails at connection time, not as a grant accident.
-- The postgres master user is the database owner and keeps CONNECT regardless of PUBLIC.
REVOKE CONNECT ON DATABASE ticketreservation FROM PUBLIC;

-- Verify from the box (both prompts take the payment password):
--   sudo docker run -it --rm postgres:17-alpine psql "host=<rds> dbname=payments user=payment_user"
--     -> connects (TLS)
--   payments=> \c ticketreservation
--     -> FATAL: permission denied for database "ticketreservation" / User does not have CONNECT privilege.
