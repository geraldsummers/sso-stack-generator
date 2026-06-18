#!/usr/bin/env bash
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:?ERROR: POSTGRES_USER not set}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:?ERROR: POSTGRES_PASSWORD not set}"
POSTGRES_DB="${POSTGRES_DB:-postgres}"
POSTGRES_KEYCLOAK_PASSWORD="${POSTGRES_KEYCLOAK_PASSWORD:?ERROR: POSTGRES_KEYCLOAK_PASSWORD not set}"

export PGPASSWORD="$POSTGRES_PASSWORD"

until pg_isready -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
  sleep 2
done

psql -v ON_ERROR_STOP=1 \
  -v keycloak_password="$POSTGRES_KEYCLOAK_PASSWORD" \
  -h "$POSTGRES_HOST" \
	  -p "$POSTGRES_PORT" \
	  --username "$POSTGRES_USER" \
	  --dbname "$POSTGRES_DB" <<-EOSQL
	    SET webservices.keycloak_password TO :'keycloak_password';
	    DO \$\$
	    BEGIN
	      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'keycloak') THEN
	        EXECUTE format('CREATE USER keycloak WITH PASSWORD %L', current_setting('webservices.keycloak_password'));
	      ELSE
	        EXECUTE format('ALTER USER keycloak WITH PASSWORD %L', current_setting('webservices.keycloak_password'));
	      END IF;
    END
    \$\$;
    SELECT 'CREATE DATABASE keycloak OWNER keycloak'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
    GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak;
EOSQL

psql -v ON_ERROR_STOP=1 \
  -h "$POSTGRES_HOST" \
  -p "$POSTGRES_PORT" \
  --username "$POSTGRES_USER" \
  --dbname keycloak \
  -c "GRANT ALL ON SCHEMA public TO keycloak;"

printf 'Keycloak PostgreSQL role and database are ready\n'
