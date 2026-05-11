#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PLAYWRIGHT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$PLAYWRIGHT_DIR/../../.." && pwd)

have_auth_env() {
  [ -n "${STACK_ADMIN_USER:-}" ] \
    && [ -n "${STACK_ADMIN_PASSWORD:-}" ] \
    && [ -n "${STACK_ADMIN_EMAIL:-}" ]
}

missing_auth_env_vars() {
  missing=""

  for var_name in \
    STACK_ADMIN_USER \
    STACK_ADMIN_PASSWORD \
    STACK_ADMIN_EMAIL
  do
    var_value=$(printenv "$var_name" 2>/dev/null || true)
    if [ -z "$var_value" ]; then
      if [ -n "$missing" ]; then
        missing="$missing, "
      fi
      missing="$missing$var_name"
    fi
  done

  printf '%s\n' "$missing"
}

load_runtime_env_file() {
  runtime_env_file="$1"

  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*)
        continue
        ;;
    esac

    key=${line%%=*}
    value=${line#*=}
    value=$(printf '%s' "$value" | sed 's/\\$\\$/\\$/g')
    export "$key=$value"
  done < "$runtime_env_file"
}

resolve_runtime_env_file() {
  if [ -n "${STACK_RUNTIME_ENV_FILE:-}" ] && [ -f "$STACK_RUNTIME_ENV_FILE" ]; then
    printf '%s\n' "$STACK_RUNTIME_ENV_FILE"
    return 0
  fi

  for candidate in \
    "$REPO_ROOT/dist/runtime/stack.env" \
    "$REPO_ROOT/runtime/stack.env"
  do
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

loaded_runtime_env_file=""

if [ "${PW_SKIP_GLOBAL_SETUP:-0}" != "1" ] && ! have_auth_env; then
  if runtime_env_file=$(resolve_runtime_env_file); then
    load_runtime_env_file "$runtime_env_file"
    export STACK_RUNTIME_ENV_FILE="$runtime_env_file"
    loaded_runtime_env_file="$runtime_env_file"
  fi
fi

if [ "${PW_SKIP_GLOBAL_SETUP:-0}" != "1" ] && ! have_auth_env; then
  echo "Playwright auth suites require rendered runtime env." >&2
  missing_vars=$(missing_auth_env_vars)
  if [ -n "$loaded_runtime_env_file" ]; then
    echo "Loaded runtime env file: $loaded_runtime_env_file" >&2
  fi
  if [ -n "$missing_vars" ]; then
    echo "Missing vars: $missing_vars" >&2
  fi
  echo "Build the bundle first with ./build.sh --manifest /path/to/manifest.json, then deploy it so ./runtime/stack.env exists." >&2
  echo "Then run via: ./run-tests.sh ts-e2e from the deployed bundle, or export STACK_RUNTIME_ENV_FILE=/path/to/runtime/stack.env." >&2
  exit 1
fi

exec "$@"
