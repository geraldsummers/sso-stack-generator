#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# shellcheck source=scripts/lib/deploy-state.sh
source "$ROOT_DIR/scripts/lib/deploy-state.sh"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

bundle_root="$tmp_dir/bundle"
deploy_root="$tmp_dir/deploy"
mkdir -p \
  "$bundle_root/site" \
  "$bundle_root/stack.systemd" \
  "$bundle_root/systemd-user/infra" \
  "$deploy_root/runtime"

cat > "$bundle_root/site/components.lock.json" <<'EOF_JSON'
{"generatedAt":"test","components":["core"]}
EOF_JSON

cat > "$bundle_root/stack.systemd/graph.json" <<'EOF_JSON'
{"unitPrefix":"webservices","defaultTarget":{"name":"webservices.target"}}
EOF_JSON

cat > "$bundle_root/systemd-user/infra/networks.json" <<'EOF_JSON'
[]
EOF_JSON

cat > "$bundle_root/systemd-user/infra/volumes.json" <<'EOF_JSON'
[]
EOF_JSON

deploy_state_write_global_signature "$bundle_root" "$deploy_root"
deploy_state_check_global_signature "$bundle_root" "$deploy_root"

signature_file="$deploy_root/runtime/deploy-state/global-signature.json"
if grep -q 'core' "$signature_file"; then
  printf '[deploy-state-test] signature leaked component contents\n' >&2
  exit 1
fi

cat > "$bundle_root/stack.systemd/graph.json" <<'EOF_JSON'
{"unitPrefix":"webservices","defaultTarget":{"name":"webservices.target"},"auxiliaryTargets":[{"name":"webservices-apps.target"}]}
EOF_JSON

if deploy_state_check_global_signature "$bundle_root" "$deploy_root" >/dev/null 2>"$tmp_dir/error.log"; then
  printf '[deploy-state-test] changed graph did not block scoped deploy\n' >&2
  exit 1
fi

if ! grep -q 'run a full deploy' "$tmp_dir/error.log"; then
  printf '[deploy-state-test] missing full-deploy guidance on mismatch\n' >&2
  cat "$tmp_dir/error.log" >&2
  exit 1
fi

printf '[deploy-state-test] ok\n' >&2
