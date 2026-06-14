#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[module-group-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
run_smoke=false
workspace=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/test-module-group.sh [--smoke] /path/to/workspace

Runs scripts/test-module.sh for every direct child containing stack.module.json.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --smoke)
      run_smoke=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      printf '[module-group-test] unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
    *)
      if [ -n "$workspace" ]; then
        printf '[module-group-test] only one workspace path may be specified\n' >&2
        usage
        exit 2
      fi
      workspace="$1"
      shift
      ;;
  esac
done

[ -n "$workspace" ] || { usage; exit 2; }
[ -d "$workspace" ] || { printf '[module-group-test] workspace not found: %s\n' "$workspace" >&2; exit 1; }

mapfile -t module_dirs < <(find "$workspace" -mindepth 2 -maxdepth 2 -name stack.module.json -printf '%h\n' | sort)
if [ "${#module_dirs[@]}" -eq 0 ]; then
  printf '[module-group-test] no stack.module.json files found under: %s\n' "$workspace" >&2
  exit 1
fi

failed=()
for module_dir in "${module_dirs[@]}"; do
  if [ "$run_smoke" = true ]; then
    "$SCRIPT_DIR/test-module.sh" --smoke "$module_dir" || failed+=("$module_dir")
  else
    "$SCRIPT_DIR/test-module.sh" "$module_dir" || failed+=("$module_dir")
  fi
done

if [ "${#failed[@]}" -gt 0 ]; then
  printf '[module-group-test] failed modules: %s\n' "${failed[*]}" >&2
  exit 1
fi

printf '[module-group-test] ok: %s modules\n' "${#module_dirs[@]}"
