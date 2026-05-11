-- PostgreSQL Observer Views
-- Creates read-only views for agent_observer that expose only public/metadata
-- These views are safe for LM consumption and exclude all sensitive user data

-- Note: This script should be run after all applications have created their tables.
-- This file targets the main postgres cluster (grafana, planka).
-- Forgejo and Mastodon now live on postgres-ssd and have their own observer script.
-- Run this manually after the stack is fully initialized:
--   docker exec -i postgres psql -U <admin> -d <database> < create-observer-views.sql

-- =============================================================================
-- GRAFANA DATABASE - Public metadata only
-- =============================================================================
\c grafana

-- Public: Dashboard metadata (no queries, no variables - those might contain credentials)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'dashboard') THEN
        CREATE OR REPLACE VIEW agent_observer.public_dashboards AS
        SELECT
            id,
            org_id,
            title,
            slug,
            created,
            updated,
            is_folder,
            folder_id,
            uid
        FROM dashboard
        WHERE is_folder = false;
        RAISE NOTICE 'Created agent_observer.public_dashboards view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_dashboards - dashboard table does not exist yet';
    END IF;
END $$;

-- Public: Organization list
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'org') THEN
        CREATE OR REPLACE VIEW agent_observer.public_orgs AS
        SELECT id, name, created, updated FROM org;
        RAISE NOTICE 'Created agent_observer.public_orgs view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_orgs - org table does not exist yet';
    END IF;
END $$;

-- Public: Data source types (no credentials)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'data_source') THEN
        CREATE OR REPLACE VIEW agent_observer.public_datasource_types AS
        SELECT DISTINCT type, name FROM data_source;
        RAISE NOTICE 'Created agent_observer.public_datasource_types view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_datasource_types - data_source table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- PLANKA DATABASE - Public boards and lists only
-- =============================================================================
\c planka

CREATE SCHEMA IF NOT EXISTS agent_observer;

-- Public: Board names and descriptions (no card content)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'board') THEN
        -- Check if is_archived column exists
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'board' AND column_name = 'is_archived') THEN
            CREATE OR REPLACE VIEW agent_observer.public_boards AS
            SELECT
                id,
                name,
                created_at,
                updated_at
            FROM board
            WHERE is_archived = false;
            RAISE NOTICE 'Created agent_observer.public_boards view (with is_archived filter)';
        ELSE
            CREATE OR REPLACE VIEW agent_observer.public_boards AS
            SELECT
                id,
                name,
                created_at,
                updated_at
            FROM board;
            RAISE NOTICE 'Created agent_observer.public_boards view (no is_archived column, showing all boards)';
        END IF;
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_boards - board table does not exist yet';
    END IF;
END $$;

-- Public: List names within boards
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'list')
       AND EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'board') THEN
        -- Check if is_archived column exists in board table
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'board' AND column_name = 'is_archived') THEN
            CREATE OR REPLACE VIEW agent_observer.public_lists AS
            SELECT
                l.id,
                l.board_id,
                l.name,
                l.position,
                b.name as board_name
            FROM list l
            JOIN board b ON l.board_id = b.id
            WHERE b.is_archived = false;
            RAISE NOTICE 'Created agent_observer.public_lists view (with is_archived filter)';
        ELSE
            CREATE OR REPLACE VIEW agent_observer.public_lists AS
            SELECT
                l.id,
                l.board_id,
                l.name,
                l.position,
                b.name as board_name
            FROM list l
            JOIN board b ON l.board_id = b.id;
            RAISE NOTICE 'Created agent_observer.public_lists view (no is_archived filter)';
        END IF;
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_lists - list or board table does not exist yet';
    END IF;
END $$;

-- Public: Card count per list (no card details)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'card') THEN
        -- Check if is_archived column exists
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'card' AND column_name = 'is_archived') THEN
            CREATE OR REPLACE VIEW agent_observer.public_list_stats AS
            SELECT
                list_id,
                COUNT(*) as card_count
            FROM card
            WHERE is_archived = false
            GROUP BY list_id;
            RAISE NOTICE 'Created agent_observer.public_list_stats view (with is_archived filter)';
        ELSE
            CREATE OR REPLACE VIEW agent_observer.public_list_stats AS
            SELECT
                list_id,
                COUNT(*) as card_count
            FROM card
            GROUP BY list_id;
            RAISE NOTICE 'Created agent_observer.public_list_stats view (no is_archived filter)';
        END IF;
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_list_stats - card table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- OTHER DATABASES - No access at all (too sensitive)
-- =============================================================================
-- VAULTWARDEN: Contains passwords - NO ACCESS
-- KEYCLOAK: Contains sessions/auth tokens - NO ACCESS
-- SYNAPSE: Contains private messages - NO ACCESS
-- OPENWEBUI: May contain conversation history - NO ACCESS

-- Revoke all access to sensitive databases
\c vaultwarden
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

\c keycloak
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;

\c synapse
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;
