#!/usr/bin/env bash
set -euo pipefail

export STEPPATH="${WORKSPACE_PROVISIONER_STEPPATH:-/data/step}"
mkdir -p "$STEPPATH" "${WORKSPACE_PROVISIONER_DB_DIR:-/data/db}"

if [ ! -f "$STEPPATH/config/ca.json" ]; then
  mkdir -p "$STEPPATH/config" "$STEPPATH/certs" "$STEPPATH/secrets"
  pw_file="$(mktemp)"
  prov_pw_file="$(mktemp)"
  trap 'rm -f "$pw_file" "$prov_pw_file"' EXIT
  if [ -n "${WORKSPACE_PROVISIONER_CA_PASSWORD:-}" ]; then
    printf '%s' "$WORKSPACE_PROVISIONER_CA_PASSWORD" > "$pw_file"
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n' > "$pw_file"
  fi
  if [ -n "${WORKSPACE_PROVISIONER_CA_PROVISIONER_PASSWORD:-}" ]; then
    printf '%s' "$WORKSPACE_PROVISIONER_CA_PROVISIONER_PASSWORD" > "$prov_pw_file"
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n' > "$prov_pw_file"
  fi

  step ca init \
    --ssh \
    --deployment-type standalone \
    --name "${WORKSPACE_PROVISIONER_CA_NAME:-webservices-workspaces}" \
    --dns "${WORKSPACE_PROVISIONER_CA_DNS_NAME:-workspaces.local}" \
    --address ":9000" \
    --provisioner "${WORKSPACE_PROVISIONER_CA_PROVISIONER:-workspace-provisioner}" \
    --password-file "$pw_file" \
    --provisioner-password-file "$prov_pw_file" \
    --with-ca-url "${WORKSPACE_PROVISIONER_CA_URL:-https://workspaces.local}" >/tmp/workspace-provisioner-step-init.log 2>&1

  cp "$prov_pw_file" "$STEPPATH/secrets/provisioner-password.txt"
  chmod 600 "$STEPPATH/secrets/provisioner-password.txt"
fi

exec java -jar /app/workspace-provisioner.jar
