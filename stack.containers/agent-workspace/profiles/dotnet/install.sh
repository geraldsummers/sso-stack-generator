#!/usr/bin/env bash
set -euo pipefail
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
DOTNET_INSTALL_SCRIPT_SHA256="${DOTNET_INSTALL_SCRIPT_SHA256:-102a6849303713f15462bb28eb10593bf874bbeec17122e0522f10a3b57ce442}"
curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$tmpdir/dotnet-install.sh"
echo "${DOTNET_INSTALL_SCRIPT_SHA256}  $tmpdir/dotnet-install.sh" | sha256sum -c -
sudo bash "$tmpdir/dotnet-install.sh" --channel 8.0 --install-dir /usr/share/dotnet
sudo ln -sf /usr/share/dotnet/dotnet /usr/local/bin/dotnet
