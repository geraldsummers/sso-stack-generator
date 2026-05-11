#!/bin/bash
set -e
TOKEN_FILE="/runner-token/token"
LOCK_FILE="/runner-token/.token-generated"
TEMP_TOKEN_FILE="$(mktemp)"
RUNNER_UID="${FORGEJO_RUNNER_UID:-1000}"
RUNNER_GID="${FORGEJO_RUNNER_GID:-1000}"

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

echo "[forgejo-entrypoint] Waiting for Forgejo to be fully ready..."
for i in {1..180}; do
    if wget -q --spider http://localhost:3000/api/healthz 2>/dev/null; then
        echo "[forgejo-entrypoint] Forgejo API is ready"
        break
    fi
    if [ $i -eq 180 ]; then
        echo "[forgejo-entrypoint] ⚠️ Timeout waiting for Forgejo API"
        exit 0
    fi
    sleep 2
done
sleep 5
echo "[forgejo-entrypoint] Checking Forgejo admin user status..."
if run_forgejo admin user list 2>/tmp/user-list.err; then
    echo "[forgejo-entrypoint] ✓ Admin user accessible"
else
    echo "[forgejo-entrypoint] ⚠️ Cannot list users (may still be initializing)"
    cat /tmp/user-list.err 2>/dev/null || true
fi

echo "[forgejo-entrypoint] Syncing runner registration token from live Forgejo..."
if run_forgejo actions generate-runner-token > "$TEMP_TOKEN_FILE" 2>/tmp/token-gen.err; then
    if [ -s "$TEMP_TOKEN_FILE" ]; then
        if [ -f "$TOKEN_FILE" ] && cmp -s "$TEMP_TOKEN_FILE" "$TOKEN_FILE"; then
            echo "[forgejo-entrypoint] ✓ Runner token already current"
            rm -f "$TEMP_TOKEN_FILE"
        else
            mv "$TEMP_TOKEN_FILE" "$TOKEN_FILE"
            echo "[forgejo-entrypoint] ✓ Runner token refreshed"
        fi
        chown "$RUNNER_UID:$RUNNER_GID" "$TOKEN_FILE"
        chmod 400 "$TOKEN_FILE"
        touch "$LOCK_FILE"
    else
        echo "[forgejo-entrypoint] ⚠️ Token file is empty"
        rm -f "$TEMP_TOKEN_FILE"
        rm -f "$TOKEN_FILE"
    fi
else
    EXIT_CODE=$?
    rm -f "$TEMP_TOKEN_FILE"
    echo "[forgejo-entrypoint] ⚠️ Failed to sync token via CLI (exit code: $EXIT_CODE)"
    echo "[forgejo-entrypoint] Error output:"
    cat /tmp/token-gen.err 2>/dev/null || echo "  (no error output captured)"
    echo "[forgejo-entrypoint] Checking if actions are enabled in configuration..."
    if run_forgejo admin config list | grep -i "actions.enabled" 2>/dev/null; then
        echo "[forgejo-entrypoint] Actions configuration found"
    else
        echo "[forgejo-entrypoint] ⚠️ Actions may not be properly configured"
    fi
    echo "[forgejo-entrypoint] Manual fix: docker exec -u git forgejo forgejo --config /data/gitea/conf/app.ini actions generate-runner-token > /runner-token/token"
fi
exit 0
