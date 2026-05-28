#!/usr/bin/env bash
set -euo pipefail

mkdir -p "${PROGRESSION_RUNTIME_DIR:-/runtime/progression}"
exec java -jar /app/progression.jar serve
