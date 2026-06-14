#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[site-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
site_manifest=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/test-site.sh /path/to/site/manifest.json

Runs the generator build for a site manifest. Site-owned composition and live
tests stay in the site configuration repository.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      printf '[site-test] unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
    *)
      if [ -n "$site_manifest" ]; then
        printf '[site-test] only one manifest path may be specified\n' >&2
        usage
        exit 2
      fi
      site_manifest="$1"
      shift
      ;;
  esac
done

[ -n "$site_manifest" ] || { usage; exit 2; }
[ -f "$site_manifest" ] || { printf '[site-test] manifest not found: %s\n' "$site_manifest" >&2; exit 1; }

"$ROOT_DIR/build.sh" --manifest "$site_manifest"
printf '[site-test] ok: %s\n' "$site_manifest"
