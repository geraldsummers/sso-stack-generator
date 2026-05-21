#!/usr/bin/env bash
set -euo pipefail
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
DOTNET_INSTALL_SCRIPT_SHA256="${DOTNET_INSTALL_SCRIPT_SHA256:-082f7685e156738a1b2e2ed8381a621870d4ce8e8c59278034556f05c186eb2e}"
curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$tmpdir/dotnet-install.sh"
echo "${DOTNET_INSTALL_SCRIPT_SHA256}  $tmpdir/dotnet-install.sh" | sha256sum -c -
sudo bash "$tmpdir/dotnet-install.sh" --channel 8.0 --install-dir /usr/share/dotnet
sudo ln -sf /usr/share/dotnet/dotnet /usr/local/bin/dotnet
