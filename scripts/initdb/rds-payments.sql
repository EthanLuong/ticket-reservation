-- One-time RDS step for the two-service deploy (R4 Task 3) — the prod echo of ADR 0009.
-- Compose gets this from scripts/initdb/01-payments.sh, which only runs when a Postgres volume
-- initializes EMPTY. RDS initialized in July, so this must be run by hand, once:
--
--   psql "host=ticket-reservation-db.czc0aweocw7v.us-east-2.rds.amazonaws.com dbname=ticketreservation user=postgres" \
--        -v payment_password="$(aws ssm get-parameter --with-decryption --name /ticketres/prod/payment-db-password --query Parameter.Value --output text)" \
--        -f rds-payments.sql
--
-- from somewhere inside the VPC (the Kafka EC2 box has psql; the RDS SG allows the task SG and
-- the Kafka SG — see the handbook's SG matrix). Put the password in SSM FIRST:
--   aws ssm put-parameter --name /ticketres/prod/payment-db-password --type SecureString --value '<strong random>'
-- and add ssm:GetParameters on that ARN to ecsTaskExecutionRole's policy, or the payment
-- container fails at startup with "ResourceInitializationError: unable to pull secrets".
--
-- Idempotent: safe to re-run.

-- psql interpolates :'var' as a quoted literal OUTSIDE string/dollar-quoted bodies, so the
-- role is created via SELECT … \gexec rather than a DO block (no interpolation inside $$…$$).
SELECT format('CREATE ROLE payment_user WITH LOGIN PASSWORD %L', :'payment_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_user') \gexec

SELECT 'CREATE DATABASE payments OWNER payment_user'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'payments') \gexec

-- Credential isolation is the point (ADR 0009): payment_user can never even connect to the
-- reservation database. Cross-service SQL fails at connection time, not as a grant accident.
REVOKE CONNECT ON DATABASE ticketreservation FROM PUBLIC;

-- Verify from the same session:
--   \c payments payment_user          -> connects
--   \c ticketreservation payment_user -> FATAL: permission denied for database
