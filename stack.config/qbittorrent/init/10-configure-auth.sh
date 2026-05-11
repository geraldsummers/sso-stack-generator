#!/usr/bin/with-contenv bash
set -euo pipefail

CONF_DIR="${QBITTORRENT_CONF_DIR:-/config/qBittorrent}"
CONF_FILE="$CONF_DIR/qBittorrent.conf"
CONFIG_OWNER="${QBITTORRENT_CONFIG_OWNER:-abc:abc}"
echo "[qbittorrent-init] Enforcing reverse-proxy WebUI auth settings..."
mkdir -p "$CONF_DIR"

resolve_caddy_whitelist() {
    getent ahostsv4 caddy 2>/dev/null \
        | awk '{ print $1 }' \
        | sort -u \
        | paste -sd, -
}

upsert_preference() {
    local key="$1"
    local value="$2"
    local tmp_file

    tmp_file="$(mktemp)"
    QB_KEY="$key" QB_VALUE="$value" awk '
        BEGIN {
            key = ENVIRON["QB_KEY"]
            value = ENVIRON["QB_VALUE"]
        }

        $0 == "[Preferences]" {
            print
            if (!inserted) {
                print value
                inserted = 1
            }
            next
        }

        index($0, key "=") == 1 {
            if (!inserted) {
                print value
                inserted = 1
            }
            next
        }

        { print }

        END {
            if (!inserted) {
                print value
            }
        }
    ' "$CONF_FILE" > "$tmp_file"
    mv "$tmp_file" "$CONF_FILE"
}

if [ ! -f "$CONF_FILE" ]; then
    echo "[qbittorrent-init] Applying pre-configured settings..."
    cat > "$CONF_FILE" << 'EOF'
[AutoRun]
enabled=false
program=
[BitTorrent]
Session\AddTorrentStopped=false
Session\DefaultSavePath=/downloads/
Session\Port=6881
Session\QueueingSystemEnabled=true
Session\SSL\Port=49582
Session\ShareLimitAction=Stop
Session\TempPath=/downloads/incomplete/
[LegalNotice]
Accepted=true
[Meta]
MigrationVersion=8
[Network]
PortForwardingEnabled=false
Proxy\HostnameLookupEnabled=false
Proxy\Profiles\BitTorrent=true
Proxy\Profiles\Misc=true
Proxy\Profiles\RSS=true
[Preferences]
Connection\PortRangeMin=6881
Connection\UPnP=false
Downloads\SavePath=/downloads/
Downloads\TempPath=/downloads/incomplete/
WebUI\Address=*
WebUI\ServerDomains=*
WebUI\AuthSubnetWhitelistEnabled=true
WebUI\AuthSubnetWhitelist=127.0.0.1
WebUI\BypassLocalAuth=true
EOF
fi

if ! grep -q "^\[Preferences\]" "$CONF_FILE"; then
    printf '\n[Preferences]\n' >> "$CONF_FILE"
fi

caddy_whitelist="$(resolve_caddy_whitelist)"
if [ -z "$caddy_whitelist" ]; then
    caddy_whitelist="127.0.0.1"
    echo "[qbittorrent-init] WARNING: could not resolve attached container subnets; WebUI bypass will only trust localhost" >&2
fi

upsert_preference 'WebUI\AuthSubnetWhitelistEnabled' 'WebUI\AuthSubnetWhitelistEnabled=true'
upsert_preference 'WebUI\AuthSubnetWhitelist' "WebUI\\AuthSubnetWhitelist=${caddy_whitelist}"
upsert_preference 'WebUI\BypassLocalAuth' 'WebUI\BypassLocalAuth=true'

chown -R "$CONFIG_OWNER" "$CONF_DIR"
chmod 644 "$CONF_FILE"
echo "[qbittorrent-init] WebUI auth bypass enabled for attached container subnets: ${caddy_whitelist}"
