#!/bin/bash
set -euo pipefail

pgdata="${PGDATA:-/var/lib/postgresql/data}"
mkdir -p "$pgdata"
chown postgres:postgres "$pgdata"
chmod 700 "$pgdata"

exec docker-entrypoint.sh "$@"
