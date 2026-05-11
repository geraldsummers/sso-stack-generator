#!/usr/bin/env bash
set -euo pipefail
ruby main.rb | tee output.txt
grep -qx 'RUBY_OK' output.txt
