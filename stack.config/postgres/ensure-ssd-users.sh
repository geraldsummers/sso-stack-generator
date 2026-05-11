#!/bin/bash
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres-ssd}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

until pg_isready --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" >/dev/null 2>&1; do
  echo "[postgres-ssd-bootstrap] waiting for PostgreSQL at ${POSTGRES_HOST}:${POSTGRES_PORT}..."
  sleep 2
done

echo "[postgres-ssd-bootstrap] PostgreSQL is reachable; ensuring roles, databases, and shared tables..."
. /ensure-ssd-bootstrap-common.sh
bootstrap_postgres_ssd
echo "[postgres-ssd-bootstrap] postgres-ssd bootstrap verified"
