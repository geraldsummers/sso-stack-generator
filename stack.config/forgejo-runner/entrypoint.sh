#!/bin/sh
set -eu

RUNNER_STATE_FILE="/data/.runner"
RUNNER_TOKEN_FILE="/runner-token/token"
RUNNER_DAEMON_LOG="/tmp/forgejo-runner-daemon.log"
FORGEJO_RUNNER_CONFIG="${FORGEJO_RUNNER_CONFIG:-/etc/forgejo-runner/config.yaml}"
FORGEJO_RUNNER_MAX_RECOVERY_ATTEMPTS="${FORGEJO_RUNNER_MAX_RECOVERY_ATTEMPTS:-2}"

log() {
  printf '[forgejo-runner] %s\n' "$*"
}

wait_for_forgejo() {
  until nc -z forgejo 3000; do
    log 'Waiting for Forgejo API...'
    sleep 5
  done
  log '✓ Forgejo API is reachable'
}

wait_for_runner_token() {
  log 'Waiting for Forgejo to generate token...'
  retries=60
  i=1
  while [ "$i" -le "$retries" ]; do
    if [ -f "$RUNNER_TOKEN_FILE" ]; then
      runner_token="$(cat "$RUNNER_TOKEN_FILE")"
      if [ -n "$runner_token" ]; then
        log '✓ Token found!'
        return 0
      fi
    fi
    log "Waiting for token file... ($i/$retries)"
    sleep 2
    i=$((i + 1))
  done

  log '❌ Failed to obtain runner token after retries'
  log 'Token file may be empty or missing'
  return 1
}

register_runner() {
  runner_token="$(cat "$RUNNER_TOKEN_FILE")"
  log 'Registering runner with Forgejo...'
  forgejo-runner register \
    --no-interactive \
    --instance "$FORGEJO_INSTANCE_URL" \
    --token "$runner_token" \
    --name "$FORGEJO_RUNNER_NAME" \
    --labels "$FORGEJO_RUNNER_LABELS"
  log '✓ Runner registered successfully'
}

run_runner_daemon() {
  : >"$RUNNER_DAEMON_LOG"
  set +e
  forgejo-runner daemon --config "$FORGEJO_RUNNER_CONFIG" >"$RUNNER_DAEMON_LOG" 2>&1
  daemon_rc=$?
  set -e
  cat "$RUNNER_DAEMON_LOG"
  return "$daemon_rc"
}

is_stale_registration() {
  grep -q 'unauthenticated: unregistered runner' "$RUNNER_DAEMON_LOG"
}

main() {
  log 'Starting runner initialization...'
  wait_for_forgejo

  attempt=1
  while [ "$attempt" -le "$FORGEJO_RUNNER_MAX_RECOVERY_ATTEMPTS" ]; do
    if [ ! -f "$RUNNER_STATE_FILE" ]; then
      wait_for_runner_token
      register_runner
    else
      log 'Runner already registered'
    fi

    log 'Starting runner daemon...'
    if run_runner_daemon; then
      exit 0
    fi

    if is_stale_registration && [ "$attempt" -lt "$FORGEJO_RUNNER_MAX_RECOVERY_ATTEMPTS" ]; then
      log 'Stale runner registration detected; removing local state and re-registering'
      rm -f "$RUNNER_STATE_FILE"
      attempt=$((attempt + 1))
      continue
    fi

    exit 1
  done
}

main "$@"
