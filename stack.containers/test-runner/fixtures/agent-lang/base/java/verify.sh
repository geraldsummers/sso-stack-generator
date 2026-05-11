#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
javac -d build Main.java
java -cp build Main | tee build/output.txt
grep -qx 'JAVA_OK' build/output.txt
