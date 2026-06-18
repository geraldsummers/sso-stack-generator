#!/bin/bash
set -e
# Validate required environment variables using new naming convention
POSTGRES_GRAFANA_PASSWORD="${POSTGRES_GRAFANA_PASSWORD:?ERROR: POSTGRES_GRAFANA_PASSWORD not set}"
POSTGRES_KEYCLOAK_PASSWORD="${POSTGRES_KEYCLOAK_PASSWORD:-}"
POSTGRES_PLANKA_PASSWORD="${POSTGRES_PLANKA_PASSWORD:?ERROR: POSTGRES_PLANKA_PASSWORD not set}"
POSTGRES_SYNAPSE_PASSWORD="${POSTGRES_SYNAPSE_PASSWORD:?ERROR: POSTGRES_SYNAPSE_PASSWORD not set}"
POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD="${POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD:?ERROR: POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD not set}"
POSTGRES_VAULTWARDEN_PASSWORD="${POSTGRES_VAULTWARDEN_PASSWORD:?ERROR: POSTGRES_VAULTWARDEN_PASSWORD not set}"
POSTGRES_HOMEASSISTANT_PASSWORD="${POSTGRES_HOMEASSISTANT_PASSWORD:-}"
POSTGRES_AGENT_PASSWORD="${POSTGRES_AGENT_PASSWORD:?ERROR: POSTGRES_AGENT_PASSWORD not set}"
POSTGRES_TXGATEWAY_USER="${POSTGRES_TXGATEWAY_USER:-txgateway}"
POSTGRES_TXGATEWAY_PASSWORD="${POSTGRES_TXGATEWAY_PASSWORD:?ERROR: POSTGRES_TXGATEWAY_PASSWORD not set}"
# PGPASSWORD already set by docker-compose environment
psql -v ON_ERROR_STOP=1 \
    -v planka_password="$POSTGRES_PLANKA_PASSWORD" \
    -v synapse_password="$POSTGRES_SYNAPSE_PASSWORD" \
    -v matrix_authentication_service_password="$POSTGRES_MATRIX_AUTHENTICATION_SERVICE_PASSWORD" \
    -v grafana_password="$POSTGRES_GRAFANA_PASSWORD" \
    -v keycloak_password="$POSTGRES_KEYCLOAK_PASSWORD" \
    -v vaultwarden_password="$POSTGRES_VAULTWARDEN_PASSWORD" \
    -v homeassistant_password="$POSTGRES_HOMEASSISTANT_PASSWORD" \
    -v agent_password="$POSTGRES_AGENT_PASSWORD" \
    -v txgateway_user="$POSTGRES_TXGATEWAY_USER" \
    -v txgateway_password="$POSTGRES_TXGATEWAY_PASSWORD" \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" <<-EOSQL
	    -- Create users with passwords from environment (must be created before databases for ownership)
	    -- Use DO block to check if user exists before creating
	    SET webservices.planka_password TO :'planka_password';
	    SET webservices.synapse_password TO :'synapse_password';
	    SET webservices.matrix_authentication_service_password TO :'matrix_authentication_service_password';
	    SET webservices.grafana_password TO :'grafana_password';
	    SET webservices.keycloak_password TO :'keycloak_password';
	    SET webservices.vaultwarden_password TO :'vaultwarden_password';
	    SET webservices.homeassistant_password TO :'homeassistant_password';
	    SET webservices.agent_password TO :'agent_password';
	    SET webservices.txgateway_user TO :'txgateway_user';
	    SET webservices.txgateway_password TO :'txgateway_password';
	    DO \$\$
	    BEGIN
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'planka') THEN
	            EXECUTE format('CREATE USER planka WITH PASSWORD %L', current_setting('webservices.planka_password'));
	        ELSE
	            EXECUTE format('ALTER USER planka WITH PASSWORD %L', current_setting('webservices.planka_password'));
	        END IF;
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'synapse') THEN
	            EXECUTE format('CREATE USER synapse WITH PASSWORD %L', current_setting('webservices.synapse_password'));
	        ELSE
	            EXECUTE format('ALTER USER synapse WITH PASSWORD %L', current_setting('webservices.synapse_password'));
	        END IF;
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'matrix_authentication_service') THEN
	            EXECUTE format('CREATE USER matrix_authentication_service WITH PASSWORD %L', current_setting('webservices.matrix_authentication_service_password'));
	        ELSE
	            EXECUTE format('ALTER USER matrix_authentication_service WITH PASSWORD %L', current_setting('webservices.matrix_authentication_service_password'));
	        END IF;
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'grafana') THEN
	            EXECUTE format('CREATE USER grafana WITH PASSWORD %L', current_setting('webservices.grafana_password'));
	        ELSE
	            EXECUTE format('ALTER USER grafana WITH PASSWORD %L', current_setting('webservices.grafana_password'));
	        END IF;
	        IF LENGTH(current_setting('webservices.keycloak_password')) > 0 THEN
	            IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'keycloak') THEN
	                EXECUTE format('CREATE USER keycloak WITH PASSWORD %L', current_setting('webservices.keycloak_password'));
	            ELSE
	                EXECUTE format('ALTER USER keycloak WITH PASSWORD %L', current_setting('webservices.keycloak_password'));
	            END IF;
	        END IF;
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'vaultwarden') THEN
	            EXECUTE format('CREATE USER vaultwarden WITH PASSWORD %L', current_setting('webservices.vaultwarden_password'));
	        ELSE
	            EXECUTE format('ALTER USER vaultwarden WITH PASSWORD %L', current_setting('webservices.vaultwarden_password'));
	        END IF;
	        -- Create homeassistant user if password is set (HA may use SQLite instead)
	        IF LENGTH(current_setting('webservices.homeassistant_password')) > 0 THEN
	            IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'homeassistant') THEN
	                EXECUTE format('CREATE USER homeassistant WITH PASSWORD %L', current_setting('webservices.homeassistant_password'));
	            ELSE
	                EXECUTE format('ALTER USER homeassistant WITH PASSWORD %L', current_setting('webservices.homeassistant_password'));
	            END IF;
	        END IF;
        -- Shadow agent accounts are created per-user via obsolete/scripts/security/create-shadow-agent-account.main.kts
        -- Each user gets: {username}-agent role with read-only access to agent_observer schema
	        -- Create global agent_observer account for tests and anonymous access (fallback only)
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'agent_observer') THEN
	            EXECUTE format('CREATE USER agent_observer WITH PASSWORD %L', current_setting('webservices.agent_password'));
	        ELSE
	            EXECUTE format('ALTER USER agent_observer WITH PASSWORD %L', current_setting('webservices.agent_password'));
	        END IF;
	        -- Create txgateway service user (for tx-gateway and evm-broadcaster services)
	        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = current_setting('webservices.txgateway_user')) THEN
	            EXECUTE format('CREATE USER %I WITH PASSWORD %L', current_setting('webservices.txgateway_user'), current_setting('webservices.txgateway_password'));
	        ELSE
	            EXECUTE format('ALTER USER %I WITH PASSWORD %L', current_setting('webservices.txgateway_user'), current_setting('webservices.txgateway_password'));
	        END IF;
    END
    \$\$;
    -- Keep template databases clean; application databases are created explicitly below.
    \connect template1
    DROP EXTENSION IF EXISTS timescaledb CASCADE;
    \connect postgres
    DROP EXTENSION IF EXISTS timescaledb CASCADE;
    -- Create databases with correct owners (IF NOT EXISTS requires PostgreSQL 9.1+)
    SELECT 'CREATE DATABASE planka OWNER planka'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'planka')\gexec
    SELECT 'CREATE DATABASE langgraph OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'langgraph')\gexec
    SELECT 'CREATE DATABASE synapse OWNER synapse LC_COLLATE ''C'' LC_CTYPE ''C'' TEMPLATE template0'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'synapse')\gexec
    SELECT 'CREATE DATABASE matrix_authentication_service OWNER matrix_authentication_service'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'matrix_authentication_service')\gexec
    SELECT 'CREATE DATABASE grafana OWNER grafana'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'grafana')\gexec
    SELECT 'CREATE DATABASE keycloak OWNER keycloak'
    WHERE LENGTH('$POSTGRES_KEYCLOAK_PASSWORD') > 0
      AND NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
    SELECT 'CREATE DATABASE vaultwarden OWNER vaultwarden'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'vaultwarden')\gexec
    SELECT format('CREATE DATABASE txgateway OWNER %I', '$POSTGRES_TXGATEWAY_USER')
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'txgateway')\gexec
    -- Create sysadmin database for monitoring/admin tools
    SELECT 'CREATE DATABASE sysadmin OWNER $POSTGRES_USER'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sysadmin')\gexec
    -- Create homeassistant database with correct owner
    SELECT CASE
        WHEN LENGTH('$POSTGRES_HOMEASSISTANT_PASSWORD') > 0 THEN
            'CREATE DATABASE homeassistant OWNER homeassistant'
        ELSE
            'CREATE DATABASE homeassistant OWNER $POSTGRES_USER'
        END
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'homeassistant')\gexec
    -- Grant privileges (these are idempotent)
    GRANT ALL PRIVILEGES ON DATABASE planka TO planka;
    GRANT ALL PRIVILEGES ON DATABASE langgraph TO $POSTGRES_USER;
    GRANT ALL PRIVILEGES ON DATABASE synapse TO synapse;
    GRANT ALL PRIVILEGES ON DATABASE matrix_authentication_service TO matrix_authentication_service;
    GRANT ALL PRIVILEGES ON DATABASE grafana TO grafana;
    SELECT 'GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak'
    WHERE LENGTH('$POSTGRES_KEYCLOAK_PASSWORD') > 0\gexec
    GRANT ALL PRIVILEGES ON DATABASE vaultwarden TO vaultwarden;
    SELECT format('GRANT ALL PRIVILEGES ON DATABASE txgateway TO %I', '$POSTGRES_TXGATEWAY_USER')\gexec
    -- Shadow agent accounts are granted CONNECT via obsolete/scripts/security/provision-shadow-database-access.sh
    -- Each {username}-agent gets CONNECT on safe databases only (grafana, planka)
    -- SECURITY: Per-user shadow accounts enable audit traceability and limited blast radius
    -- Explicitly DENY access to sensitive databases
    -- (revoke is redundant but explicit for documentation)
    -- vaultwarden: passwords/secrets
    -- keycloak: auth sessions/tokens
    -- synapse: private messages
    -- openwebui: conversation history
EOSQL
if [ -n "$POSTGRES_HOMEASSISTANT_PASSWORD" ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO homeassistant;"
else
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "homeassistant" -c "GRANT ALL ON SCHEMA public TO $POSTGRES_USER;"
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "planka" -c "GRANT ALL ON SCHEMA public TO planka;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "synapse" -c "GRANT ALL ON SCHEMA public TO synapse;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "matrix_authentication_service" -c "GRANT ALL ON SCHEMA public TO matrix_authentication_service;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "grafana" -c "GRANT ALL ON SCHEMA public TO grafana;"
if [ -n "$POSTGRES_KEYCLOAK_PASSWORD" ]; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "keycloak" -c "GRANT ALL ON SCHEMA public TO keycloak;"
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "vaultwarden" -c "GRANT ALL ON SCHEMA public TO vaultwarden;"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "txgateway" -c "GRANT ALL ON SCHEMA public TO \"$POSTGRES_TXGATEWAY_USER\";"
for db in grafana planka; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
        -- Create dedicated schema for observer views
        CREATE SCHEMA IF NOT EXISTS agent_observer;
        -- Grant CONNECT to agent_observer (global fallback account)
        GRANT CONNECT ON DATABASE $db TO agent_observer;
        -- Grant USAGE on agent_observer schema
        GRANT USAGE ON SCHEMA agent_observer TO agent_observer;
        -- Grant SELECT on all tables in agent_observer schema
        GRANT SELECT ON ALL TABLES IN SCHEMA agent_observer TO agent_observer;
        -- Grant SELECT on future tables (PostgreSQL 9.0+)
        ALTER DEFAULT PRIVILEGES IN SCHEMA agent_observer GRANT SELECT ON TABLES TO agent_observer;
        -- NOTE: Individual views must be created by running create-observer-views.sql
        -- after applications have initialized their schemas
        -- This is a manual step to ensure safety
EOSQL
done
echo "PostgreSQL databases and users initialized successfully"
