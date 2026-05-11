#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
rustc main.rs -o build/rust-demo
./build/rust-demo | tee build/output.txt
grep -qx 'RUST_OK' build/output.txt
