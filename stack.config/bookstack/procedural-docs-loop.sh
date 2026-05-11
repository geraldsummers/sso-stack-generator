#!/usr/bin/env bash
set -euo pipefail

INTERVAL_SECONDS="${BOOKSTACK_PROCEDURAL_DOC_REFRESH_SECONDS:-300}"

while true; do
    php /config/bookstack/publish-procedural-docs.php || true
    sleep "$INTERVAL_SECONDS"
done
