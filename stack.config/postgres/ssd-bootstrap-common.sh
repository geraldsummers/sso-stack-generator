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
POSTGRES_SEARCH_SERVICE_PASSWORD="${POSTGRES_SEARCH_SERVICE_PASSWORD:?ERROR: POSTGRES_SEARCH_SERVICE_PASSWORD not set}"
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

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'search_service_user') THEN
            CREATE USER search_service_user WITH PASSWORD \$pwd\$$POSTGRES_SEARCH_SERVICE_PASSWORD\$pwd\$;
        ELSE
            ALTER USER search_service_user WITH PASSWORD \$pwd\$$POSTGRES_SEARCH_SERVICE_PASSWORD\$pwd\$;
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

    GRANT ALL PRIVILEGES ON DATABASE forgejo TO forgejo;
    GRANT ALL PRIVILEGES ON DATABASE openwebui TO openwebui;
    GRANT ALL PRIVILEGES ON DATABASE mastodon TO mastodon;
    GRANT ALL PRIVILEGES ON DATABASE webservices TO pipeline_user;
    GRANT CONNECT ON DATABASE webservices TO search_service_user;
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
  "${psql_base[@]}" --dbname "webservices" -c "GRANT USAGE ON SCHEMA public TO search_service_user;"
  "${psql_base[@]}" --dbname "webservices" -c "GRANT USAGE ON SCHEMA public TO test_runner_user;"
  "${psql_base[@]}" --dbname "webservices" -c "ALTER DEFAULT PRIVILEGES FOR USER pipeline_user IN SCHEMA public GRANT SELECT ON TABLES TO search_service_user;"
  "${psql_base[@]}" --dbname "webservices" -c "ALTER DEFAULT PRIVILEGES FOR USER pipeline_user IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO test_runner_user;"
  "${psql_base[@]}" --dbname "webservices" <<-'EOSQL'
    CREATE TABLE IF NOT EXISTS dedupe_records (
        id SERIAL PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        item_id VARCHAR(500) NOT NULL,
        content_hash VARCHAR(64) NOT NULL,
        first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        last_seen_run_id VARCHAR(100),
        fetch_type VARCHAR(100),
        UNIQUE(source, item_id)
    );
    CREATE TABLE IF NOT EXISTS fetch_history (
        id SERIAL PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        category VARCHAR(100) NOT NULL,
        item_count INTEGER,
        fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        metadata TEXT,
        fetch_type VARCHAR(100),
        status VARCHAR(50),
        record_count INTEGER,
        error_message TEXT,
        execution_time_ms INTEGER
    );
    CREATE TABLE IF NOT EXISTS document_staging (
        id VARCHAR(500) PRIMARY KEY,
        source VARCHAR(255) NOT NULL,
        collection VARCHAR(255) NOT NULL,
        text TEXT NOT NULL,
        metadata TEXT NOT NULL,
        embedding_status VARCHAR(50) NOT NULL,
        chunk_index INTEGER,
        total_chunks INTEGER,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        retry_count INTEGER NOT NULL DEFAULT 0,
        error_message TEXT,
        bookstack_url TEXT,
        vector_id VARCHAR(500)
    );
    CREATE INDEX IF NOT EXISTS dedupe_records_hash_idx ON dedupe_records(content_hash);
    CREATE INDEX IF NOT EXISTS dedupe_records_first_seen_idx ON dedupe_records(first_seen);
    CREATE INDEX IF NOT EXISTS fetch_history_source_idx ON fetch_history(source);
    CREATE INDEX IF NOT EXISTS fetch_history_fetched_at_idx ON fetch_history(fetched_at);
    CREATE INDEX IF NOT EXISTS fetch_history_status_idx ON fetch_history(status);
    CREATE INDEX IF NOT EXISTS idx_staging_status_created ON document_staging(embedding_status, created_at);
    CREATE INDEX IF NOT EXISTS idx_staging_collection_status ON document_staging(collection, embedding_status);
    CREATE INDEX IF NOT EXISTS idx_staging_source ON document_staging(source);
    CREATE INDEX IF NOT EXISTS idx_staging_fulltext_completed
        ON document_staging
        USING GIN (
            (
                setweight(to_tsvector('english', COALESCE(metadata::json->>'title', '')), 'A') ||
                setweight(to_tsvector('english', text), 'B')
            )
        )
        WHERE embedding_status = 'COMPLETED';
    ALTER TABLE dedupe_records OWNER TO pipeline_user;
    ALTER TABLE fetch_history OWNER TO pipeline_user;
    ALTER TABLE document_staging OWNER TO pipeline_user;
    GRANT SELECT ON dedupe_records TO search_service_user, test_runner_user;
    GRANT SELECT ON fetch_history TO search_service_user, test_runner_user;
    GRANT SELECT ON document_staging TO search_service_user;
    GRANT SELECT, INSERT, UPDATE, DELETE ON document_staging TO test_runner_user;
EOSQL
}
