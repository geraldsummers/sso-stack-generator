#!/usr/bin/env bash
set -euo pipefail
rm -rf dist
mkdir -p dist
tsc --target ES2020 --module commonjs --outDir dist src/main.ts
node dist/main.js | tee dist/output.txt
grep -qx 'TS_JS_OK' dist/output.txt
