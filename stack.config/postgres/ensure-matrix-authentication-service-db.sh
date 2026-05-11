#!/usr/bin/env bash
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:?ERROR: POSTGRES_USER not set}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?ERROR: POSTGRES_PASSWORD not set}"
POSTGRES_DB="${POSTGRES_DB:-postgres}"
POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD="${POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD:?ERROR: POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD not set}"

export PGPASSWORD="$POSTGRES_PASSWORD"

until pg_isready -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
  sleep 2
done

psql -v ON_ERROR_STOP=1 \
  -h "$POSTGRES_HOST" \
  -p "$POSTGRES_PORT" \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'matrix_authentication_service') THEN
        CREATE USER matrix_authentication_service WITH PASSWORD \$pwd\$$POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD\$pwd\$;
      ELSE
        ALTER USER matrix_authentication_service WITH PASSWORD \$pwd\$$POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD\$pwd\$;
      END IF;
    END
    \$\$;
    SELECT 'CREATE DATABASE matrix_authentication_service OWNER matrix_authentication_service'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'matrix_authentication_service')\gexec
    GRANT ALL PRIVILEGES ON DATABASE matrix_authentication_service TO matrix_authentication_service;
EOSQL

psql -v ON_ERROR_STOP=1 \
  -h "$POSTGRES_HOST" \
  -p "$POSTGRES_PORT" \
  --username "$POSTGRES_USER" \
  --dbname matrix_authentication_service \
  -c "GRANT ALL ON SCHEMA public TO matrix_authentication_service;"

printf 'Matrix Authentication Service PostgreSQL role and database are ready\n'
