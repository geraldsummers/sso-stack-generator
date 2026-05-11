#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/scripts/lib/common.sh"
# shellcheck source=scripts/lib/site-manifest.sh
source "$SCRIPT_DIR/scripts/lib/site-manifest.sh"
# shellcheck source=scripts/lib/compose.sh
source "$SCRIPT_DIR/scripts/lib/compose.sh"

SITE_MANIFEST_PATH=""
DIST_DIR="$SCRIPT_DIR/dist"
OUT_DIR="$SCRIPT_DIR/out"

usage() {
  cat <<'EOF_USAGE'
Usage:
  ./build.sh --manifest <path-to-manifest.json>

Builds the local deployable bundle in ./dist without decrypting secrets.
EOF_USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --manifest)
      [ "$#" -ge 2 ] || die "--manifest requires a value"
      SITE_MANIFEST_PATH="$2"
      shift
      ;;
    *)
      die "unknown argument for build.sh: $1"
      ;;
  esac
  shift
done

[ -n "$SITE_MANIFEST_PATH" ] || die "missing required --manifest <path-to-manifest.json>"
site_manifest_path="$(resolve_site_manifest_file "$SITE_MANIFEST_PATH")"

artifact_path="$("$SCRIPT_DIR/scripts/build-artifact.sh")"
mkdir -p "$OUT_DIR"
printf '%s\n' "$artifact_path" > "$OUT_DIR/latest-artifact-path.txt"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/build"

tar -xf "$artifact_path" -C "$DIST_DIR/build"
cp "$OUT_DIR/latest-build.json" "$DIST_DIR/build/build-info.json"
cp "$artifact_path" "$DIST_DIR/build/artifact.tar"
sha256sum "$DIST_DIR/build/artifact.tar" | awk '{print $1}' > "$DIST_DIR/build/artifact.sha256"

stage_site_manifest_bundle "$site_manifest_path" "$DIST_DIR/build/site"
build_merged_compose "$DIST_DIR/build" "$DIST_DIR/build/docker-compose.yml"
rewrite_compose_runtime_paths "$DIST_DIR/build/docker-compose.yml"
rewrite_compose_runtime_paths "$DIST_DIR/build/stack.compose/test-runners.yml"
log "validating generated docker-compose.yml"
validate_generated_compose "$DIST_DIR/build" "$DIST_DIR/build/docker-compose.yml"
log "rendering systemd user units"
"$SCRIPT_DIR/scripts/deploy/render-systemd-user.sh" \
  --bundle-root "$DIST_DIR/build" \
  --output-dir "$DIST_DIR/build/systemd-user"

cat > "$DIST_DIR/deploy.sh" <<'EOF_DEPLOY'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
exec "$SCRIPT_DIR/build/scripts/deploy.sh" "$@"
EOF_DEPLOY

cat > "$DIST_DIR/verify.sh" <<'EOF_VERIFY'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
exec "$SCRIPT_DIR/build/scripts/verify.sh" "$@"
EOF_VERIFY

cat > "$DIST_DIR/run-tests.sh" <<'EOF_RUN_TESTS'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
export DIST_DIR="$SCRIPT_DIR/build"
exec "$SCRIPT_DIR/build/stack.containers/test-runner/run-tests.sh" "$@"
EOF_RUN_TESTS

chmod +x "$DIST_DIR/deploy.sh" "$DIST_DIR/verify.sh" "$DIST_DIR/run-tests.sh"

# The bundled artifact uses normalized mtimes for reproducibility. Refresh them in dist/
# so rsync -a notices changed files even when size stays the same.
find "$DIST_DIR" -exec touch {} +

log "dist ready at $DIST_DIR"
