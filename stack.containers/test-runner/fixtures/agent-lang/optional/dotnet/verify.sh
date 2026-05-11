#!/usr/bin/env bash
set -euo pipefail
dotnet run --project DotnetDemo.csproj | tee output.txt
grep -qx 'DOTNET_OK' output.txt
