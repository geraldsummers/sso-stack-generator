#!/usr/bin/env bash
set -euo pipefail
project="agent-fixture-${PPID}-$$"
docker_cmd=(docker)
if ! docker info >/dev/null 2>&1; then
    if sudo -n docker info >/dev/null 2>&1; then
        docker_cmd=(sudo -n docker)
    else
        echo "docker-compose fixture: Docker daemon is unavailable to both current user and sudo" >&2
        exit 1
    fi
fi
trap '"${docker_cmd[@]}" compose -p "$project" down --remove-orphans >/dev/null 2>&1 || true' EXIT
"${docker_cmd[@]}" compose -p "$project" up --abort-on-container-exit --quiet-pull > compose.log 2>&1 || {
    cat compose.log >&2
    exit 1
}
grep -q 'DOCKER_COMPOSE_OK' compose.log
