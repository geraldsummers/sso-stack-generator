#!/bin/bash
set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:?ERROR: POSTGRES_USER not set}"
POSTGRES_DB="${POSTGRES_DB:?ERROR: POSTGRES_DB not set}"
POSTGRES_AGENT_PASSWORD="${POSTGRES_AGENT_PASSWORD:?ERROR: POSTGRES_AGENT_PASSWORD not set}"
POSTGRES_FORGEJO_PASSWORD="${POSTGRES_FORGEJO_PASSWORD:?ERROR: POSTGRES_FORGEJO_PASSWORD not set}"
POSTGRES_OPENWEBUI_PASSWORD="${POSTGRES_OPENWEBUI_PASSWORD:?ERROR: POSTGRES_OPENWEBUI_PASSWORD not set}"
POSTGRES_MASTODON_PASSWORD="${POSTGRES_MASTODON_PASSWORD:?ERROR: POSTGRES_MASTODON_PASSWORD not set}"
POSTGRES_PIPELINE_PASSWORD="${POSTGRES_PIPELINE_PASSWORD:?ERROR: POSTGRES_PIPELINE_PASSWORD not set}"
POSTGRES_AIRFLOW_PASSWORD="${POSTGRES_AIRFLOW_PASSWORD:?ERROR: POSTGRES_AIRFLOW_PASSWORD not set}"
POSTGRES_TEST_RUNNER_PASSWORD="${POSTGRES_TEST_RUNNER_PASSWORD:?ERROR: POSTGRES_TEST_RUNNER_PASSWORD not set}"

psql_base=(
  psql
  -v ON_ERROR_STOP=1
  --host "$POSTGRES_HOST"
  --port "$POSTGRES_PORT"
  --username "$POSTGRES_USER"
)

bootstrap_postgres_ssd() {
  "${psql_base[@]}" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'agent_observer') THEN
            CREATE USER agent_observer WITH PASSWORD \$pwd\$$POSTGRES_AGENT_PASSWORD\$pwd\$;
        ELSE
            ALTER USER agent_observer WITH PASSWORD \$pwd\$$POSTGRES_AGENT_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'forgejo') THEN
            CREATE USER forgejo WITH PASSWORD \$pwd\$$POSTGRES_FORGEJO_PASSWORD\$pwd\$;
        ELSE
            ALTER USER forgejo WITH PASSWORD \$pwd\$$POSTGRES_FORGEJO_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openwebui') THEN
            CREATE USER openwebui WITH PASSWORD \$pwd\$$POSTGRES_OPENWEBUI_PASSWORD\$pwd\$;
        ELSE
            ALTER USER openwebui WITH PASSWORD \$pwd\$$POSTGRES_OPENWEBUI_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'mastodon') THEN
            CREATE USER mastodon WITH PASSWORD \$pwd\$$POSTGRES_MASTODON_PASSWORD\$pwd\$;
        ELSE
            ALTER USER mastodon WITH PASSWORD \$pwd\$$POSTGRES_MASTODON_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pipeline_user') THEN
            CREATE USER pipeline_user WITH PASSWORD \$pwd\$$POSTGRES_PIPELINE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER pipeline_user WITH PASSWORD \$pwd\$$POSTGRES_PIPELINE_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'airflow') THEN
            CREATE USER airflow WITH PASSWORD \$pwd\$$POSTGRES_AIRFLOW_PASSWORD\$pwd\$;
        ELSE
            ALTER USER airflow WITH PASSWORD \$pwd\$$POSTGRES_AIRFLOW_PASSWORD\$pwd\$;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'test_runner_user') THEN
            CREATE USER test_runner_user WITH PASSWORD \$pwd\$$POSTGRES_TEST_RUNNER_PASSWORD\$pwd\$;
        ELSE
            ALTER USER test_runner_user WITH PASSWORD \$pwd\$$POSTGRES_TEST_RUNNER_PASSWORD\$pwd\$;
        END IF;
    END
    \$\$;

    SELECT 'CREATE DATABASE forgejo OWNER forgejo'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'forgejo')\gexec

    SELECT 'CREATE DATABASE openwebui OWNER openwebui'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'openwebui')\gexec

    SELECT 'CREATE DATABASE mastodon OWNER mastodon'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'mastodon')\gexec

    SELECT 'CREATE DATABASE webservices OWNER pipeline_user'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'webservices')\gexec

    SELECT 'CREATE DATABASE airflow OWNER airflow'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'airflow')\gexec

    GRANT ALL PRIVILEGES ON DATABASE forgejo TO forgejo;
    GRANT ALL PRIVILEGES ON DATABASE openwebui TO openwebui;
    GRANT ALL PRIVILEGES ON DATABASE mastodon TO mastodon;
    GRANT ALL PRIVILEGES ON DATABASE webservices TO pipeline_user;
    GRANT ALL PRIVILEGES ON DATABASE airflow TO airflow;
    GRANT CONNECT ON DATABASE webservices TO test_runner_user;
EOSQL

  "${psql_base[@]}" --dbname "forgejo" <<-'EOSQL'
    GRANT ALL ON SCHEMA public TO forgejo;
    CREATE SCHEMA IF NOT EXISTS agent_observer;
    GRANT CONNECT ON DATABASE forgejo TO agent_observer;
    GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
    GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO agent_observer;
EOSQL

  "${psql_base[@]}" --dbname "openwebui" -c "GRANT ALL ON SCHEMA public TO openwebui;"
  "${psql_base[@]}" --dbname "mastodon" <<-'EOSQL'
    GRANT ALL ON SCHEMA public TO mastodon;
    CREATE SCHEMA IF NOT EXISTS agent_observer;
    GRANT CONNECT ON DATABASE mastodon TO agent_observer;
    GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
    GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO agent_observer;
EOSQL

  "${psql_base[@]}" --dbname "webservices" -c "GRANT ALL ON SCHEMA public TO pipeline_user;"
  "${psql_base[@]}" --dbname "webservices" -c "GRANT USAGE ON SCHEMA public TO test_runner_user;"
  "${psql_base[@]}" --dbname "webservices" -c "ALTER DEFAULT PRIVILEGES FOR USER pipeline_user IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO test_runner_user;"
  "${psql_base[@]}" --dbname "webservices" <<-'EOSQL'
    CREATE EXTENSION IF NOT EXISTS pgcrypto;
    CREATE TABLE IF NOT EXISTS ingestion_sources (
        id TEXT PRIMARY KEY,
        source_type TEXT NOT NULL,
        enabled BOOLEAN NOT NULL DEFAULT TRUE,
        config JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS ingestion_runs (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        source_id TEXT NOT NULL REFERENCES ingestion_sources(id),
        dag_id TEXT NOT NULL,
        airflow_run_id TEXT,
        status TEXT NOT NULL,
        started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        finished_at TIMESTAMPTZ,
        records_seen BIGINT NOT NULL DEFAULT 0,
        records_indexed BIGINT NOT NULL DEFAULT 0,
        error_count BIGINT NOT NULL DEFAULT 0,
        metadata JSONB NOT NULL DEFAULT '{}'::jsonb
    );
    CREATE TABLE IF NOT EXISTS ingestion_checkpoints (
        source_id TEXT PRIMARY KEY REFERENCES ingestion_sources(id),
        checkpoint_key TEXT NOT NULL,
        checkpoint_value JSONB NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    DO $$
    BEGIN
        IF EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conrelid = 'ingestion_checkpoints'::regclass
              AND contype = 'p'
              AND pg_get_constraintdef(oid) = 'PRIMARY KEY (source_id)'
        ) THEN
            ALTER TABLE ingestion_checkpoints DROP CONSTRAINT ingestion_checkpoints_pkey;
            ALTER TABLE ingestion_checkpoints ADD PRIMARY KEY (source_id, checkpoint_key);
        END IF;
    END $$;
    CREATE TABLE IF NOT EXISTS ingestion_errors (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        run_id UUID REFERENCES ingestion_runs(id),
        source_id TEXT REFERENCES ingestion_sources(id),
        item_id TEXT,
        error_type TEXT NOT NULL,
        error_message TEXT NOT NULL,
        metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS publication_records (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        document_id TEXT NOT NULL,
        source_id TEXT NOT NULL REFERENCES ingestion_sources(id),
        presentation_target TEXT NOT NULL,
        presentation_url TEXT NOT NULL,
        bookstack_url TEXT,
        published BOOLEAN NOT NULL DEFAULT FALSE,
        search_ready BOOLEAN NOT NULL DEFAULT FALSE,
        metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE(document_id, presentation_target)
    );
    CREATE INDEX IF NOT EXISTS ingestion_runs_source_started_idx ON ingestion_runs(source_id, started_at DESC);
    CREATE INDEX IF NOT EXISTS ingestion_runs_status_idx ON ingestion_runs(status);
    CREATE INDEX IF NOT EXISTS ingestion_checkpoints_source_updated_idx ON ingestion_checkpoints(source_id, updated_at DESC);
    CREATE INDEX IF NOT EXISTS ingestion_errors_run_idx ON ingestion_errors(run_id);
    CREATE INDEX IF NOT EXISTS publication_records_source_idx ON publication_records(source_id);
    ALTER TABLE ingestion_sources OWNER TO pipeline_user;
    ALTER TABLE ingestion_runs OWNER TO pipeline_user;
    ALTER TABLE ingestion_checkpoints OWNER TO pipeline_user;
    ALTER TABLE ingestion_errors OWNER TO pipeline_user;
    ALTER TABLE publication_records OWNER TO pipeline_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ingestion_sources TO test_runner_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ingestion_runs TO test_runner_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ingestion_checkpoints TO test_runner_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ingestion_errors TO test_runner_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON publication_records TO test_runner_user;
EOSQL

  "${psql_base[@]}" --dbname "airflow" -c "GRANT ALL ON SCHEMA public TO airflow;"
}
