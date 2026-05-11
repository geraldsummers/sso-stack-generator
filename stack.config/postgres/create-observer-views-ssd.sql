-- PostgreSQL Observer Views (postgres-ssd)
-- Creates read-only views for agent_observer that expose only public/metadata.
--
-- Note: Run this after Forgejo and Mastodon have initialized their schemas:
--   docker exec -i postgres-ssd psql -U <admin> -d <database> < create-observer-views-ssd.sql

-- =============================================================================
-- FORGEJO DATABASE - Public repository metadata only
-- =============================================================================
\c forgejo

CREATE SCHEMA IF NOT EXISTS agent_observer;

DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'repository') THEN
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'repository' AND column_name = 'is_archived') THEN
            CREATE OR REPLACE VIEW agent_observer.public_repositories AS
            SELECT
                id,
                owner_id,
                name,
                description,
                is_private,
                is_archived,
                num_stars,
                num_forks,
                created_unix,
                updated_unix
            FROM repository
            WHERE is_private = false AND is_archived = false;
            RAISE NOTICE 'Created agent_observer.public_repositories view (with is_archived filter)';
        ELSE
            CREATE OR REPLACE VIEW agent_observer.public_repositories AS
            SELECT
                id,
                owner_id,
                name,
                description,
                is_private,
                num_stars,
                num_forks,
                created_unix,
                updated_unix
            FROM repository
            WHERE is_private = false;
            RAISE NOTICE 'Created agent_observer.public_repositories view (no is_archived column)';
        END IF;
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_repositories - repository table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- MASTODON DATABASE - Public posts only (no DMs, no private posts)
-- =============================================================================
\c mastodon

CREATE SCHEMA IF NOT EXISTS agent_observer;

DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'statuses') THEN
        CREATE OR REPLACE VIEW agent_observer.public_statuses AS
        SELECT
            id,
            account_id,
            text,
            created_at,
            updated_at,
            visibility,
            language
        FROM statuses
        WHERE visibility = 0
        AND deleted_at IS NULL;
        RAISE NOTICE 'Created agent_observer.public_statuses view';
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_statuses - statuses table does not exist yet';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'accounts') THEN
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'accounts' AND column_name = 'followers_count') THEN
            CREATE OR REPLACE VIEW agent_observer.public_accounts AS
            SELECT
                id,
                username,
                domain,
                display_name,
                created_at,
                updated_at,
                followers_count,
                following_count
            FROM accounts
            WHERE suspended_at IS NULL;
            RAISE NOTICE 'Created agent_observer.public_accounts view (with followers_count)';
        ELSE
            CREATE OR REPLACE VIEW agent_observer.public_accounts AS
            SELECT
                id,
                username,
                domain,
                display_name,
                created_at,
                updated_at
            FROM accounts
            WHERE suspended_at IS NULL;
            RAISE NOTICE 'Created agent_observer.public_accounts view (no followers_count column)';
        END IF;
    ELSE
        RAISE NOTICE 'Skipping agent_observer.public_accounts - accounts table does not exist yet';
    END IF;
END $$;

GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;

-- =============================================================================
-- OPENWEBUI DATABASE - Explicitly no observer access
-- =============================================================================
\c openwebui
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM agent_observer;
