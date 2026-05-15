#!/bin/bash
set -e
/usr/bin/entrypoint 2> >(grep -v "Read-only file system" >&2) &
FORGEJO_PID=$!
/generate-runner-token.sh &
TOKEN_GEN_PID=$!

(
    while true; do
        if /init-forgejo.sh; then
            exit 0
        fi
        echo "[forgejo-entrypoint] Forgejo bootstrap failed; retrying in 30s" >&2
        sleep 30
    done
) &
OIDC_INIT_PID=$!
wait $FORGEJO_PID
