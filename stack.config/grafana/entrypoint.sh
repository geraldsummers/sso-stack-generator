#!/bin/sh
set -eu

if [ -d /provisioning-src ]; then
  mkdir -p /etc/grafana/provisioning/dashboards /etc/grafana/provisioning/datasources
  find /etc/grafana/provisioning/dashboards -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  find /etc/grafana/provisioning/datasources -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -R /provisioning-src/. /etc/grafana/provisioning/
  chown -R grafana:root /etc/grafana/provisioning
fi

if [ -n "${GF_DATABASE_PASSWORD:-}" ]; then
  runtime_config="/tmp/grafana-runtime.ini"
  cp "${GF_PATHS_CONFIG:-/etc/grafana/grafana.ini}" "$runtime_config"
  cat >> "$runtime_config" <<EOF

[database]
type = ${GF_DATABASE_TYPE:-postgres}
host = ${GF_DATABASE_HOST:-postgres:5432}
name = ${GF_DATABASE_NAME:-grafana}
user = ${GF_DATABASE_USER:-grafana}
password = ${GF_DATABASE_PASSWORD}
ssl_mode = ${GF_DATABASE_SSL_MODE:-disable}
conn_max_lifetime = ${GF_DATABASE_CONN_MAX_LIFETIME:-12000}
EOF
  chown grafana:root "$runtime_config" 2>/dev/null || true
  chmod 600 "$runtime_config"
  export GF_PATHS_CONFIG="$runtime_config"
  unset GF_DATABASE_TYPE GF_DATABASE_HOST GF_DATABASE_NAME GF_DATABASE_USER GF_DATABASE_PASSWORD GF_DATABASE_SSL_MODE GF_DATABASE_CONN_MAX_LIFETIME
fi

exec /run.sh
