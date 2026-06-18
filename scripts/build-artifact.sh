#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LIB_DIR="$SCRIPT_DIR/lib"
# shellcheck source=scripts/lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=scripts/lib/build-metadata.sh
source "$LIB_DIR/build-metadata.sh"
# shellcheck source=scripts/lib/components.sh
source "$LIB_DIR/components.sh"
# shellcheck source=scripts/lib/external-modules.sh
source "$LIB_DIR/external-modules.sh"

TARGET="//:web_services_release"

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./scripts/build-artifact.sh

Runs local build/test steps and prints the built Bazel release artifact path.
EOF_USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument for build-artifact.sh: $1"
      ;;
  esac
  shift
done

require_cmd bazel
require_cmd git
require_cmd npm
require_cmd tar

cd "$SOURCE_ROOT"
require_clean_git_tree "$SOURCE_ROOT"

contract_test_root="$SOURCE_ROOT"
contract_test_tmp=""
if [ -d "$EXTERNAL_MODULES_MATERIALIZED_DIR" ] && find "$EXTERNAL_MODULES_MATERIALIZED_DIR" -type f -print -quit | grep -q .; then
  contract_test_tmp="$(mktemp -d)"
  cleanup_contract_test_root() {
    [ -z "$contract_test_tmp" ] || rm -rf "$contract_test_tmp"
  }
  trap cleanup_contract_test_root EXIT
  for root in global.settings stack.compose stack.config stack.containers stack.kotlin stack.js stack.systemd scripts docs; do
    if [ -e "$SOURCE_ROOT/$root" ]; then
      cp -a "$SOURCE_ROOT/$root" "$contract_test_tmp/$root"
    fi
  done
  external_modules_overlay_into "$contract_test_tmp"
  component_catalog_merge_external "$contract_test_tmp/stack.config/components.json"
  contract_test_root="$contract_test_tmp"
fi

local_test_dir="$SOURCE_ROOT/stack.containers/test-runner/playwright-tests"
if [ -f "$local_test_dir/package.json" ]; then
  if [ ! -d "$local_test_dir/node_modules" ] || [ ! -f "$local_test_dir/node_modules/jest-cli/package.json" ] || [ ! -f "$local_test_dir/node_modules/typescript/package.json" ] || [ ! -f "$local_test_dir/node_modules/@playwright/test/package.json" ]; then
    log "installing TypeScript test dependencies"
    (cd "$local_test_dir" && npm ci) >&2
  fi
  log "running TypeScript compile check"
  (cd "$local_test_dir" && npm run build) >&2
  log "running TypeScript unit tests"
  (cd "$local_test_dir" && npm run test:unit) >&2
fi

log "running component selection checks"
"$SCRIPT_DIR/test-component-selection.sh" >&2

log "running external module checks"
"$SCRIPT_DIR/test-external-modules.sh" >&2

log "running service contract checks"
WEBSERVICES_CONTRACT_ROOT="$contract_test_root" "$SCRIPT_DIR/test-service-contracts.sh" >&2

log "running contract report checks"
WEBSERVICES_CONTRACT_ROOT="$contract_test_root" "$SCRIPT_DIR/test-contract-reports.sh" >&2

log "running mount diagnostics checks"
"$SCRIPT_DIR/test-mount-diagnostics.sh" >&2

log "running Jellyfin ffmpeg wrapper checks"
"$SCRIPT_DIR/test-jellyfin-ffmpeg-websafe.sh" >&2

log "running deploy-state guard checks"
"$SCRIPT_DIR/test-deploy-state.sh" >&2

log "running scoped deploy planner checks"
"$SCRIPT_DIR/test-deploy-scope.sh" >&2

log "running env-file security checks"
"$SCRIPT_DIR/test-env-file-security.sh" >&2

log "running docs link checks"
"$SCRIPT_DIR/test-docs.sh" >&2

log "running Gradle tests and shadow jars"
./gradlew test shadowJar --no-daemon >&2

log "packaging immutable release artifact with Bazel"
bazel build "$TARGET" >&2

artifact_rel="$(bazel cquery --output=files "$TARGET" | tail -n 1)"
[ -n "$artifact_rel" ] || die "failed to resolve Bazel artifact path for $TARGET"
if [[ "$artifact_rel" = /* ]]; then
  artifact_path="$artifact_rel"
else
  execution_root="$(bazel info execution_root)"
  artifact_path="$execution_root/$artifact_rel"
fi

required_artifact_paths=(
  "./stack.config/progression/tasks/bookstack-mvp.json"
  "./stack.config/progression/dashboards/bookstack-mvp.json"
  "./stack.kotlin/progression/src/main/resources/static/index.html"
)
artifact_listing="$(mktemp)"
tar -tf "$artifact_path" > "$artifact_listing"
for required_artifact_path in "${required_artifact_paths[@]}"; do
  if ! grep -Fxq "$required_artifact_path" "$artifact_listing"; then
    die "release artifact missing required test-runner fixture: $required_artifact_path"
  fi
done
rm -f "$artifact_listing"

mkdir -p "$SOURCE_ROOT/out"
write_source_build_metadata_json "$SOURCE_ROOT" "$SOURCE_ROOT/out/latest-build.json"
printf '%s\n' "$artifact_path"
