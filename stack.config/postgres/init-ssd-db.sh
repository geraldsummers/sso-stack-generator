#!/bin/bash
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

psql -v ON_ERROR_STOP=1 --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
    -- Keep template databases clean; application databases are created explicitly below.
    \connect template1
    DROP EXTENSION IF EXISTS timescaledb CASCADE;
    \connect postgres
    DROP EXTENSION IF EXISTS timescaledb CASCADE;
EOSQL

. /docker-entrypoint-initdb.d/00-ssd-bootstrap-common.sh
bootstrap_postgres_ssd
echo "postgres-ssd databases initialized successfully"
