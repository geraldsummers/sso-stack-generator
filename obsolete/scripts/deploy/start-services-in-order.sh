#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LIB_DIR="$(cd "$SCRIPT_DIR/../lib" && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=scripts/lib/runtime-state.sh
source "$LIB_DIR/runtime-state.sh"

BUNDLE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
RUNTIME_ENV_FILE=""
PROJECT_NAME="${PROJECT_NAME:-webservices}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-900}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./scripts/deploy/start-services-in-order.sh --bundle-root <path> --runtime-env-file <path>
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
    --timeout-seconds)
      TIMEOUT_SECONDS="$2"
      shift
      ;;
    --interval-seconds)
      INTERVAL_SECONDS="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument for start-services-in-order.sh: $1"
      ;;
  esac
  shift
done

[ -n "$RUNTIME_ENV_FILE" ] || die "--runtime-env-file is required"
[ -f "$BUNDLE_ROOT/docker-compose.yml" ] || die "missing docker-compose.yml in $BUNDLE_ROOT"
[ -f "$RUNTIME_ENV_FILE" ] || die "missing runtime env file: $RUNTIME_ENV_FILE"

require_cmd docker
require_cmd jq

deploy_log() {
  printf '[webservices-deploy] %s\n' "$*" >&2
}

compose_cmd() {
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle "$BUNDLE_ROOT" "$RUNTIME_ENV_FILE" "$@"
}

compose_config_json() {
  compose_cmd config --format json
}

compose_ps_json() {
  compose_cmd ps -a --format json | jq -s '.'
}

compose_service_state() {
  local compose_ps="$1"
  local service_name="$2"
  printf '%s\n' "$compose_ps" | jq -r --arg service "$service_name" '([.[] | select(.Service == $service)][0].State) // "missing"'
}

compose_service_health() {
  local compose_ps="$1"
  local service_name="$2"
  printf '%s\n' "$compose_ps" | jq -r --arg service "$service_name" '([.[] | select(.Service == $service)][0].Health) // ""'
}

compose_service_exit_code() {
  local compose_ps="$1"
  local service_name="$2"
  printf '%s\n' "$compose_ps" | jq -r --arg service "$service_name" '([.[] | select(.Service == $service)][0].ExitCode) // ""'
}

service_dependency_rows() {
  local compose_config="$1"
  local service_name="$2"
  printf '%s\n' "$compose_config" | jq -r --arg service "$service_name" '
    .services[$service].depends_on // {}
    | to_entries[]
    | "\(.key)\t\(.value.condition // "service_started")"
  '
}

service_is_eligible() {
  local compose_config="$1"
  local compose_ps="$2"
  local service_name="$3"
  local dep_name dep_condition dep_state dep_health dep_exit

  while IFS=$'\t' read -r dep_name dep_condition; do
    [ -n "$dep_name" ] || continue
    dep_state="$(compose_service_state "$compose_ps" "$dep_name")"
    dep_health="$(compose_service_health "$compose_ps" "$dep_name")"
    dep_exit="$(compose_service_exit_code "$compose_ps" "$dep_name")"
    case "$dep_condition" in
      service_healthy)
        if [ "$dep_state" != "running" ] || [ "$dep_health" != "healthy" ]; then
          return 1
        fi
        ;;
      service_completed_successfully)
        if [ "$dep_state" != "exited" ] || [ "$dep_exit" != "0" ]; then
          return 1
        fi
        ;;
      *)
        if [ "$dep_state" != "running" ] && ! { [ "$dep_state" = "exited" ] && [ "$dep_exit" = "0" ]; }; then
          return 1
        fi
        ;;
    esac
  done < <(service_dependency_rows "$compose_config" "$service_name")

  return 0
}

service_blockers() {
  local compose_config="$1"
  local compose_ps="$2"
  local service_name="$3"
  local dep_name dep_condition dep_state dep_health dep_exit

  while IFS=$'\t' read -r dep_name dep_condition; do
    [ -n "$dep_name" ] || continue
    dep_state="$(compose_service_state "$compose_ps" "$dep_name")"
    dep_health="$(compose_service_health "$compose_ps" "$dep_name")"
    dep_exit="$(compose_service_exit_code "$compose_ps" "$dep_name")"
    case "$dep_condition" in
      service_healthy)
        if [ "$dep_state" != "running" ] || [ "$dep_health" != "healthy" ]; then
          printf '%s:%s%s\n' "$dep_name" "$dep_state" "${dep_health:+/$dep_health}"
        fi
        ;;
      service_completed_successfully)
        if [ "$dep_state" != "exited" ] || [ "$dep_exit" != "0" ]; then
          printf '%s:%s%s\n' "$dep_name" "$dep_state" "${dep_exit:+/$dep_exit}"
        fi
        ;;
      *)
        if [ "$dep_state" != "running" ] && ! { [ "$dep_state" = "exited" ] && [ "$dep_exit" = "0" ]; }; then
          printf '%s:%s%s\n' "$dep_name" "$dep_state" "${dep_exit:+/$dep_exit}"
        fi
        ;;
    esac
  done < <(service_dependency_rows "$compose_config" "$service_name")
}

blockers_are_progressing() {
  local blocker state tail

  for blocker in "$@"; do
    state="${blocker#*:}"
    state="${state%%/*}"
    tail="${blocker#*/}"
    case "$state" in
      missing|created|running|restarting)
        return 0
        ;;
      exited)
        if [ "$tail" = "$blocker" ]; then
          continue
        fi
        continue
        ;;
    esac
  done

  return 1
}

start_time="$(date +%s)"
compose_config="$(compose_config_json)"
mapfile -t remaining_services < <(printf '%s\n' "$compose_config" | jq -r '.services | keys[]')

[ "${#remaining_services[@]}" -gt 0 ] || exit 0

wave=1
while [ "${#remaining_services[@]}" -gt 0 ]; do
  compose_ps="$(compose_ps_json)"
  eligible_services=()
  blocker_messages=()

  for service_name in "${remaining_services[@]}"; do
    if service_is_eligible "$compose_config" "$compose_ps" "$service_name"; then
      eligible_services+=("$service_name")
      continue
    fi

    while IFS= read -r blocker; do
      [ -n "$blocker" ] && blocker_messages+=("$blocker")
    done < <(service_blockers "$compose_config" "$compose_ps" "$service_name")
  done

  if [ "${#eligible_services[@]}" -gt 0 ]; then
    deploy_log "starting wave $wave: ${eligible_services[*]}"
    if [ "$wave" -eq 1 ]; then
      compose_cmd up -d --force-recreate --remove-orphans --no-deps "${eligible_services[@]}"
    else
      compose_cmd up -d --force-recreate --no-deps "${eligible_services[@]}"
    fi

    next_remaining=()
    for service_name in "${remaining_services[@]}"; do
      skip_service=0
      for eligible_service in "${eligible_services[@]}"; do
        if [ "$service_name" = "$eligible_service" ]; then
          skip_service=1
          break
        fi
      done
      if [ "$skip_service" -eq 0 ]; then
        next_remaining+=("$service_name")
      fi
    done
    remaining_services=("${next_remaining[@]}")
    wave="$((wave + 1))"
    sleep "$INTERVAL_SECONDS"
    continue
  fi

  if [ "${#blocker_messages[@]}" -gt 0 ]; then
    mapfile -t blocker_messages < <(printf '%s\n' "${blocker_messages[@]}" | awk 'NF && !seen[$0]++')
  fi

  now="$(date +%s)"
  elapsed="$((now - start_time))"
  if [ "$elapsed" -ge "$TIMEOUT_SECONDS" ]; then
    die "timed out waiting to satisfy service dependencies: $(printf '%s ' "${blocker_messages[@]}")"
  fi

  if [ "${#blocker_messages[@]}" -gt 0 ] && ! blockers_are_progressing "${blocker_messages[@]}"; then
    die "service dependency graph stalled: $(printf '%s ' "${blocker_messages[@]}")"
  fi

  if [ "${#blocker_messages[@]}" -gt 0 ]; then
    deploy_log "waiting for dependencies before next wave: ${blocker_messages[*]}"
  else
    deploy_log "waiting for compose services to appear before next wave"
  fi
  sleep "$INTERVAL_SECONDS"
done
