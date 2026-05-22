#!/usr/bin/env bash

# shellcheck source=scripts/lib/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

deploy_scope_validate_unit_reference() {
  local unit_name="$1"
  [[ "$unit_name" =~ ^[A-Za-z0-9][A-Za-z0-9_.@:-]*\.(service|target)$ ]]
}

deploy_scope_normalize_unit() {
  local requested_unit="$1"
  local unit_prefix="$2"
  local normalized

  case "$requested_unit" in
    *.service|*.target)
      normalized="$requested_unit"
      ;;
    "$unit_prefix"-*)
      normalized="$requested_unit.service"
      ;;
    *)
      normalized="$unit_prefix-$requested_unit.service"
      ;;
  esac

  if ! deploy_scope_validate_unit_reference "$normalized"; then
    die "invalid scoped systemd unit reference: $requested_unit"
  fi
  printf '%s\n' "$normalized"
}

deploy_scope_unit_domain() {
  local unit_name="$1"
  local unit_prefix="$2"
  local domain_name

  deploy_scope_validate_unit_reference "$unit_name" || die "invalid scoped systemd unit reference: $unit_name"
  [[ "$unit_name" == *.service ]] || return 0
  [[ "$unit_name" == "$unit_prefix"-* ]] || return 0

  domain_name="${unit_name#"$unit_prefix"-}"
  domain_name="${domain_name%.service}"
  [ -n "$domain_name" ] || return 0
  printf '%s\n' "$domain_name"
}

deploy_scope_services_for_unit() {
  local requested_unit="$1"
  local unit_prefix="$2"
  local graph_file="$3"
  local compose_config_json="$4"
  local unit_name domain_name services

  [ -f "$graph_file" ] || die "missing systemd graph: $graph_file"
  [ -f "$compose_config_json" ] || die "missing compose config JSON: $compose_config_json"

  unit_name="$(deploy_scope_normalize_unit "$requested_unit" "$unit_prefix")"
  domain_name="$(deploy_scope_unit_domain "$unit_name" "$unit_prefix")"
  [ -n "$domain_name" ] || return 0

  services="$(jq -r --arg domain "$domain_name" '
    .lifecycleDomains[]?
    | select(.name == $domain)
    | .services[]?
  ' "$graph_file")"
  if [ -n "$services" ]; then
    printf '%s\n' "$services"
    return 0
  fi

  if jq -e --arg service "$domain_name" '.services[$service] != null' "$compose_config_json" >/dev/null; then
    printf '%s\n' "$domain_name"
  fi
}
