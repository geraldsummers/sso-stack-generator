#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
go build -o build/go-demo main.go
./build/go-demo | tee build/output.txt
grep -qx 'GO_OK' build/output.txt
