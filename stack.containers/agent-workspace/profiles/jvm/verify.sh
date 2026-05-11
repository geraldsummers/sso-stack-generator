#!/usr/bin/env bash
set -euo pipefail

resolve_cmd() {
    local cmd="$1"
    shift
    if command -v "$cmd" >/dev/null 2>&1; then
        command -v "$cmd"
        return 0
    fi
    local candidate
    for candidate in "$@"; do
        if [ -x "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

JAVA_BIN="$(resolve_cmd java /usr/bin/java)" || {
    echo "jvm profile verify: java not found (PATH=$PATH)" >&2
    exit 1
}
JAVAC_BIN="$(resolve_cmd javac /usr/bin/javac)" || {
    echo "jvm profile verify: javac not found (PATH=$PATH)" >&2
    exit 1
}
KOTLINC_BIN="$(resolve_cmd kotlinc /opt/kotlin/current/bin/kotlinc /opt/kotlin/kotlinc/bin/kotlinc /usr/local/bin/kotlinc)" || {
    echo "jvm profile verify: kotlinc not found (PATH=$PATH)" >&2
    exit 1
}

"$JAVA_BIN" -version >/dev/null 2>&1
"$JAVAC_BIN" -version >/dev/null 2>&1
"$KOTLINC_BIN" -version >/dev/null 2>&1
