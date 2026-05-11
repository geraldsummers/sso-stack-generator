#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
g++ -std=c++17 -O2 -Wall -Wextra -o build/cpp-demo main.cpp
./build/cpp-demo | tee build/output.txt
grep -qx 'CPP_OK' build/output.txt
