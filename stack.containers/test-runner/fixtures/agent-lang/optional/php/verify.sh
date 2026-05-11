#!/usr/bin/env bash
set -euo pipefail
php index.php | tee output.txt
grep -qx 'PHP_OK' output.txt
