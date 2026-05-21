#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
LIB_DIR="$SCRIPT_DIR/lib"
# shellcheck source=scripts/lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=scripts/lib/build-metadata.sh
source "$LIB_DIR/build-metadata.sh"

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

log "running deploy-state guard checks"
"$SCRIPT_DIR/test-deploy-state.sh" >&2

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

mkdir -p "$SOURCE_ROOT/out"
write_source_build_metadata_json "$SOURCE_ROOT" "$SOURCE_ROOT/out/latest-build.json"
printf '%s\n' "$artifact_path"
