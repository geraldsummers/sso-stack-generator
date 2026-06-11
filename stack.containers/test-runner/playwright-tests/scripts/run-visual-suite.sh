#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PLAYWRIGHT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

require_container_health() {
  service_name="$1"
  project_name="${TEST_RUNNER_COMPOSE_PROJECT_NAME:-${COMPOSE_PROJECT_NAME:-webservices}}"
  container_name="${2:-}"

  if [ -z "$container_name" ]; then
    container_name=$(docker ps \
      --filter "label=com.docker.compose.project=${project_name}" \
      --filter "label=com.docker.compose.service=${service_name}" \
      --format '{{.Names}}' \
      | head -n 1)
  fi
  if [ -z "$container_name" ]; then
    container_name="$service_name"
  fi

  if ! inspect_output=$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_name" 2>/dev/null); then
    printf 'missing:%s\n' "$service_name"
    return 1
  fi

  container_status=$(printf '%s' "$inspect_output" | awk '{print $1}')
  health_status=$(printf '%s' "$inspect_output" | awk '{print $2}')

  if [ "$container_status" != "running" ]; then
    printf 'stopped:%s:%s\n' "$service_name" "$container_status"
    return 1
  fi

  if [ "$health_status" != "none" ] && [ "$health_status" != "healthy" ]; then
    printf 'unhealthy:%s:%s\n' "$service_name" "$health_status"
    return 1
  fi

  return 0
}

preflight_visual_stack() {
  missing_report=""

  for service_name in \
    caddy \
    keycloak \
    keycloak-auth-gateway \
    alertmanager \
    portal \
    bookstack \
    chatgpt-connector \
    sogo \
    jellyfin \
    donetick \
    erpnext \
    erpnext-backend \
    forgejo \
    grafana \
    homeassistant \
    jupyterhub \
    kopia \
    mastodon-web \
    mastodon-sidekiq \
    ntfy \
    planka \
    progression \
    prometheus \
    opensearch \
    seafile \
    synapse \
    element \
    vaultwarden \
    workspace-provisioner
  do
    if ! status_line=$(require_container_health "$service_name"); then
      if [ -n "$missing_report" ]; then
        missing_report="${missing_report}\n"
      fi
      missing_report="${missing_report}${status_line}"
    fi
  done

  if [ "${TESTDEV_SKIP_GPU_INGESTION:-0}" != "1" ]; then
    for service_name in airflow-webserver airflow-scheduler ingestion-runner; do
      if ! status_line=$(require_container_health "$service_name"); then
        if [ -n "$missing_report" ]; then
          missing_report="${missing_report}\n"
        fi
        missing_report="${missing_report}${status_line}"
      fi
    done
  fi

  if [ -n "$missing_report" ]; then
    printf 'Visual suite preflight failed. Required local containers are missing or unhealthy:\n' >&2
    printf '%b\n' "$missing_report" >&2
    exit 1
  fi
}

cd "$PLAYWRIGHT_DIR"
preflight_visual_stack

set -- \
  tests/visual \
  tests/deep/forward-auth/homeassistant.spec.ts \
  tests/deep/forward-auth/alertmanager.spec.ts \
  tests/deep/forward-auth/jupyterhub.spec.ts \
  tests/deep/forward-auth/kopia.spec.ts \
  tests/deep/forward-auth/ntfy.spec.ts \
  tests/deep/forward-auth/prometheus.spec.ts \
  tests/deep/forward-auth/search.spec.ts \
  tests/deep/forward-auth/seafile.spec.ts \
  tests/deep/forward-auth/onboarding.spec.ts \
  tests/deep/forward-auth/workspaces.spec.ts \
  tests/deep/oidc/element.spec.ts \
  tests/deep/oidc/mastodon.spec.ts \
  tests/deep/oidc/planka.spec.ts \
  tests/deep/oidc/vaultwarden.spec.ts

if [ "${TESTDEV_SKIP_GPU_INGESTION:-0}" != "1" ]; then
  set -- "$@" tests/deep/forward-auth/pipeline.spec.ts
fi

exec npx playwright test "$@"
