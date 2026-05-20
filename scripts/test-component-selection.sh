#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$ROOT_DIR/scripts/lib/common.sh"
# shellcheck source=scripts/lib/components.sh
source "$ROOT_DIR/scripts/lib/components.sh"
# shellcheck source=scripts/lib/compose.sh
source "$ROOT_DIR/scripts/lib/compose.sh"

assert_not_contains() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if grep -Eq "$pattern" "$file"; then
    printf '[component-selection-test] unexpected %s in %s\n' "$label" "$file" >&2
    grep -En "$pattern" "$file" >&2 || true
    exit 1
  fi
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if ! grep -Eq "$pattern" "$file"; then
    printf '[component-selection-test] missing %s in %s\n' "$label" "$file" >&2
    exit 1
  fi
}

validate_caddy_file() {
  local caddy_file="$1"
  local caddy_log
  caddy_log="$(mktemp)"
  if ! docker run --rm \
    -v "$caddy_file:/etc/caddy/Caddyfile:ro" \
    -e DOMAIN=example.test \
    -e WORKSPACE_PROXY_AUTH_SECRET=test \
    -e ONBOARDING_TRUSTED_PROXY_SECRET=test \
    -e KOPIA_PROXY_AUTHORIZATION=test \
    -e BOOKSTACK_INTERNAL_API_TOKEN=test \
    -e CHATGPT_CONNECTOR_TRUSTED_PROXY_SECRET=test \
    -e SEARCH_SERVICE_INTERNAL_TOKEN=test \
    -e HOMEASSISTANT_TRUSTED_PROXY_SECRET=test \
    -e VAULTWARDEN_ORG_ID=00000000-0000-0000-0000-000000000000 \
    caddy:2.10.2 \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >"$caddy_log" 2>&1; then
    cat "$caddy_log" >&2
    rm -f "$caddy_log"
    exit 1
  fi
  rm -f "$caddy_log"
}

tmp_root="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_root"
}
trap cleanup EXIT

fake_bin="$tmp_root/bin"
bundle_root="$tmp_root/bundle/build"
site_root="$bundle_root/site"
runtime_root="$tmp_root/runtime"
mkdir -p "$fake_bin" "$bundle_root" "$site_root" "$runtime_root"

cat > "$fake_bin/sops" <<'EOF_SOPS'
#!/usr/bin/env bash
set -euo pipefail
last=""
for arg in "$@"; do
  last="$arg"
done
cat "$last"
EOF_SOPS
chmod +x "$fake_bin/sops"

copy_tree "$ROOT_DIR/global.settings" "$bundle_root/global.settings"
copy_tree "$ROOT_DIR/stack.compose" "$bundle_root/stack.compose"
copy_tree "$ROOT_DIR/stack.config" "$bundle_root/stack.config"
copy_tree "$ROOT_DIR/scripts" "$bundle_root/scripts"

cat > "$site_root/manifest.json" <<'EOF_MANIFEST'
{
  "site": "component-test",
  "stackConfig": "stack.config.yaml",
  "secretStore": "webservices.sops.json",
  "components": ["core"]
}
EOF_MANIFEST

cat > "$site_root/stack.config.yaml" <<'EOF_CONFIG'
runtime:
  domain: "example.test"
  admin_email: "admin@example.test"
  admin_user: "admin"
  caddy_tls_mode: "local"

vaultwarden:
  org_id: "00000000-0000-0000-0000-000000000000"
EOF_CONFIG

cat > "$site_root/webservices.sops.json" <<'EOF_SECRETS'
{
  "STACK_ADMIN_PASSWORD": "component-test-password"
}
EOF_SECRETS

cat > "$bundle_root/build-info.json" <<'EOF_BUILD_INFO'
{
  "version": "component-test",
  "gitSha": "component-test",
  "gitShortSha": "component-test",
  "gitBranch": "component-test",
  "gitDirty": false,
  "sourceBuiltAt": "component-test",
  "builtBy": "component-test",
  "buildSystem": "component-test"
}
EOF_BUILD_INFO

component_selection_write_metadata \
  "$site_root/manifest.json" \
  "$bundle_root/stack.config/components.json" \
  "$site_root/components.lock.json"

build_merged_compose "$bundle_root" "$bundle_root/docker-compose.yml" "$site_root/manifest.json"
docker compose -f "$bundle_root/docker-compose.yml" config --quiet --no-interpolate >/dev/null

PATH="$fake_bin:$PATH" "$ROOT_DIR/scripts/deploy/render-runtime.sh" \
  --bundle-root "$bundle_root" \
  --deploy-root "$tmp_root/bundle" \
  --site-manifest "$site_root/manifest.json" \
  --runtime-root "$runtime_root" \
  --skip-compose-validate >/dev/null

caddy_file="$tmp_root/bundle/runtime/configs/caddy/Caddyfile"
keycloak_configure="$tmp_root/bundle/runtime/configs/keycloak/configure-runtime.sh"

assert_contains "$caddy_file" 'reverse_proxy keycloak:8080' "core Keycloak route"
assert_contains "$caddy_file" 'reverse_proxy onboarding:8080' "core onboarding route"
assert_contains "$caddy_file" 'webservices core stack' "core apex fallback"

assert_not_contains "$caddy_file" 'reverse_proxy (vaultwarden|grafana|homepage:3000|bookstack|matrix-authentication-service|mastodon|jupyterhub|homeassistant|search-service|chatgpt-connector|kopia)' "disabled app Caddy upstream"
assert_not_contains "$keycloak_configure" 'ensure_confidential_client "(bookstack|vaultwarden|matrix|planka|forgejo|mastodon|sogo|jellyfin|donetick|erpnext)' "disabled app Keycloak client"
validate_caddy_file "$caddy_file"

cat > "$site_root/manifest.json" <<'EOF_FULL_MANIFEST'
{
  "site": "component-test",
  "stackConfig": "stack.config.yaml",
  "secretStore": "webservices.sops.json",
  "components": ["full"]
}
EOF_FULL_MANIFEST

component_selection_write_metadata \
  "$site_root/manifest.json" \
  "$bundle_root/stack.config/components.json" \
  "$site_root/components.lock.json"

build_merged_compose "$bundle_root" "$bundle_root/docker-compose.full.yml" "$site_root/manifest.json"
docker compose -f "$bundle_root/docker-compose.full.yml" config --quiet --no-interpolate >/dev/null
cp "$bundle_root/docker-compose.full.yml" "$bundle_root/docker-compose.yml"

PATH="$fake_bin:$PATH" "$ROOT_DIR/scripts/deploy/render-runtime.sh" \
  --bundle-root "$bundle_root" \
  --deploy-root "$tmp_root/bundle" \
  --site-manifest "$site_root/manifest.json" \
  --runtime-root "$runtime_root" \
  --skip-compose-validate >/dev/null

assert_contains "$caddy_file" 'reverse_proxy vaultwarden:80' "full Vaultwarden route"
assert_contains "$caddy_file" 'reverse_proxy homepage:3000' "full Homepage route"
assert_contains "$keycloak_configure" 'ensure_confidential_client "vaultwarden"' "full Vaultwarden Keycloak client"
assert_not_contains "$caddy_file" 'webservices-component-(start|end)' "component marker"
validate_caddy_file "$caddy_file"

printf '[component-selection-test] ok\n' >&2
