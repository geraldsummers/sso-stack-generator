#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[module-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
run_smoke=false
module_dir=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/test-module.sh [--smoke] /path/to/module

Validates stack.module.json and runs tests/validate.sh when present.
With --smoke, also runs tests/smoke.sh when present.
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
      printf '[module-test] unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
    *)
      if [ -n "$module_dir" ]; then
        printf '[module-test] only one module path may be specified\n' >&2
        usage
        exit 2
      fi
      module_dir="$1"
      shift
      ;;
  esac
done

[ -n "$module_dir" ] || { usage; exit 2; }
[ -d "$module_dir" ] || { printf '[module-test] module directory not found: %s\n' "$module_dir" >&2; exit 1; }
module_dir="$(cd "$module_dir" && pwd -P)"
metadata_file="$module_dir/stack.module.json"
[ -f "$metadata_file" ] || { printf '[module-test] missing stack.module.json: %s\n' "$module_dir" >&2; exit 1; }

python3 - "$metadata_file" "$module_dir" "$ROOT_DIR/modules/catalog.json" <<'PY'
import json
import re
import sys
from pathlib import Path

metadata_path = Path(sys.argv[1])
module_dir = Path(sys.argv[2])
catalog_path = Path(sys.argv[3])
metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

allowed_keys = {
    "schemaVersion",
    "id",
    "repo",
    "runtimeId",
    "lifecycle",
    "dependencies",
    "overlays",
    "sourceRepo",
    "testAssets",
}
extra = sorted(set(metadata) - allowed_keys)
if extra:
    raise SystemExit(f"unexpected stack.module.json keys: {', '.join(extra)}")

if metadata.get("schemaVersion") != 1:
    raise SystemExit("schemaVersion must be 1")

module_id = metadata.get("id")
if not isinstance(module_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", module_id):
    raise SystemExit("id must be a lowercase module id")

repo = metadata.get("repo")
if not isinstance(repo, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", repo):
    raise SystemExit("repo must be a repository name")

expected_repos = {module_id, f"{module_id}-stack-module", f"{module_id}-module"}
if repo not in expected_repos:
    expected = " or ".join(sorted(expected_repos))
    raise SystemExit(f"repo must match module id: expected {expected}, got {repo}")

lifecycle = metadata.get("lifecycle")
if lifecycle not in {"active", "experimental", "retired"}:
    raise SystemExit("lifecycle must be active, experimental, or retired")

for key in ("dependencies", "overlays", "testAssets"):
    value = metadata.get(key, [] if key != "overlays" else None)
    if not isinstance(value, list):
        raise SystemExit(f"{key} must be an array")
    if len(value) != len(set(value)):
        raise SystemExit(f"{key} contains duplicates")

for dep in metadata.get("dependencies", []):
    if not isinstance(dep, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", dep):
        raise SystemExit(f"invalid dependency id: {dep!r}")
    if dep == module_id:
        raise SystemExit("module may not depend on itself")

allowed_prefixes = (
    "global.settings/",
    "stack.compose/",
    "stack.config/",
    "stack.containers/",
    "stack.kotlin/",
    "stack.js/",
    "stack.systemd/",
    "scripts/lib/",
    "scripts/modules/",
    "docs/modules/",
    "tests/fixtures/",
)
allowed_roots = {prefix.rstrip("/") for prefix in allowed_prefixes}

def validate_relative_path(path_value: str, key: str) -> None:
    if not isinstance(path_value, str) or not path_value:
        raise SystemExit(f"{key} entries must be non-empty strings")
    path = Path(path_value)
    if path.is_absolute() or ".." in path.parts or "." in path.parts:
        raise SystemExit(f"{key} path is not safe: {path_value}")
    if path_value not in allowed_roots and not path_value.startswith(allowed_prefixes):
        raise SystemExit(f"{key} path is not an allowed overlay/test path: {path_value}")
    if not (module_dir / path).exists():
        raise SystemExit(f"{key} path does not exist: {path_value}")

overlays = metadata.get("overlays")
if not overlays:
    raise SystemExit("overlays must contain at least one owned path")
for overlay in overlays:
    validate_relative_path(overlay, "overlays")
for asset in metadata.get("testAssets", []):
    validate_relative_path(asset, "testAssets")

if catalog_path.exists():
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog_modules = {
        repo_entry.get("moduleId"): repo_entry.get("name")
        for repo_entry in catalog.get("repositories", [])
        if repo_entry.get("kind") == "stack-module"
    }
    catalog_repo = catalog_modules.get(module_id)
    if catalog_repo and catalog_repo != repo:
        raise SystemExit(f"catalog maps module {module_id} to {catalog_repo}, metadata says {repo}")
PY

if [ -x "$module_dir/tests/validate.sh" ]; then
  printf '[module-test] running validate.sh: %s\n' "$module_dir"
  (cd "$module_dir" && tests/validate.sh)
else
  printf '[module-test] no tests/validate.sh: %s\n' "$module_dir"
fi

if [ "$run_smoke" = true ]; then
  if [ -x "$module_dir/tests/smoke.sh" ]; then
    printf '[module-test] running smoke.sh: %s\n' "$module_dir"
    (cd "$module_dir" && tests/smoke.sh)
  else
    printf '[module-test] no tests/smoke.sh: %s\n' "$module_dir"
  fi
fi

printf '[module-test] ok: %s\n' "$module_dir"
