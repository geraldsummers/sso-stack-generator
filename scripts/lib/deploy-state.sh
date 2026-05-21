#!/usr/bin/env bash

# shellcheck source=scripts/lib/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

deploy_state_dir() {
  local deploy_root="$1"
  printf '%s\n' "$deploy_root/runtime/deploy-state"
}

deploy_state_file_sha256() {
  local path="$1"
  if [ -f "$path" ]; then
    sha256sum "$path" | awk '{print $1}'
  else
    printf 'missing\n'
  fi
}

deploy_state_global_signature() {
  local bundle_root="$1"
  local components_lock="$bundle_root/site/components.lock.json"
  local graph_file="$bundle_root/stack.systemd/graph.json"
  local networks_file="$bundle_root/systemd-user/infra/networks.json"
  local volumes_file="$bundle_root/systemd-user/infra/volumes.json"

  jq -n \
    --arg componentsLock "$(deploy_state_file_sha256 "$components_lock")" \
    --arg systemdGraph "$(deploy_state_file_sha256 "$graph_file")" \
    --arg infraNetworks "$(deploy_state_file_sha256 "$networks_file")" \
    --arg infraVolumes "$(deploy_state_file_sha256 "$volumes_file")" \
    '{
      version: 1,
      inputs: {
        componentsLock: $componentsLock,
        systemdGraph: $systemdGraph,
        infraNetworks: $infraNetworks,
        infraVolumes: $infraVolumes
      }
    }'
}

deploy_state_write_global_signature() {
  local bundle_root="$1"
  local deploy_root="$2"
  local state_dir signature_file temp_file

  state_dir="$(deploy_state_dir "$deploy_root")"
  signature_file="$state_dir/global-signature.json"
  mkdir -p -m 700 "$state_dir"
  chmod go-w "$state_dir"
  temp_file="$(mktemp "$state_dir/.global-signature.tmp.XXXXXX")"
  deploy_state_global_signature "$bundle_root" > "$temp_file"
  chmod 0600 "$temp_file"
  mv -f "$temp_file" "$signature_file"
}

deploy_state_check_global_signature() {
  local bundle_root="$1"
  local deploy_root="$2"
  local state_dir signature_file current_file diff_output

  state_dir="$(deploy_state_dir "$deploy_root")"
  signature_file="$state_dir/global-signature.json"
  if [ ! -f "$signature_file" ]; then
    printf '[webservices-build] ERROR: scoped deploy has no previous global deploy signature at %s; run a full deploy first\n' "$signature_file" >&2
    return 1
  fi

  current_file="$(mktemp "${TMPDIR:-/tmp}/webservices-global-signature.XXXXXX")"
  deploy_state_global_signature "$bundle_root" > "$current_file"
  if cmp -s "$signature_file" "$current_file"; then
    rm -f "$current_file"
    return 0
  fi

  diff_output="$(diff -u "$signature_file" "$current_file" || true)"
  rm -f "$current_file"
  printf '%s\n' "$diff_output" >&2
  printf '[webservices-build] ERROR: global deployment inputs changed; run a full deploy so component selection, systemd graph, and Docker infra reconcile together\n' >&2
  return 1
}
