#!/bin/sh
set -eu

KNOWN_HOSTS_FILE="${ISOLATED_DOCKER_VM_KNOWN_HOSTS:-/home/tunnel/.ssh/known_hosts}"
LOCAL_SOCKET="${ISOLATED_DOCKER_VM_LOCAL_SOCKET:-/run/docker-labware/docker.sock}"
REMOTE_SOCKET="${ISOLATED_DOCKER_VM_REMOTE_SOCKET:-/var/run/docker.sock}"
IDENTITY_FILE="${ISOLATED_DOCKER_VM_IDENTITY_FILE:-}"

cleanup() {
    rm -f "$LOCAL_SOCKET"
}

trap cleanup EXIT INT TERM

if [ ! -f "$KNOWN_HOSTS_FILE" ]; then
    echo "ERROR: Known hosts file not found: ${KNOWN_HOSTS_FILE}" >&2
    echo "Populate known_hosts before starting tunnel (StrictHostKeyChecking=yes)." >&2
    exit 1
fi

if [ -z "${ISOLATED_DOCKER_VM_HOST:-}" ]; then
    echo "ERROR: ISOLATED_DOCKER_VM_HOST is not set" >&2
    exit 1
fi

SSH_ARGS="-o ExitOnForwardFailure=yes -o StrictHostKeyChecking=yes -o StreamLocalBindMask=0111 -o UserKnownHostsFile=${KNOWN_HOSTS_FILE} -o StreamLocalBindUnlink=yes -o ServerAliveInterval=60 -o ServerAliveCountMax=3 -N -L ${LOCAL_SOCKET}:${REMOTE_SOCKET}"
if [ -n "$IDENTITY_FILE" ]; then
    if [ ! -r "$IDENTITY_FILE" ]; then
        echo "ERROR: SSH identity file is not readable: ${IDENTITY_FILE}" >&2
        exit 1
    fi
    SSH_ARGS="${SSH_ARGS} -i ${IDENTITY_FILE}"
fi

mkdir -p "$(dirname "$LOCAL_SOCKET")"

echo "Connecting to ${ISOLATED_DOCKER_VM_HOST}..."
while true; do
    cleanup

    # shellcheck disable=SC2086
    ssh ${SSH_ARGS} "${ISOLATED_DOCKER_VM_HOST}"

    echo "Connection lost. Retrying in 5 seconds..."
    cleanup
    sleep 5
done
