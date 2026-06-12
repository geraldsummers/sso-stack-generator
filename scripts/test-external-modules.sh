#!/usr/bin/env bash
set -Eeuo pipefail
trap 'status=$?; printf "[external-modules-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
# shellcheck source=scripts/lib/components.sh
source "$ROOT_DIR/scripts/lib/components.sh"
# shellcheck source=scripts/lib/external-modules.sh
source "$ROOT_DIR/scripts/lib/external-modules.sh"

git_commit_all() {
  local repo_dir="$1"
  local message="$2"
  git -C "$repo_dir" add .
  git -C "$repo_dir" \
    -c user.name="External Module Test" \
    -c user.email="external-module-test@example.invalid" \
    commit -m "$message" >/dev/null
  git -C "$repo_dir" rev-parse HEAD
}

assert_file() {
  local file="$1"
  [ -f "$file" ] || {
    printf '[external-modules-test] expected file: %s\n' "$file" >&2
    exit 1
  }
}

tmp_root="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_root"
}
trap cleanup EXIT

EXTERNAL_MODULES_CACHE_DIR="$tmp_root/external-git"
EXTERNAL_MODULES_ROOT="$tmp_root/external-modules"
EXTERNAL_MODULES_MATERIALIZED_DIR="$EXTERNAL_MODULES_ROOT/materialized"
EXTERNAL_MODULES_METADATA_FILE="$EXTERNAL_MODULES_ROOT/metadata.json"

module_repo="$tmp_root/demo-module"
manifest_repo="$tmp_root/module-manifest"
site_root="$tmp_root/site"
bundle_root="$tmp_root/bundle"
mkdir -p "$module_repo" "$manifest_repo" "$site_root" "$bundle_root/stack.config"
git -C "$module_repo" init -b main >/dev/null
git -C "$manifest_repo" init -b main >/dev/null

mkdir -p "$module_repo/stack.compose" "$module_repo/stack.config"
cat > "$module_repo/stack.compose/demo.yml" <<'EOF_COMPOSE'
services:
  demo-module:
    image: caddy:2.11.3
EOF_COMPOSE
cat > "$module_repo/stack.config/components.json" <<'EOF_COMPONENTS'
{
  "schemaVersion": 1,
  "defaultComponents": [],
  "components": {
    "demo-module": {
      "name": "Demo module",
      "description": "External module test service.",
      "dependencies": ["core"],
      "composeFiles": ["demo.yml"]
    }
  }
}
EOF_COMPONENTS
module_commit="$(git_commit_all "$module_repo" "Add demo module")"

cat > "$manifest_repo/modules.json" <<EOF_MODULES
{
  "modules": [
    {
      "name": "demo-module",
      "git": "$module_repo",
      "ref": "main",
      "commit": "$module_commit"
    }
  ]
}
EOF_MODULES
manifest_commit="$(git_commit_all "$manifest_repo" "Add module manifest")"

cat > "$site_root/manifest.json" <<'EOF_SITE'
{
  "site": "external-module-test",
  "stackConfig": "stack.config.yaml",
  "secretStore": "webservices.sops.json",
  "components": ["demo-module"]
}
EOF_SITE
cat > "$site_root/stack.config.yaml" <<'EOF_CONFIG'
runtime:
  domain: "example.test"
EOF_CONFIG
cat > "$site_root/webservices.sops.json" <<'EOF_SECRETS'
{}
EOF_SECRETS
cat > "$site_root/.webservices-generator.json" <<EOF_PIN
{
  "generatorRemote": "local",
  "generatorRef": "dev",
  "generatorCommit": "local",
  "moduleManifestRemote": "$manifest_repo",
  "moduleManifestRef": "main",
  "moduleManifestCommit": "$manifest_commit"
}
EOF_PIN

external_modules_resolve "$site_root/manifest.json"
assert_file "$EXTERNAL_MODULES_MATERIALIZED_DIR/stack.compose/demo.yml"
assert_file "$EXTERNAL_MODULES_MATERIALIZED_DIR/stack.config/components.external/demo-module.json"
jq -e '.enabled == true and (.modules | length) == 1 and .modules[0].name == "demo-module"' \
  "$EXTERNAL_MODULES_METADATA_FILE" >/dev/null

cp "$ROOT_DIR/stack.config/components.json" "$bundle_root/stack.config/components.json"
external_modules_overlay_into "$bundle_root"
component_catalog_merge_external "$bundle_root/stack.config/components.json"
jq -e '.components["demo-module"].composeFiles == ["demo.yml"]' "$bundle_root/stack.config/components.json" >/dev/null

bad_repo="$tmp_root/bad-module"
mkdir -p "$bad_repo/scripts"
git -C "$bad_repo" init -b main >/dev/null
cat > "$bad_repo/scripts/not-allowed.sh" <<'EOF_BAD'
#!/usr/bin/env bash
true
EOF_BAD
bad_commit="$(git_commit_all "$bad_repo" "Add unsupported path")"
cat > "$manifest_repo/modules.json" <<EOF_BAD_MODULES
{
  "modules": [
    {
      "name": "bad-module",
      "git": "$bad_repo",
      "ref": "main",
      "commit": "$bad_commit"
    }
  ]
}
EOF_BAD_MODULES
bad_manifest_commit="$(git_commit_all "$manifest_repo" "Add bad module manifest")"
jq --arg commit "$bad_manifest_commit" '.moduleManifestCommit = $commit' "$site_root/.webservices-generator.json" > "$site_root/pin.tmp"
mv "$site_root/pin.tmp" "$site_root/.webservices-generator.json"
trap - ERR
set +e
( external_modules_resolve "$site_root/manifest.json" ) >/dev/null 2>&1
bad_status=$?
set -e
trap 'status=$?; printf "[external-modules-test] failed at line %s: %s (exit %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2' ERR
if [ "$bad_status" -eq 0 ]; then
  printf '[external-modules-test] unsupported module path was accepted\n' >&2
  exit 1
fi

printf '[external-modules-test] ok\n' >&2
