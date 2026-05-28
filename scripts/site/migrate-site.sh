#!/usr/bin/env bash
set -euo pipefail

SITE_ROOT=""
FROM_COMMIT=""
TO_COMMIT=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --site-root)
      SITE_ROOT="$2"
      shift
      ;;
    --from)
      FROM_COMMIT="$2"
      shift
      ;;
    --to)
      TO_COMMIT="$2"
      shift
      ;;
    -h|--help)
      cat <<'EOF_USAGE'
Usage:
  migrate-site.sh --site-root <path> --from <commit> --to <commit>

Runs generator-owned site repo migrations. This first version validates the
expected site files and leaves data unchanged.
EOF_USAGE
      exit 0
      ;;
    *)
      printf '[webservices-migrate] ERROR: unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
  shift
done

[ -n "$SITE_ROOT" ] || { printf '[webservices-migrate] ERROR: --site-root is required\n' >&2; exit 1; }
[ -f "$SITE_ROOT/manifest.json" ] || { printf '[webservices-migrate] ERROR: missing manifest.json in %s\n' "$SITE_ROOT" >&2; exit 1; }

if [ -f "$SITE_ROOT/stack.config.yaml" ]; then
  stack_config_file="$SITE_ROOT/stack.config.yaml"
elif [ -f "$SITE_ROOT/global.settings/stack.config.yaml" ]; then
  stack_config_file="$SITE_ROOT/global.settings/stack.config.yaml"
else
  printf '[webservices-migrate] ERROR: missing stack.config.yaml in %s or %s\n' "$SITE_ROOT" "$SITE_ROOT/global.settings" >&2
  exit 1
fi

if [ -f "$SITE_ROOT/webservices.sops.json" ]; then
  secrets_file="$SITE_ROOT/webservices.sops.json"
elif [ -f "$SITE_ROOT/global.settings/webservices.sops.json" ]; then
  secrets_file="$SITE_ROOT/global.settings/webservices.sops.json"
else
  printf '[webservices-migrate] ERROR: missing webservices.sops.json in %s or %s\n' "$SITE_ROOT" "$SITE_ROOT/global.settings" >&2
  exit 1
fi

printf '[webservices-migrate] validated site files: %s, %s\n' "$stack_config_file" "$secrets_file" >&2
printf '[webservices-migrate] no site migrations required (%s -> %s)\n' "${FROM_COMMIT:-unknown}" "${TO_COMMIT:-unknown}" >&2
