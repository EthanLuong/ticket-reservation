#!/bin/sh
# Runs once, on first init of an EMPTY pgdata volume (docker-entrypoint-initdb.d contract).
# .sh instead of .sql so the password can come from the container environment instead of git.
set -e
: "${PAYMENT_DB_PASSWORD:?PAYMENT_DB_PASSWORD must be set (see .env.example)}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE payment_user WITH LOGIN PASSWORD '$PAYMENT_DB_PASSWORD';
CREATE DATABASE payments OWNER payment_user;
REVOKE CONNECT ON DATABASE ticketreservation FROM PUBLIC;
EOSQL
