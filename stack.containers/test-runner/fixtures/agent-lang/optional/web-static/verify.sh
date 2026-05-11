#!/usr/bin/env bash
set -euo pipefail
tidy -v >/dev/null 2>&1 || tidy --version >/dev/null 2>&1
grep -q 'WEB_STATIC_OK' index.html
grep -q '\.ok' styles.css
