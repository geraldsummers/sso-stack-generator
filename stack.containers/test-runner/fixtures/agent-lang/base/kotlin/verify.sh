#!/usr/bin/env bash
set -euo pipefail
mkdir -p build
kotlinc Main.kt -include-runtime -d build/kotlin-demo.jar
java -jar build/kotlin-demo.jar | tee build/output.txt
grep -qx 'KOTLIN_OK' build/output.txt
