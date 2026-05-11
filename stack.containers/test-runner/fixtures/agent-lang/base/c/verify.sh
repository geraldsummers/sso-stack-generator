#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
gcc -Wall -Wextra -O2 -o build/c-demo main.c
./build/c-demo | tee build/output.txt
grep -qx 'C_OK' build/output.txt
