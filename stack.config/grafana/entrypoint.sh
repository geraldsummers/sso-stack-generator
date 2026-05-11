#!/bin/sh
set -eu

if [ -d /provisioning-src ]; then
  mkdir -p /etc/grafana/provisioning/dashboards /etc/grafana/provisioning/datasources
  find /etc/grafana/provisioning/dashboards -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  find /etc/grafana/provisioning/datasources -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -R /provisioning-src/. /etc/grafana/provisioning/
  chown -R grafana:root /etc/grafana/provisioning
fi

exec /run.sh
