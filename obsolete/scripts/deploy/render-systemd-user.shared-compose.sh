#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LIB_DIR="$(cd "$SCRIPT_DIR/../lib" && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=scripts/lib/runtime-state.sh
source "$LIB_DIR/runtime-state.sh"

BUNDLE_ROOT=""
RUNTIME_ENV_FILE=""
PROJECT_NAME="${PROJECT_NAME:-webservices}"
OUTPUT_DIR=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./scripts/deploy/render-systemd-user.sh --bundle-root <path> --runtime-env-file <path> [--output-dir <path>]
EOF_USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --bundle-root)
      BUNDLE_ROOT="$2"
      shift
      ;;
    --runtime-env-file)
      RUNTIME_ENV_FILE="$2"
      shift
      ;;
    --project-name)
      PROJECT_NAME="$2"
      shift
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument for render-systemd-user.sh: $1"
      ;;
  esac
  shift
done

[ -n "$BUNDLE_ROOT" ] || die "--bundle-root is required"
[ -n "$RUNTIME_ENV_FILE" ] || die "--runtime-env-file is required"
[ -f "$BUNDLE_ROOT/docker-compose.yml" ] || die "missing docker-compose.yml in $BUNDLE_ROOT"
[ -f "$RUNTIME_ENV_FILE" ] || die "missing runtime env file: $RUNTIME_ENV_FILE"

GRAPH_PATH="$BUNDLE_ROOT/stack.systemd/graph.json"
[ -f "$GRAPH_PATH" ] || die "missing systemd graph source: $GRAPH_PATH"

OUTPUT_DIR="${OUTPUT_DIR:-$BUNDLE_ROOT/systemd-user}"
mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT_DIR"/*.service "$OUTPUT_DIR"/*.target
printf '[webservices-deploy] rendering systemd user units from %s into %s\n' "$GRAPH_PATH" "$OUTPUT_DIR" >&2

require_cmd docker
require_cmd jq

DOCKER_BIN="$(command -v docker)"
SYSTEMD_NOTIFY_BIN="$(command -v systemd-notify)"
[ -n "$SYSTEMD_NOTIFY_BIN" ] || die "systemd-notify is required to render user units"

compose_config_json="$(mktemp)"
trap 'rm -f "$compose_config_json"' EXIT
COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
  "$BUNDLE_ROOT" \
  "$RUNTIME_ENV_FILE" \
  config --format json > "$compose_config_json"

TARGET_NAME="$(jq -r '.target.name' "$GRAPH_PATH")"
TARGET_DESCRIPTION="$(jq -r '.target.description' "$GRAPH_PATH")"
UNIT_PREFIX="$(jq -r '.unitPrefix' "$GRAPH_PATH")"
printf '[webservices-deploy] systemd target: %s (%s)\n' "$TARGET_NAME" "$TARGET_DESCRIPTION" >&2

service_unit_name() {
  local service_name="$1"
  printf '%s-%s.service\n' "$UNIT_PREFIX" "$service_name"
}

service_is_excluded() {
  local service_name="$1"
  jq -e --arg service "$service_name" '.excludedServices // [] | index($service) != null' "$GRAPH_PATH" >/dev/null
}

service_is_target_excluded() {
  local service_name="$1"
  jq -e --arg service "$service_name" '.targetExcludedServices // [] | index($service) != null' "$GRAPH_PATH" >/dev/null
}

service_is_job() {
  local service_name="$1"
  local restart_policy
  restart_policy="$(jq -r --arg service "$service_name" '.services[$service].restart // ""' "$compose_config_json")"
  if [ "$restart_policy" = "no" ]; then
    return 0
  fi

  jq -e --arg service "$service_name" '
    any(
      .services[]
      | (.depends_on // {})
      | to_entries[]?;
      .key == $service and (.value.condition // "service_started") == "service_completed_successfully"
    )
  ' "$compose_config_json" >/dev/null
}

service_has_healthcheck() {
  local service_name="$1"
  jq -e --arg service "$service_name" '.services[$service].healthcheck != null' "$compose_config_json" >/dev/null
}

service_dependency_rows() {
  local service_name="$1"
  jq -r --arg service "$service_name" '
    .services[$service].depends_on // {}
    | to_entries[]?
    | "\(.key)\t\(.value.condition // "service_started")"
  ' "$compose_config_json"
}

service_requires_units() {
  local service_name="$1"
  local dep_name dep_condition
  while IFS=$'\t' read -r dep_name dep_condition; do
    [ -n "$dep_name" ] || continue
    if service_is_excluded "$dep_name"; then
      continue
    fi
    service_unit_name "$dep_name"
  done < <(service_dependency_rows "$service_name")
}

render_target_unit() {
  local target_path="$OUTPUT_DIR/$TARGET_NAME"
  local service_name unit_name

  {
    printf '[Unit]\n'
    printf 'Description=%s\n' "$TARGET_DESCRIPTION"
    while IFS= read -r service_name; do
      [ -n "$service_name" ] || continue
      if service_is_excluded "$service_name" || service_is_target_excluded "$service_name"; then
        continue
      fi
      unit_name="$(service_unit_name "$service_name")"
      printf 'Wants=%s\n' "$unit_name"
    done < <(jq -r '.services | keys[]' "$compose_config_json" | sort)

    printf '\n[Install]\n'
    printf 'WantedBy=default.target\n'
  } > "$target_path"
}

render_job_unit() {
  local service_name="$1"
  local unit_name="$2"
  local target_member="$3"
  local unit_path="$OUTPUT_DIR/$unit_name"
  local dep_units=()
  local dep_unit

  while IFS= read -r dep_unit; do
    [ -n "$dep_unit" ] || continue
    dep_units+=("$dep_unit")
  done < <(service_requires_units "$service_name")

  {
    printf '[Unit]\n'
    printf 'Description=Web Services bootstrap job (%s)\n' "$service_name"
    for dep_unit in "${dep_units[@]}"; do
      printf 'Requires=%s\n' "$dep_unit"
      printf 'After=%s\n' "$dep_unit"
    done
    if [ "$target_member" = "1" ]; then
      printf 'PartOf=%s\n' "$TARGET_NAME"
    fi

    printf '\n[Service]\n'
    printf 'Type=oneshot\n'
    printf 'RemainAfterExit=yes\n'
    printf 'WorkingDirectory=%s\n' "$BUNDLE_ROOT"
    printf 'TimeoutStartSec=1800\n'
    printf "ExecStart=/bin/bash -lc 'set -euo pipefail; compose() { %s compose --project-name %s --env-file %s -f %s/docker-compose.yml \"\$@\"; }; service_name=%s; compose rm -f -s \"\$service_name\" >/dev/null 2>&1 || true; compose up --force-recreate --abort-on-container-exit --exit-code-from \"\$service_name\" --no-deps \"\$service_name\"'\n" \
      "$DOCKER_BIN" "$PROJECT_NAME" "$RUNTIME_ENV_FILE" "$BUNDLE_ROOT" "$service_name"
    printf "ExecStop=/bin/true\n"
    printf "ExecStopPost=/bin/bash -lc '%s compose --project-name %s --env-file %s -f %s/docker-compose.yml rm -f -s %s >/dev/null 2>&1 || true'\n" \
      "$DOCKER_BIN" "$PROJECT_NAME" "$RUNTIME_ENV_FILE" "$BUNDLE_ROOT" "$service_name"
    printf 'Restart=no\n'
  } > "$unit_path"
}

render_service_unit() {
  local service_name="$1"
  local unit_name="$2"
  local target_member="$3"
  local unit_path="$OUTPUT_DIR/$unit_name"
  local need_health=0
  local dep_units=()
  local dep_unit

  if service_has_healthcheck "$service_name"; then
    need_health=1
  fi

  while IFS= read -r dep_unit; do
    [ -n "$dep_unit" ] || continue
    dep_units+=("$dep_unit")
  done < <(service_requires_units "$service_name")

  {
    printf '[Unit]\n'
    printf 'Description=Web Services container (%s)\n' "$service_name"
    for dep_unit in "${dep_units[@]}"; do
      printf 'Requires=%s\n' "$dep_unit"
      printf 'After=%s\n' "$dep_unit"
    done
    if [ "$target_member" = "1" ]; then
      printf 'PartOf=%s\n' "$TARGET_NAME"
    fi

    printf '\n[Service]\n'
    printf 'Type=notify\n'
    printf 'NotifyAccess=all\n'
    printf 'WorkingDirectory=%s\n' "$BUNDLE_ROOT"
    printf 'KillMode=none\n'
    printf 'Restart=on-failure\n'
    printf 'RestartSec=5s\n'
    printf 'TimeoutStartSec=900\n'
    printf 'TimeoutStopSec=120\n'
    printf "ExecStart=/bin/bash -lc 'set -euo pipefail; compose() { %s compose --project-name %s --env-file %s -f %s/docker-compose.yml \"\$@\"; }; service_name=%s; need_health=%s; compose up -d --force-recreate --no-deps \"\$service_name\" >/dev/null; ready=0; for attempt in \$(seq 1 300); do cid=\"\$(compose ps -q \"\$service_name\" | head -n 1)\"; if [ -z \"\$cid\" ]; then sleep 2; continue; fi; state=\"\$(%s inspect -f \"{{.State.Status}}\" \"\$cid\" 2>/dev/null || true)\"; health=\"\$(%s inspect -f \"{{if .State.Health}}{{.State.Health.Status}}{{end}}\" \"\$cid\" 2>/dev/null || true)\"; if [ \"\$state\" = \"running\" ] && { [ \"\$need_health\" = \"0\" ] || [ \"\$health\" = \"healthy\" ]; }; then %s --ready --status=\"\$service_name running\"; ready=1; break; fi; if [ \"\$state\" = \"exited\" ] || [ \"\$state\" = \"dead\" ]; then %s logs --tail 120 \"\$cid\" >&2 || true; exit 1; fi; sleep 2; done; [ \"\$ready\" = \"1\" ] || exit 1; while true; do cid=\"\$(compose ps -q \"\$service_name\" | head -n 1)\"; [ -n \"\$cid\" ] || exit 1; state=\"\$(%s inspect -f \"{{.State.Status}}\" \"\$cid\" 2>/dev/null || true)\"; health=\"\$(%s inspect -f \"{{if .State.Health}}{{.State.Health.Status}}{{end}}\" \"\$cid\" 2>/dev/null || true)\"; if [ \"\$state\" != \"running\" ]; then exit 1; fi; if [ \"\$need_health\" = \"1\" ] && [ \"\$health\" = \"unhealthy\" ]; then exit 1; fi; sleep 5; done'\n" \
      "$DOCKER_BIN" "$PROJECT_NAME" "$RUNTIME_ENV_FILE" "$BUNDLE_ROOT" "$service_name" "$need_health" "$DOCKER_BIN" "$DOCKER_BIN" "$SYSTEMD_NOTIFY_BIN" "$DOCKER_BIN" "$DOCKER_BIN" "$DOCKER_BIN"
    printf "ExecStop=%s compose --project-name %s --env-file %s -f %s/docker-compose.yml stop %s\n" \
      "$DOCKER_BIN" "$PROJECT_NAME" "$RUNTIME_ENV_FILE" "$BUNDLE_ROOT" "$service_name"
    printf "ExecReload=%s compose --project-name %s --env-file %s -f %s/docker-compose.yml restart %s\n" \
      "$DOCKER_BIN" "$PROJECT_NAME" "$RUNTIME_ENV_FILE" "$BUNDLE_ROOT" "$service_name"
  } > "$unit_path"
}

render_target_unit

while IFS= read -r service_name; do
  [ -n "$service_name" ] || continue
  if service_is_excluded "$service_name"; then
    printf '[webservices-deploy] skipping excluded service %s\n' "$service_name" >&2
    continue
  fi

  unit_name="$(service_unit_name "$service_name")"
  target_member=1
  if service_is_target_excluded "$service_name"; then
    target_member=0
  fi

  if service_is_job "$service_name"; then
    printf '[webservices-deploy] rendering oneshot unit %s for service %s (targetMember=%s)\n' "$unit_name" "$service_name" "$target_member" >&2
    render_job_unit "$service_name" "$unit_name" "$target_member"
  else
    printf '[webservices-deploy] rendering long-running unit %s for service %s (targetMember=%s)\n' "$unit_name" "$service_name" "$target_member" >&2
    render_service_unit "$service_name" "$unit_name" "$target_member"
  fi
done < <(jq -r '.services | keys[]' "$compose_config_json" | sort)

printf '[webservices-deploy] rendered target unit %s and %s service units\n' \
  "$TARGET_NAME" \
  "$(find "$OUTPUT_DIR" -maxdepth 1 -type f -name "${UNIT_PREFIX}-*.service" | wc -l | awk '{print $1}')" >&2
