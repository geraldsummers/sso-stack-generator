#!/usr/bin/env sh
set -eu

SIMULATED_IP="${1:-203.0.113.250}"
SIMULATED_DURATION="${2:-5m}"

cscli decisions add \
  --ip "$SIMULATED_IP" \
  --type ban \
  --reason webservices-simulated-alert \
  --duration "$SIMULATED_DURATION"

cscli decisions list --contained
