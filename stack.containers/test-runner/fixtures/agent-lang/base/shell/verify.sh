#!/usr/bin/env bash
set -euo pipefail
out_dir="${PWD}/build"
mkdir -p "$out_dir"
bash -lc 'printf "%s\n" SHELL_BASH_OK' | tee "$out_dir/bash.txt"
zsh -lc 'printf "%s\n" SHELL_ZSH_OK' | tee "$out_dir/zsh.txt"
grep -qx 'SHELL_BASH_OK' "$out_dir/bash.txt"
grep -qx 'SHELL_ZSH_OK' "$out_dir/zsh.txt"
