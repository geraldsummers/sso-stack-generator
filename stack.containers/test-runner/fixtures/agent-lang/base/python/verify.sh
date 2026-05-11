#!/usr/bin/env bash
set -euo pipefail
python3 main.py | tee output.txt
grep -qx 'PYTHON_OK' output.txt
