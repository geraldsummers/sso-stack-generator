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
[ -f "$SITE_ROOT/stack.config.yaml" ] || { printf '[webservices-migrate] ERROR: missing stack.config.yaml in %s\n' "$SITE_ROOT" >&2; exit 1; }
[ -f "$SITE_ROOT/webservices.sops.json" ] || { printf '[webservices-migrate] ERROR: missing webservices.sops.json in %s\n' "$SITE_ROOT" >&2; exit 1; }

printf '[webservices-migrate] no site migrations required (%s -> %s)\n' "${FROM_COMMIT:-unknown}" "${TO_COMMIT:-unknown}" >&2
