#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
BUNDLE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
DEPLOY_ROOT="$(cd "$BUNDLE_ROOT/.." && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=scripts/lib/site-manifest.sh
source "$SCRIPT_DIR/lib/site-manifest.sh"
# shellcheck source=scripts/lib/runtime-state.sh
source "$SCRIPT_DIR/lib/runtime-state.sh"
# shellcheck source=scripts/lib/systemd-user.sh
source "$SCRIPT_DIR/lib/systemd-user.sh"
# shellcheck source=scripts/lib/components.sh
source "$SCRIPT_DIR/lib/components.sh"

PROJECT_NAME="${PROJECT_NAME:-webservices}"
PREFLIGHT_ONLY=0
PARTIAL_DEPLOY=0
CURRENT_PHASE="initializing"
SYSTEMD_RECONCILE_TIMEOUT_SECONDS="${SYSTEMD_RECONCILE_TIMEOUT_SECONDS:-1800}"
SYSTEMD_PROGRESS_INTERVAL_SECONDS="${SYSTEMD_PROGRESS_INTERVAL_SECONDS:-5}"
SYSTEMD_PROGRESS_MAX_ITEMS="${SYSTEMD_PROGRESS_MAX_ITEMS:-8}"
SYSTEMD_PROGRESS_HEARTBEAT_SECONDS="${SYSTEMD_PROGRESS_HEARTBEAT_SECONDS:-30}"
declare -A BUILT_IMAGE_IDS_BEFORE=()
declare -a SCOPED_COMPONENTS=()
declare -a SCOPED_SERVICES=()
declare -a SCOPED_UNITS=()

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./scripts/deploy.sh [--preflight-only]
  ./scripts/deploy.sh [--component <name> ...] [--service <compose-service> ...] [--unit <systemd-unit-or-domain> ...]

Deploys the in-place bundle under ~/webservices by rendering runtime material into
~/webservices/runtime, installing pre-rendered systemd user units from ./build,
and asking systemd --user to reconcile webservices.target.

Scoped deploys render and install the same bundle, but only reload/start the
selected lifecycle units and the dependency units required by systemd. Use them
for small app/config updates where reconciling the whole webservices.target is
unnecessary.
EOF_USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --preflight-only)
      PREFLIGHT_ONLY=1
      ;;
    --component)
      [ "$#" -ge 2 ] || die "--component requires a value"
      PARTIAL_DEPLOY=1
      SCOPED_COMPONENTS+=("$2")
      shift
      ;;
    --service)
      [ "$#" -ge 2 ] || die "--service requires a value"
      PARTIAL_DEPLOY=1
      SCOPED_SERVICES+=("$2")
      shift
      ;;
    --unit)
      [ "$#" -ge 2 ] || die "--unit requires a value"
      PARTIAL_DEPLOY=1
      SCOPED_UNITS+=("$2")
      shift
      ;;
    *)
      die "unknown argument for deploy.sh: $1"
      ;;
  esac
  shift
done

site_manifest_path="$BUNDLE_ROOT/site/manifest.json"
[ -f "$site_manifest_path" ] || die "missing bundled site manifest: $site_manifest_path"
[ -f "$BUNDLE_ROOT/docker-compose.yml" ] || die "missing bundle compose file: $BUNDLE_ROOT/docker-compose.yml"
[ -d "$BUNDLE_ROOT/systemd-user" ] || die "missing pre-rendered systemd user units in $BUNDLE_ROOT/systemd-user (run build.sh first)"

deploy_log() {
  printf '[webservices-deploy] %s\n' "$*" >&2
}

set_phase() {
  CURRENT_PHASE="$1"
  deploy_log "phase: $CURRENT_PHASE"
}

dump_deploy_diagnostics() {
  local failed_units unit_name
  deploy_log "diagnostics begin (phase=$CURRENT_PHASE)"
  deploy_log "systemd target status"
  user_systemd_show_status webservices.target >&2

  deploy_log "systemd dependency tree"
  user_systemd_list_dependencies webservices.target >&2

  deploy_log "webservices unit inventory"
  user_systemd_list_matching_units >&2

  mapfile -t failed_units < <(user_systemd_failed_units)
  if [ "${#failed_units[@]}" -gt 0 ]; then
    deploy_log "failed user units: ${failed_units[*]}"
    for unit_name in "${failed_units[@]}"; do
      deploy_log "status for failed unit $unit_name"
      user_systemd_show_status "$unit_name" >&2
      deploy_log "recent journal for failed unit $unit_name"
      user_systemd_show_recent_logs "$unit_name" 160 >&2
    done
  else
    deploy_log "no failed user units reported"
  fi

  if [ -f "$DEPLOY_ROOT/runtime/stack.env" ]; then
    deploy_log "docker container snapshot"
    docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' 2>&1 | sort || true
  fi

  deploy_log "diagnostics end"
}

on_deploy_error() {
  local exit_code=$?
  deploy_log "ERROR: deploy failed during phase '$CURRENT_PHASE' with exit code $exit_code"
  dump_deploy_diagnostics
  exit "$exit_code"
}

trap 'on_deploy_error' ERR

preflight() {
  require_cmd docker
  require_cmd jq
  require_cmd python3
  require_cmd sops
  require_cmd systemctl
  docker compose version >/dev/null 2>&1 || die "docker compose plugin is unavailable"
  resolve_site_manifest_file "$site_manifest_path" >/dev/null
  ensure_runtime_links "$DEPLOY_ROOT" >/dev/null
  ensure_user_systemd_env
  deploy_log "preflight ok (bundle=$BUNDLE_ROOT siteManifestPath=$site_manifest_path)"
}

model_prep_services() {
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    config --format json | jq -r '
      .services
      | to_entries[]
      | select(
          (.value.labels // []) as $labels
          | if ($labels | type) == "object" then
              ($labels["webservices.model-prep"] // "false") == "true"
            elif ($labels | type) == "array" then
              any($labels[]; . == "webservices.model-prep=true")
            else
              false
            end
        )
      | .key
    ' | sort
}

built_image_services() {
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    config --format json | jq -r '
      .services
      | to_entries[]
      | select((.value.build // null) != null or ((.value.image // "") | test(":local-build$")))
      | select((.value.image // "") != "")
      | [.key, .value.image]
      | @tsv
    ' | sort
}

image_id_for_ref() {
  local image_ref="$1"
  docker image inspect --format '{{.Id}}' "$image_ref" 2>/dev/null || true
}

container_image_id_for_service() {
  local service_name="$1"
  local container_id
  container_id="$(COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    ps -q "$service_name" 2>/dev/null | head -n 1)"
  [ -n "$container_id" ] || return 0
  docker inspect --format '{{.Image}}' "$container_id" 2>/dev/null || true
}

unit_for_compose_service() {
  local service_name="$1"
  local graph_file="$BUNDLE_ROOT/stack.systemd/graph.json"
  local unit_prefix domain_name

  unit_prefix="$(jq -r '.unitPrefix // "webservices"' "$graph_file")"
  domain_name="$(jq -r --arg service "$service_name" '
    first(
      .lifecycleDomains[]?
      | select(((.services // []) | index($service)) != null)
      | .name
    ) // $service
  ' "$graph_file")"
  printf '%s-%s.service\n' "$unit_prefix" "$domain_name"
}

compose_service_exists() {
  local service_name="$1"
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    config --format json | jq -e --arg service "$service_name" '.services[$service] != null' >/dev/null
}

component_compose_files() {
  local component="$1"
  local catalog="$BUNDLE_ROOT/stack.config/components.json"

  jq -r --arg requested "$component" '
    def walk_component($name):
      if .components[$name] == null then
        error("unknown component: " + $name)
      else
        [$name] + ((.components[$name].dependencies // []) | map(walk_component(.)) | add // [])
      end;

    (walk_component($requested) | unique) as $selected
    | .components
    | keys_unsorted[] as $component
    | select($selected | index($component))
    | .[$component].composeFiles[]?
  ' "$catalog"
}

services_from_compose_file() {
  local compose_file="$1"
  local compose_path="$BUNDLE_ROOT/stack.compose/$compose_file"

  [ -f "$compose_path" ] || die "component references missing compose file: $compose_file"
  docker compose -f "$compose_path" config --format json --no-interpolate | jq -r '.services | keys[]'
}

append_unique() {
  local value="$1"
  shift
  local -n target_array="$1"
  local existing

  [ -n "$value" ] || return 0
  for existing in "${target_array[@]}"; do
    [ "$existing" != "$value" ] || return 0
  done
  target_array+=("$value")
}

resolve_scoped_services() {
  local services=()
  local component compose_file service_name

  for service_name in "${SCOPED_SERVICES[@]}"; do
    append_unique "$service_name" services
  done

  for component in "${SCOPED_COMPONENTS[@]}"; do
    while IFS= read -r compose_file; do
      [ -n "$compose_file" ] || continue
      while IFS= read -r service_name; do
        append_unique "$service_name" services
      done < <(services_from_compose_file "$compose_file")
    done < <(component_compose_files "$component")
  done

  for service_name in "${services[@]}"; do
    if ! compose_service_exists "$service_name"; then
      die "selected compose service is not present in this bundle: $service_name"
    fi
    printf '%s\n' "$service_name"
  done
}

normalize_scoped_unit() {
  local unit="$1"
  local prefix

  prefix="$(jq -r '.unitPrefix // "webservices"' "$BUNDLE_ROOT/stack.systemd/graph.json")"
  case "$unit" in
    *.service|*.target)
      printf '%s\n' "$unit"
      ;;
    "$prefix"-*)
      printf '%s.service\n' "$unit"
      ;;
    *)
      printf '%s-%s.service\n' "$prefix" "$unit"
      ;;
  esac
}

resolve_scoped_units() {
  local units=()
  local service_name unit_name requested_unit

  while IFS= read -r service_name; do
    [ -n "$service_name" ] || continue
    unit_name="$(unit_for_compose_service "$service_name")"
    append_unique "$unit_name" units
  done < <(resolve_scoped_services)

  for requested_unit in "${SCOPED_UNITS[@]}"; do
    unit_name="$(normalize_scoped_unit "$requested_unit")"
    append_unique "$unit_name" units
  done

  [ "${#units[@]}" -gt 0 ] || die "scoped deploy requested, but no service or unit scope resolved"

  for unit_name in "${units[@]}"; do
    [ -f "$BUNDLE_ROOT/systemd-user/$unit_name" ] || die "selected systemd unit is not present in this bundle: $unit_name"
    printf '%s\n' "$unit_name"
  done
}

scoped_health_units() {
  local unit_name health_unit
  while IFS= read -r unit_name; do
    [[ "$unit_name" == *.service ]] || continue
    health_unit="${unit_name%.service}-healthy.service"
    [ -f "$BUNDLE_ROOT/systemd-user/$health_unit" ] || continue
    printf '%s\n' "$health_unit"
  done < <(resolve_scoped_units)
}

reconcile_scoped_units() {
  local units=() health_units=() reload_units=() restart_units=() start_units=()
  local unit_name action_units=()

  mapfile -t units < <(resolve_scoped_units)
  mapfile -t health_units < <(scoped_health_units)

  deploy_log "scoped deploy selected units: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${units[@]}")"

  for unit_name in "${units[@]}"; do
    if [[ "$unit_name" == *.target ]]; then
      start_units+=("$unit_name")
    elif user_systemctl is-active --quiet "$unit_name"; then
      if grep -q '^ExecReload=' "$BUNDLE_ROOT/systemd-user/$unit_name"; then
        reload_units+=("$unit_name")
      else
        restart_units+=("$unit_name")
      fi
    else
      start_units+=("$unit_name")
    fi
  done

  if [ "${#reload_units[@]}" -gt 0 ]; then
    deploy_log "reloading selected active units: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${reload_units[@]}")"
    user_systemctl reload "${reload_units[@]}"
  fi

  if [ "${#restart_units[@]}" -gt 0 ]; then
    deploy_log "restarting selected active units without ExecReload: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${restart_units[@]}")"
    user_systemctl reset-failed "${restart_units[@]}" || true
    user_systemctl restart "${restart_units[@]}"
  fi

  if [ "${#start_units[@]}" -gt 0 ]; then
    deploy_log "starting selected inactive units: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${start_units[@]}")"
    user_systemctl reset-failed "${start_units[@]}" || true
    user_systemctl start "${start_units[@]}"
  fi

  action_units=("${reload_units[@]}" "${restart_units[@]}" "${start_units[@]}")
  if [ "${#action_units[@]}" -gt 0 ]; then
    user_systemctl reset-failed
  fi

  if [ "${#health_units[@]}" -gt 0 ]; then
    deploy_log "waiting on selected healthy gates: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${health_units[@]}")"
    user_systemctl start "${health_units[@]}"
  fi
}

snapshot_built_image_ids_before() {
  local service_name image_ref image_id
  BUILT_IMAGE_IDS_BEFORE=()
  while IFS=$'\t' read -r service_name image_ref; do
    [ -n "$service_name" ] || continue
    image_id="$(image_id_for_ref "$image_ref")"
    BUILT_IMAGE_IDS_BEFORE["$service_name"]="$image_id"
  done < <(built_image_services)
}

run_model_prep_jobs() {
  local service
  mapfile -t prep_services < <(model_prep_services)
  if [ "${#prep_services[@]}" -eq 0 ]; then
    return 0
  fi

  for service in "${prep_services[@]}"; do
    deploy_log "preparing model assets with $service"
    COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
      "$BUNDLE_ROOT" \
      "$DEPLOY_ROOT/runtime/stack.env" \
      run --rm --build --no-deps "$service"
  done
}

excluded_services() {
  jq -r '.excludedServices // [] | .[]' "$BUNDLE_ROOT/stack.systemd/graph.json"
}

cleanup_excluded_service_containers() {
  local service
  mapfile -t excluded < <(excluded_services)
  if [ "${#excluded[@]}" -eq 0 ]; then
    return 0
  fi

  for service in "${excluded[@]}"; do
    deploy_log "stopping excluded service container state for $service"
    COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
      "$BUNDLE_ROOT" \
      "$DEPLOY_ROOT/runtime/stack.env" \
      stop "$service" >/dev/null 2>&1 || true
    COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
      "$BUNDLE_ROOT" \
      "$DEPLOY_ROOT/runtime/stack.env" \
      rm -f -s "$service" >/dev/null 2>&1 || true
  done
}

ensure_bootstrap_scaffolds() {
  local repos_root="$DEPLOY_ROOT/repos"
  mkdir -p "$repos_root/source"
  deploy_log "ensured bootstrap scaffold: $repos_root"
}

runtime_env_value() {
  local key="$1"
  env -i bash -c 'set -a; . "$1"; printf "%s\n" "${!2:-}"' bash "$DEPLOY_ROOT/runtime/stack.env" "$key"
}

migrate_legacy_seafile_split_volume() {
  local volumes_config="$BUNDLE_ROOT/systemd-user/infra/volumes.json"
  local volume_name desired_device actual_driver actual_opts seafile_media_root

  [ -f "$volumes_config" ] || return 0

  volume_name="$(jq -r '.[] | select(.key == "seafile_files") | .name // empty' "$volumes_config")"
  desired_device="$(jq -r '.[] | select(.key == "seafile_files") | .driver_opts.device // empty' "$volumes_config")"
  [ -n "$volume_name" ] || return 0
  [ "$desired_device" = '${SEAFILE_MEDIA_ROOT}' ] || return 0
  docker volume inspect "$volume_name" >/dev/null 2>&1 || return 0

  actual_driver="$(docker volume inspect "$volume_name" -f '{{.Driver}}')"
  actual_opts="$(docker volume inspect "$volume_name" | jq -cS '.[0].Options // {}')"
  [ "$actual_driver" = "local" ] || return 0
  [ "$actual_opts" = "{}" ] || return 0

  seafile_media_root="$(runtime_env_value SEAFILE_MEDIA_ROOT)"
  [ -n "$seafile_media_root" ] || die "SEAFILE_MEDIA_ROOT is empty; refusing Seafile volume migration"
  [ "$seafile_media_root" != "/" ] || die "SEAFILE_MEDIA_ROOT resolved to /; refusing Seafile volume migration"

  deploy_log "migrating legacy Seafile split volume $volume_name into $seafile_media_root"
  user_systemctl stop webservices-seafile.service >/dev/null 2>&1 || true
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    stop seafile >/dev/null 2>&1 || true
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    rm -f -s seafile >/dev/null 2>&1 || true

  mkdir -p "$seafile_media_root"
  docker run --rm \
    -v "$volume_name:/legacy-shared:ro" \
    -v "$seafile_media_root:/target-shared" \
    "$RUNTIME_CLEANUP_IMAGE" \
    sh -ceu '
      cp -a /legacy-shared/. /target-shared/
      mkdir -p /target-shared/seafile/seafile-data/storage
      for name in blocks commits fs httptemp tmp; do
        source="/target-shared/$name"
        dest="/target-shared/seafile/seafile-data/storage/$name"
        [ -e "$source" ] || continue
        mkdir -p "$dest"
        for entry in "$source"/* "$source"/.[!.]* "$source"/..?*; do
          [ -e "$entry" ] || continue
          base="$(basename "$entry")"
          if [ -e "$dest/$base" ]; then
            echo "refusing to overwrite existing Seafile storage entry: $dest/$base" >&2
            exit 1
          fi
          mv "$entry" "$dest/"
        done
        rmdir "$source" 2>/dev/null || true
      done
    '

  docker volume rm "$volume_name" >/dev/null
  deploy_log "legacy Seafile split volume migrated; $volume_name will be recreated as a bind volume"
}

join_array_limited() {
  local max_items="$1"
  shift
  local items=("$@")
  local total="${#items[@]}"
  local limit="$max_items"
  local output=()

  if [ "$limit" -le 0 ]; then
    limit="$total"
  fi
  if [ "$limit" -gt "$total" ]; then
    limit="$total"
  fi

  local index
  for ((index = 0; index < limit; index++)); do
    output+=("${items[$index]}")
  done
  if [ "$total" -gt "$limit" ]; then
    output+=("...+$((total - limit)) more")
  fi

  local joined=""
  local item
  for item in "${output[@]}"; do
    if [ -n "$joined" ]; then
      joined="$joined "
    fi
    joined="${joined}${item}"
  done
  printf '%s\n' "$joined"
}

matching_systemd_jobs() {
  user_systemd_list_matching_jobs_raw | awk '{print $1 ":" $2 "/" $3}'
}

interesting_systemd_units() {
  user_systemd_list_matching_units | awk '
    $3 == "activating" || $3 == "deactivating" || $3 == "failed" || $4 == "failed" {
      print $1 ":" $3 "/" $4
    }
  '
}

unique_unit_names_limited() {
  local limit="$1"
  shift

  local entries=("$@")
  local names=()
  local seen=" "
  local entry unit_name

  for entry in "${entries[@]}"; do
    [ -n "$entry" ] || continue
    unit_name="${entry%%:*}"
    [ -n "$unit_name" ] || continue
    if [[ "$seen" == *" $unit_name "* ]]; then
      continue
    fi
    names+=("$unit_name")
    seen="$seen$unit_name "
    if [ "${#names[@]}" -ge "$limit" ]; then
      break
    fi
  done

  if [ "${#names[@]}" -eq 0 ]; then
    printf 'none\n'
    return
  fi
  printf '%s\n' "$(join_array_limited "$limit" "${names[@]}")"
}

compact_unit_label() {
  local unit_name="$1"
  unit_name="${unit_name#webservices-}"
  unit_name="${unit_name%.service}"
  unit_name="${unit_name%.target}"
  printf '%s\n' "$unit_name"
}

compact_blockers_from_jobs() {
  local limit="$1"
  shift
  local jobs=("$@")
  local non_targets=()
  local targets=()
  local seen=" "
  local job unit_name

  for job in "${jobs[@]}"; do
    [ -n "$job" ] || continue
    unit_name="${job%%:*}"
    [ -n "$unit_name" ] || continue
    if [[ "$seen" == *" $unit_name "* ]]; then
      continue
    fi
    seen="$seen$unit_name "
    if [[ "$unit_name" == *.target ]]; then
      targets+=("$unit_name")
    else
      non_targets+=("$unit_name")
    fi
  done

  local preferred=()
  if [ "${#non_targets[@]}" -gt 0 ]; then
    preferred=("${non_targets[@]}")
  else
    preferred=("${targets[@]}")
  fi

  if [ "${#preferred[@]}" -eq 0 ]; then
    printf 'none\n'
    return
  fi

  local formatted=()
  local total="${#preferred[@]}"
  local max="$limit"
  local i
  if [ "$max" -le 0 ] || [ "$max" -gt "$total" ]; then
    max="$total"
  fi
  for ((i = 0; i < max; i++)); do
    formatted+=("$(compact_unit_label "${preferred[$i]}")")
  done

  local out
  out="$(IFS=,; printf '%s' "${formatted[*]}")"
  if [ "$total" -gt "$max" ]; then
    out="$out,+$((total - max))"
  fi
  if [ "${#non_targets[@]}" -gt 0 ] && [ "${#targets[@]}" -gt 0 ]; then
    out="$out (+${#targets[@]} targets)"
  fi
  printf '%s\n' "$out"
}

summarize_jobs_brief() {
  local jobs=("$@")
  local total waiting running other
  total="${#jobs[@]}"
  waiting=0
  running=0
  other=0

  local job state
  for job in "${jobs[@]}"; do
    state="${job##*/}"
    case "$state" in
      waiting) waiting=$((waiting + 1)) ;;
      running) running=$((running + 1)) ;;
      *) other=$((other + 1)) ;;
    esac
  done

  printf 'jobs=%s (w:%s r:%s o:%s)' "$total" "$waiting" "$running" "$other"
}

summarize_units_brief() {
  local units=("$@")
  local total activating deactivating failed other
  total="${#units[@]}"
  activating=0
  deactivating=0
  failed=0
  other=0

  local unit activity substate
  for unit in "${units[@]}"; do
    activity="${unit#*:}"
    activity="${activity%%/*}"
    substate="${unit##*/}"
    case "$activity/$substate" in
      activating/*) activating=$((activating + 1)) ;;
      deactivating/*) deactivating=$((deactivating + 1)) ;;
      */failed) failed=$((failed + 1)) ;;
      *) other=$((other + 1)) ;;
    esac
  done

  printf 'units=%s (up:%s down:%s fail:%s other:%s)' "$total" "$activating" "$deactivating" "$failed" "$other"
}

wait_for_target_reconcile() {
  local start_time now elapsed target_state progress_line last_progress_line last_log_time
  local jobs=()
  local interesting_units=()
  local failed_units=()
  start_time="$(date +%s)"
  last_log_time="$start_time"
  last_progress_line=""

  while true; do
    mapfile -t jobs < <(matching_systemd_jobs)
    mapfile -t interesting_units < <(interesting_systemd_units)
    mapfile -t failed_units < <(user_systemd_failed_units)
    target_state="$(user_systemctl is-active webservices.target 2>/dev/null || true)"

    if [ "${#failed_units[@]}" -gt 0 ]; then
      deploy_log "systemd reconcile failed units: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${failed_units[@]}")"
      return 1
    fi

    if [ "${#jobs[@]}" -eq 0 ]; then
      if [ "$target_state" = "active" ]; then
        deploy_log "systemd reconcile complete (target=$target_state)"
        return 0
      fi
      deploy_log "systemd reconcile finished without outstanding jobs, but target state is '$target_state'"
      return 1
    fi

    now="$(date +%s)"
    elapsed="$((now - start_time))"

    progress_line="$(printf '[reconcile t+%ss] target=%s %s' "$elapsed" "$target_state" "$(summarize_jobs_brief "${jobs[@]}")")"
    if [ "${#interesting_units[@]}" -gt 0 ]; then
      progress_line="$progress_line $(summarize_units_brief "${interesting_units[@]}")"
    fi
    progress_line="$progress_line blockers=$(compact_blockers_from_jobs "$SYSTEMD_PROGRESS_MAX_ITEMS" "${jobs[@]}")"

    if [ "$progress_line" != "$last_progress_line" ] || [ "$((now - last_log_time))" -ge "$SYSTEMD_PROGRESS_HEARTBEAT_SECONDS" ]; then
      deploy_log "$progress_line"
      last_progress_line="$progress_line"
      last_log_time="$now"
    fi

    if [ "$elapsed" -ge "$SYSTEMD_RECONCILE_TIMEOUT_SECONDS" ]; then
      deploy_log "timed out waiting for systemd reconcile after ${elapsed}s"
      return 1
    fi

    sleep "$SYSTEMD_PROGRESS_INTERVAL_SECONDS"
  done
}

reconcile_target() {
  local graph_file aux_targets=() excluded_aux_targets=() main_action aux_action
  graph_file="$BUNDLE_ROOT/stack.systemd/graph.json"
  mapfile -t aux_targets < <(
    jq -r '
      (.defaultTarget.wantsTargets // []) as $wanted
      | [(.auxiliaryTargets // [] | .[]?.name | . as $target | select($target != null and ($wanted | index($target)) != null))]
      | .[]
    ' "$graph_file"
  )
  mapfile -t excluded_aux_targets < <(
    jq -r '
      (.defaultTarget.wantsTargets // []) as $wanted
      | [(.auxiliaryTargets // [] | .[]?.name | . as $target | select($target != null and (($wanted | index($target)) == null)))]
      | .[]
    ' "$graph_file"
  )

  if user_systemctl is-active --quiet webservices.target; then
    main_action="reconciling"
  else
    main_action="starting"
  fi
  aux_action="start"

  if [ "${#aux_targets[@]}" -gt 0 ]; then
    deploy_log "$main_action webservices.target and default auxiliary targets under systemd --user: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${aux_targets[@]}")"
    user_systemctl "$aux_action" --no-block "${aux_targets[@]}"
  else
    deploy_log "$main_action webservices.target under systemd --user (no auxiliary targets declared)"
  fi

  if [ "${#excluded_aux_targets[@]}" -gt 0 ]; then
    deploy_log "stopping non-default auxiliary targets under systemd --user: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${excluded_aux_targets[@]}")"
    user_systemctl stop --no-block "${excluded_aux_targets[@]}" || true
    user_systemctl reset-failed
  fi

  user_systemctl "${aux_action}" --no-block webservices.target
  wait_for_target_reconcile
}

reload_deploy_reconcile_units() {
  local units=()
  local unit

  for unit in "${units[@]}"; do
    if ! user_systemctl is-active --quiet "$unit"; then
      continue
    fi
    deploy_log "reloading deploy reconciliation unit under systemd --user: $unit"
    user_systemctl reload "$unit"
  done
}

restart_post_reconcile_units() {
  local unit
  local units=(
    webservices-keycloak-configure.service
    webservices-keycloak-auth-gateway.service
  )

  for unit in "${units[@]}"; do
    [ -f "$BUNDLE_ROOT/systemd-user/$unit" ] || continue
    deploy_log "restarting post-reconcile unit under systemd --user: $unit"
    user_systemctl reset-failed "$unit" || true
    user_systemctl restart "$unit"
  done
}

reload_deploy_sensitive_units() {
  local unit_name units=()
  local configured_units="${DEPLOY_RELOAD_UNITS:-webservices-caddy.service webservices-onboarding.service webservices-synapse.service webservices-forgejo.service webservices-homeassistant.service webservices-qbittorrent.service webservices-sogo.service webservices-jellyfin.service webservices-jellyfin-profile-proxy.service webservices-donetick.service webservices-erpnext-backend.service webservices-erpnext-websocket.service webservices-erpnext-queue-short.service webservices-erpnext-queue-long.service webservices-erpnext-scheduler.service webservices-erpnext.service webservices-workspace-provisioner.service webservices-chatgpt-connector.service}"

  for unit_name in $configured_units; do
    [ -f "$BUNDLE_ROOT/systemd-user/$unit_name" ] || continue
    if ! grep -q '^ExecReload=' "$BUNDLE_ROOT/systemd-user/$unit_name"; then
      continue
    fi
    if user_systemctl is-active --quiet "$unit_name"; then
      units+=("$unit_name")
    fi
  done

  if [ "${#units[@]}" -eq 0 ]; then
    deploy_log "no active deploy-sensitive lifecycle units need reload"
    return 0
  fi

  deploy_log "reloading active deploy-sensitive lifecycle units under systemd --user: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${units[@]}")"
  user_systemctl reload "${units[@]}"
}

reload_changed_built_image_units() {
  local service_name image_ref before_id after_id container_image_id unit_name
  local units=()
  local seen=" "

  while IFS=$'\t' read -r service_name image_ref; do
    [ -n "$service_name" ] || continue
    before_id="${BUILT_IMAGE_IDS_BEFORE[$service_name]:-}"
    after_id="$(image_id_for_ref "$image_ref")"
    container_image_id="$(container_image_id_for_service "$service_name")"
    if [ -n "$before_id" ] && [ "$before_id" = "$after_id" ] && { [ -z "$container_image_id" ] || [ "$container_image_id" = "$after_id" ]; }; then
      continue
    fi

    unit_name="$(unit_for_compose_service "$service_name")"
    [ -f "$BUNDLE_ROOT/systemd-user/$unit_name" ] || continue
    if ! grep -q '^ExecReload=' "$BUNDLE_ROOT/systemd-user/$unit_name"; then
      continue
    fi
    if ! user_systemctl is-active --quiet "$unit_name"; then
      continue
    fi
    if [[ "$seen" == *" $unit_name "* ]]; then
      continue
    fi
    seen="$seen$unit_name "
    units+=("$unit_name")
  done < <(built_image_services)

  if [ "${#units[@]}" -eq 0 ]; then
    deploy_log "no active built-image lifecycle units changed"
    return 0
  fi

  deploy_log "reloading active lifecycle units with changed built images under systemd --user: $(join_array_limited "$SYSTEMD_PROGRESS_MAX_ITEMS" "${units[@]}")"
  user_systemctl reload "${units[@]}"
}

restart_deploy_job_units() {
  local unit_name
  local configured_units="${DEPLOY_RESTART_JOB_UNITS:-webservices-erpnext-configurator.service webservices-erpnext-bootstrap.service}"

  for unit_name in $configured_units; do
    [ -f "$BUNDLE_ROOT/systemd-user/$unit_name" ] || continue
    deploy_log "restarting deploy job unit under systemd --user: $unit_name"
    user_systemctl reset-failed "$unit_name" || true
    user_systemctl restart "$unit_name"
  done
}

refresh_infra_units() {
  deploy_log "refreshing Docker networks and volumes from rendered infra config"
  "$SCRIPT_DIR/lib/systemd-docker-infra.sh" ensure-networks \
    --config-file "$BUNDLE_ROOT/systemd-user/infra/networks.json" \
    --env-file "$DEPLOY_ROOT/runtime/stack.env"
  "$SCRIPT_DIR/lib/systemd-docker-infra.sh" ensure-volumes \
    --config-file "$BUNDLE_ROOT/systemd-user/infra/volumes.json" \
    --env-file "$DEPLOY_ROOT/runtime/stack.env"
}

preflight
if [ "$PREFLIGHT_ONLY" = "1" ]; then
  set_phase "preflight-only"
  exit 0
fi

set_phase "render-runtime"
ensure_runtime_links "$DEPLOY_ROOT" >/dev/null
"$SCRIPT_DIR/deploy/render-runtime.sh" --bundle-root "$BUNDLE_ROOT" --deploy-root "$DEPLOY_ROOT" --site-manifest "$site_manifest_path"

set_phase "model-prep"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global model prep for scoped deploy"
else
  run_model_prep_jobs
fi

set_phase "compose-build-snapshot"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global built-image snapshot for scoped deploy"
else
  snapshot_built_image_ids_before
fi

set_phase "compose-build"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global compose build; selected lifecycle units build through ExecReload/ExecStart"
else
  deploy_log "building service images"
  COMPOSE_PROJECT_NAME="$PROJECT_NAME" run_compose_from_bundle \
    "$BUNDLE_ROOT" \
    "$DEPLOY_ROOT/runtime/stack.env" \
    build
fi

set_phase "cleanup-excluded-services"
cleanup_excluded_service_containers

set_phase "bootstrap-scaffolds"
ensure_bootstrap_scaffolds

set_phase "install-systemd-units"
deploy_log "installing pre-rendered systemd user units"
"$SCRIPT_DIR/deploy/install-systemd-user-units.sh" \
  --unit-dir "$BUNDLE_ROOT/systemd-user"

set_phase "systemd-daemon-reload"
deploy_log "reloading user systemd manager"
user_systemctl daemon-reload
user_systemctl reset-failed
user_systemctl enable webservices.target >/dev/null

deploy_log "enabled target: webservices.target"

set_phase "seafile-volume-migration"
migrate_legacy_seafile_split_volume

set_phase "systemd-refresh-infra"
refresh_infra_units

set_phase "systemd-restart-deploy-job-units"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global deploy job restarts for scoped deploy"
else
  restart_deploy_job_units
fi

set_phase "systemd-reload-changed-built-images"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global built-image reload detection for scoped deploy"
else
  reload_changed_built_image_units
fi

set_phase "systemd-reload-deploy-sensitive-units"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global deploy-sensitive reloads for scoped deploy"
else
  reload_deploy_sensitive_units
fi

set_phase "systemd-reconcile-target"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  reconcile_scoped_units
else
  reconcile_target
fi

set_phase "post-reconcile-bootstrap"
if [ "$PARTIAL_DEPLOY" = "1" ]; then
  deploy_log "skipping global post-reconcile bootstrap restarts for scoped deploy"
else
  restart_post_reconcile_units
fi
reload_deploy_reconcile_units

set_phase "complete"
deploy_log "deploy complete"
deploy_log "next step: cd $DEPLOY_ROOT && ./verify.sh"
