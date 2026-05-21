#!/usr/bin/env bash

# shellcheck source=scripts/lib/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

env_file_validate_key() {
  local key="$1"
  [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]]
}

env_file_decode_value() {
  local value="$1"
  value="${value//\$\$/\$}"
  printf '%s\n' "$value"
}

env_file_get_value() {
  local env_file="$1"
  local requested_key="$2"
  local line key value
  local line_number=0
  local found=0

  [ -f "$env_file" ] || die "missing env file: $env_file"
  env_file_validate_key "$requested_key" || die "invalid env key: $requested_key"

  while IFS= read -r line || [ -n "$line" ]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    case "$line" in
      ''|'#'*)
        continue
        ;;
    esac

    if [[ "$line" != *=* ]]; then
      die "invalid env file entry at $env_file:$line_number: expected KEY=value"
    fi

    key="${line%%=*}"
    value="${line#*=}"
    if ! env_file_validate_key "$key"; then
      die "invalid env file key at $env_file:$line_number: $key"
    fi
    if [ "$key" = "$requested_key" ]; then
      found=1
      env_file_decode_value "$value"
    fi
  done < "$env_file"

  [ "$found" = "1" ] || return 0
}
