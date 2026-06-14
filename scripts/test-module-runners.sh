#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[module-runners-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"

tmp_root="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_root"
}
trap cleanup EXIT

workspace="$tmp_root/workspace"
module_dir="$workspace/demo-stack-module"
mkdir -p "$module_dir/stack.compose" "$module_dir/tests"

cat > "$module_dir/stack.compose/demo.yml" <<'EOF_COMPOSE'
services:
  demo:
    image: caddy:2.11.3
EOF_COMPOSE
cat > "$module_dir/stack.module.json" <<'EOF_MODULE'
{
  "schemaVersion": 1,
  "id": "demo",
  "repo": "demo-stack-module",
  "runtimeId": "demo",
  "lifecycle": "active",
  "dependencies": [],
  "overlays": ["stack.compose/demo.yml"]
}
EOF_MODULE
cat > "$module_dir/tests/validate.sh" <<'EOF_VALIDATE'
#!/usr/bin/env bash
set -euo pipefail
test -f stack.compose/demo.yml
EOF_VALIDATE
chmod +x "$module_dir/tests/validate.sh"

"$ROOT_DIR/scripts/test-module.sh" "$module_dir" >/dev/null
"$ROOT_DIR/scripts/test-module-group.sh" "$workspace" >/dev/null

bad_dir="$workspace/bad-stack-module"
mkdir -p "$bad_dir/stack.compose"
cat > "$bad_dir/stack.compose/bad.yml" <<'EOF_BAD_COMPOSE'
services: {}
EOF_BAD_COMPOSE
cat > "$bad_dir/stack.module.json" <<'EOF_BAD_MODULE'
{
  "schemaVersion": 1,
  "id": "bad",
  "repo": "bad-stack-module",
  "lifecycle": "active",
  "dependencies": [],
  "overlays": ["../stack.compose/bad.yml"]
}
EOF_BAD_MODULE

trap - ERR
set +e
"$ROOT_DIR/scripts/test-module.sh" "$bad_dir" >"$tmp_root/bad.log" 2>&1
bad_status=$?
set -e
trap 'status=$?; printf "[module-runners-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR
if [ "$bad_status" -eq 0 ]; then
  printf '[module-runners-test] invalid overlay path was accepted\n' >&2
  exit 1
fi
grep -Eq 'not safe|not an allowed overlay' "$tmp_root/bad.log"

printf '[module-runners-test] ok\n' >&2
