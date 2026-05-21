#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/lib/env-file.sh
source "$SCRIPT_DIR/lib/env-file.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

env_file="$tmp_dir/stack.env"
payload_marker="$tmp_dir/pwned"

{
  printf '# generated env\n'
  printf 'SAFE_VALUE=literal$(touch %s)\n' "$payload_marker"
  printf 'DOLLAR_VALUE=abc$$def\n'
  printf 'SEAFILE_MEDIA_ROOT=/srv/seafile-media\n'
} > "$env_file"

safe_value="$(env_file_get_value "$env_file" SAFE_VALUE)"
[ "$safe_value" = "literal\$(touch $payload_marker)" ] || {
  printf 'SAFE_VALUE parsed incorrectly: %s\n' "$safe_value" >&2
  exit 1
}
[ ! -e "$payload_marker" ] || {
  printf 'env parser executed shell payload\n' >&2
  exit 1
}

dollar_value="$(env_file_get_value "$env_file" DOLLAR_VALUE)"
[ "$dollar_value" = 'abc$def' ] || {
  printf 'DOLLAR_VALUE parsed incorrectly: %s\n' "$dollar_value" >&2
  exit 1
}

media_root="$(env_file_get_value "$env_file" SEAFILE_MEDIA_ROOT)"
[ "$media_root" = "/srv/seafile-media" ] || {
  printf 'SEAFILE_MEDIA_ROOT parsed incorrectly: %s\n' "$media_root" >&2
  exit 1
}

printf 'BAD-KEY=value\n' >> "$env_file"
if ( env_file_get_value "$env_file" SEAFILE_MEDIA_ROOT ) >"$tmp_dir/out" 2>"$tmp_dir/err"; then
  printf 'invalid env key was accepted\n' >&2
  exit 1
fi

printf '[test-env-file-security] ok\n'
