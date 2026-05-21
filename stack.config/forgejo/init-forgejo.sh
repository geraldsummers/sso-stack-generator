#!/bin/bash
set -e
export FORGEJO_WORK_DIR=/data/gitea
export FORGEJO_CUSTOM=/data/gitea
BOOTSTRAP_PASSWORD_FILE="${FORGEJO_BOOTSTRAP_PASSWORD_FILE:-/data/gitea/conf/bootstrap-api-user-password}"

run_forgejo() {
    if command -v gosu >/dev/null 2>&1; then
        gosu git env FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea \
            forgejo --config /data/gitea/conf/app.ini "$@"
        return $?
    fi
    if command -v su-exec >/dev/null 2>&1; then
        su-exec git env FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea \
            forgejo --config /data/gitea/conf/app.ini "$@"
        return $?
    fi
    su -s /bin/sh git -c \
        'exec env FORGEJO_WORK_DIR=/data/gitea FORGEJO_CUSTOM=/data/gitea forgejo --config /data/gitea/conf/app.ini "$@"' \
        -- "$@"
}

retry_command() {
    local attempts="$1"
    local delay_seconds="$2"
    local description="$3"
    shift 3

    for i in $(seq 1 "$attempts"); do
        if "$@"; then
            return 0
        fi
        echo "${description} failed (attempt ${i}/${attempts}), retrying in ${delay_seconds}s..."
        sleep "$delay_seconds"
    done

    echo "${description} failed after ${attempts} attempts."
    return 1
}

check_url() {
    local url="$1"

    if command -v curl >/dev/null 2>&1; then
        curl -fsS --max-time 5 "$url" >/dev/null
        return $?
    fi

    if command -v wget >/dev/null 2>&1; then
        wget --quiet --tries=1 --timeout=5 -O /dev/null "$url"
        return $?
    fi

    return 1
}

forgejo_api() {
    local method="$1"
    local path="$2"
    local payload="${3:-}"
    local token="$4"
    local url="http://127.0.0.1:3000${path}"

    if command -v curl >/dev/null 2>&1; then
        if [ -n "$payload" ]; then
            curl -fsS --max-time 10 \
                -X "$method" \
                -H "Authorization: token $token" \
                -H 'Content-Type: application/json' \
                -d "$payload" \
                "$url"
        else
            curl -fsS --max-time 10 \
                -X "$method" \
                -H "Authorization: token $token" \
                "$url"
        fi
        return $?
    fi

    echo "curl is required for Forgejo API bootstrap" >&2
    return 1
}

generate_random_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 48
        return 0
    fi

    tr -dc 'A-Za-z0-9_@%+=:,./-' </dev/urandom | head -c 72
    printf '\n'
}

ensure_bootstrap_password() {
    local password_dir
    password_dir="$(dirname "$BOOTSTRAP_PASSWORD_FILE")"
    mkdir -p "$password_dir"

    if [ -s "$BOOTSTRAP_PASSWORD_FILE" ]; then
        cat "$BOOTSTRAP_PASSWORD_FILE"
        return 0
    fi

    umask 077
    generate_random_password > "$BOOTSTRAP_PASSWORD_FILE"
    chown git:git "$BOOTSTRAP_PASSWORD_FILE" 2>/dev/null || true
    chmod 600 "$BOOTSTRAP_PASSWORD_FILE" 2>/dev/null || true
    cat "$BOOTSTRAP_PASSWORD_FILE"
}

wait_for_forgejo_schema() {
    echo "Waiting for Forgejo user schema to be ready..."
    for i in $(seq 1 120); do
        if run_forgejo admin user list >/dev/null 2>&1; then
            echo "Forgejo user schema is ready."
            return 0
        fi
        echo "Forgejo schema not ready yet (attempt $i/120), waiting..."
        sleep 2
    done
    echo "Forgejo schema did not become ready in time."
    return 1
}

ensure_forgejo_api_user() {
    local api_username="${FORGEJO_USERNAME:-${FORGEJO_API_USERNAME:-${STACK_ADMIN_USER:-sysadmin}}}"
    local api_email="${FORGEJO_EMAIL:-${FORGEJO_API_EMAIL:-${STACK_ADMIN_EMAIL:-admin@webservices.net}}}"
    local api_password
    api_password="$(ensure_bootstrap_password)"

    for i in $(seq 1 40); do
        if run_forgejo admin user list 2>/dev/null | awk 'NR>1 { print $2 }' | grep -Fxq "$api_username"; then
            echo "Forgejo API user '$api_username' already exists, rotating local password to the Forgejo bootstrap secret..."
            if run_forgejo admin user change-password \
                --username "$api_username" \
                --password "$api_password" \
                --must-change-password=false; then
                return 0
            fi
            echo "Unable to refresh password for '$api_username' (attempt $i/40), retrying..."
            sleep 2
            continue
        fi

        echo "Creating Forgejo API user '$api_username'..."
        if run_forgejo admin user create \
            --username "$api_username" \
            --password "$api_password" \
            --email "$api_email" \
            --admin \
            --must-change-password=false; then
            return 0
        fi
        echo "Unable to create user '$api_username' (attempt $i/40), retrying..."
        sleep 2
    done

    echo "Failed to ensure Forgejo API user '$api_username'."
    return 1
}

wait_for_forgejo_http() {
    echo "Waiting for Forgejo HTTP API..."
    for i in $(seq 1 120); do
        if check_url "http://127.0.0.1:3000/api/healthz"; then
            echo "Forgejo HTTP API is ready."
            return 0
        fi
        echo "Forgejo HTTP API not ready yet (attempt $i/120), waiting..."
        sleep 2
    done

    echo "Forgejo HTTP API did not become ready in time."
    return 1
}

generate_forgejo_access_token() {
    local api_username="$1"
    local token_name="$2"
    local token_scopes="${FORGEJO_SEED_TOKEN_SCOPES:-write:repository,write:user}"

    run_forgejo admin user generate-access-token \
        --username "$api_username" \
        --token-name "$token_name" \
        --raw \
        --scopes "$token_scopes"
}

revoke_forgejo_access_token() {
    local api_username="$1"
    local token_name="$2"
    local token="$3"

    if [ -z "$token_name" ] || [ -z "$token" ]; then
        return 0
    fi

    if forgejo_api DELETE "/api/v1/users/$api_username/tokens/$token_name" "" "$token" >/dev/null 2>&1; then
        echo "Revoked temporary Forgejo seed token '$token_name'."
    else
        echo "Warning: unable to revoke temporary Forgejo seed token '$token_name'; check Forgejo token cleanup." >&2
    fi
}

write_git_askpass() {
    local askpass_path="$1"
    local username="$2"
    local token="$3"

    umask 077
    cat > "$askpass_path" <<EOF
#!/bin/sh
case "\$1" in
  *Username*) printf '%s\n' "$username" ;;
  *) printf '%s\n' "$token" ;;
esac
EOF
    chmod 700 "$askpass_path"
}

ensure_seed_repo() {
    local api_username="${FORGEJO_USERNAME:-${FORGEJO_API_USERNAME:-${STACK_ADMIN_USER:-sysadmin}}}"
    local repo_name="${FORGEJO_SEED_REPO_NAME:-sso-stack-generator}"
    local seed_repo_path="${FORGEJO_SEED_REPO_PATH:-/seed-repos/source/sso-stack-generator.git}"
    local temp_root=""
    local mirror_dir=""
    local askpass_path=""
    local token_name=""
    local token=""

    if [ ! -d "$seed_repo_path" ]; then
        echo "No bundled Forgejo seed repo at $seed_repo_path; skipping repo bootstrap."
        return 0
    fi

    wait_for_forgejo_http

    token_name="bootstrap-seed-repo-$(date +%s)-$$"
    token="$(generate_forgejo_access_token "$api_username" "$token_name")"
    if [ -z "$token" ]; then
        echo "Failed to generate Forgejo access token for '$api_username'."
        return 1
    fi

    cleanup_seed_repo() {
        local status=$?
        trap - RETURN
        if [ -n "$temp_root" ]; then
            rm -rf "$temp_root"
        fi
        revoke_forgejo_access_token "$api_username" "$token_name" "$token" || true
        return "$status"
    }
    trap cleanup_seed_repo RETURN

    if forgejo_api GET "/api/v1/repos/$api_username/$repo_name" "" "$token" >/dev/null 2>&1; then
        echo "Forgejo seed repo '$api_username/$repo_name' already exists."
        return 0
    fi

    echo "Creating Forgejo seed repo '$api_username/$repo_name'..."
    forgejo_api POST "/api/v1/user/repos" \
        "{\"name\":\"$repo_name\",\"private\":true,\"auto_init\":false,\"default_branch\":\"main\",\"description\":\"Bundled webservices source snapshot\"}" \
        "$token" >/dev/null

    temp_root="$(mktemp -d)"
    mirror_dir="$temp_root/repo.git"
    askpass_path="$temp_root/git-askpass.sh"

    git clone --mirror "$seed_repo_path" "$mirror_dir" >/dev/null 2>&1
    write_git_askpass "$askpass_path" "$api_username" "$token"
    GIT_ASKPASS="$askpass_path" GIT_TERMINAL_PROMPT=0 \
        git -C "$mirror_dir" push --mirror "http://$api_username@127.0.0.1:3000/$api_username/$repo_name.git" >/dev/null 2>&1
    git -C "$mirror_dir" remote set-head origin -a >/dev/null 2>&1 || true

    echo "Forgejo seed repo '$api_username/$repo_name' bootstrapped successfully."
}

echo "Waiting for Forgejo to be ready..."
for i in $(seq 1 60); do
    if run_forgejo admin auth list >/dev/null 2>&1; then
        echo "Forgejo is ready."
        break
    fi
    echo "Forgejo not ready yet (attempt $i/60), waiting..."
    sleep 2
done

retry_command 3 2 "Forgejo CLI readiness" run_forgejo admin auth list
wait_for_forgejo_schema
ensure_forgejo_api_user
ensure_seed_repo

echo "Waiting for Keycloak OIDC discovery endpoint..."
KEYCLOAK_DISCOVERY_URL="https://keycloak.${DOMAIN}/realms/webservices/.well-known/openid-configuration"
keycloak_ready=0
for i in $(seq 1 60); do
    if check_url "$KEYCLOAK_DISCOVERY_URL"; then
        echo "Keycloak discovery endpoint is available."
        keycloak_ready=1
        break
    fi
    echo "Keycloak not ready yet (attempt $i/60), waiting..."
    sleep 2
done
if [ "$keycloak_ready" -ne 1 ]; then
    echo "Keycloak discovery endpoint did not become available in time."
    exit 1
fi

configure_keycloak_auth_source() {
    local action="$1"
    local auth_id="${2:-}"
    local source_name="${3:-Keycloak}"
    local command_args=(
        --name "$source_name"
        --provider 'openidConnect'
        --key 'forgejo'
        --secret "${FORGEJO_OAUTH_SECRET}"
        --auto-discover-url "${KEYCLOAK_DISCOVERY_URL}"
        --scopes openid
        --scopes profile
        --scopes email
        --scopes groups
        --group-claim-name 'groups'
        --restricted-group ''
        --skip-local-2fa
    )

    if [ "$action" = "update-oauth" ]; then
        command_args=(--id "$auth_id" "${command_args[@]}")
    fi

    run_forgejo admin auth "$action" "${command_args[@]}"
}

migrate_stale_authelia_auth_sources() {
    local stale_ids
    stale_ids="$(run_forgejo admin auth list 2>/dev/null | awk '$2 == "Authelia" { print $1 }')"
    if [ -z "$stale_ids" ]; then
        return 0
    fi

    echo "Migrating stale Authelia authentication sources to Keycloak in Forgejo..."
    for auth_id in $stale_ids; do
        local source_name="Keycloak"
        local duplicate_keycloak_id
        duplicate_keycloak_id="$(run_forgejo admin auth list 2>/dev/null | awk -v migrated_id="$auth_id" '$2 == "Keycloak" && $1 != migrated_id { print $1; exit }')"
        if [ -n "$duplicate_keycloak_id" ]; then
            if run_forgejo admin auth delete --id "$duplicate_keycloak_id"; then
                echo "Removed duplicate Keycloak auth source id=$duplicate_keycloak_id before in-place migration."
            else
                echo "Warning: duplicate Keycloak auth source id=$duplicate_keycloak_id is in use; preserving it and using a distinct migrated source name." >&2
                source_name="Keycloak migrated"
            fi
        fi

        if configure_keycloak_auth_source update-oauth "$auth_id" "$source_name"; then
            echo "Migrated Forgejo auth source id=$auth_id from Authelia to $source_name."
        else
            echo "Failed to migrate stale Authelia auth source id=$auth_id"
            return 1
        fi
    done
}

migrate_stale_authelia_auth_sources

existing_auth_id="$(run_forgejo admin auth list 2>/dev/null | awk '$2 == "Keycloak" { print $1; exit }')"
if [ -n "$existing_auth_id" ]; then
    echo "Updating Keycloak OIDC authentication source..."
    if configure_keycloak_auth_source update-oauth "$existing_auth_id"; then
        echo "Forgejo OIDC configuration updated successfully!"
        echo "Users can now sign in with the 'Sign in with Keycloak' button."
        exit 0
    fi
    echo "Failed to update existing Keycloak OIDC auth source."
    exit 1
fi

echo "Adding Keycloak OIDC authentication source to Forgejo..."
for i in $(seq 1 30); do
    if configure_keycloak_auth_source add-oauth; then
        echo "Forgejo OIDC configuration completed successfully!"
        echo "Users can now sign in with the 'Sign in with Keycloak' button."
        exit 0
    fi

    if run_forgejo admin auth list 2>/dev/null | grep -q "Keycloak"; then
        echo "Keycloak OIDC authentication source already exists."
        echo "Users can now sign in with the 'Sign in with Keycloak' button."
        exit 0
    fi

    echo "Failed to add Keycloak OIDC auth source (attempt $i/30), retrying..."
    sleep 2
done

echo "Failed to configure Keycloak OIDC source in Forgejo."
exit 1
