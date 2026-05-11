#!/bin/bash
set -euo pipefail

escape_sed_replacement() {
    printf '%s' "$1" | sed 's/[&|\\]/\\&/g'
}

install_caddy_ca() {
    local ca_src="/ca/caddy-ca.crt"
    local ca_dst="/usr/local/share/ca-certificates/caddy-ca.crt"

    if [ ! -d /ca ]; then
        return 0
    fi

    for _ in $(seq 1 30); do
        if [ -s "$ca_src" ]; then
            echo "Installing Caddy CA into system trust store..."
            cp "$ca_src" "$ca_dst"
            chmod 644 "$ca_dst"
            if command -v update-ca-certificates >/dev/null 2>&1; then
                update-ca-certificates
            fi
            return 0
        fi
        sleep 1
    done

    echo "Caddy CA not available yet, continuing without installing it"
}

install_sso_plugin() {
    local plugin_src="/opt/jellyfin-plugins/sso-authentication_4.0.0.4"
    local plugin_dst="/config/plugins/SSO Authentication_4.0.0.4"

    if [ ! -f "$plugin_src/SSO-Auth.dll" ]; then
        echo "SSO Authentication plugin payload missing at $plugin_src" >&2
        return 1
    fi

    if [ ! -f "$plugin_dst/SSO-Auth.dll" ]; then
        echo "Installing Jellyfin SSO Authentication plugin..."
        mkdir -p "$plugin_dst"
        cp -a "$plugin_src/." "$plugin_dst/"
    fi
}

mark_startup_wizard_completed() {
    local system_config="/config/config/system.xml"

    mkdir -p /config/config
    if [ -f "$system_config" ]; then
        sed -i 's|<IsStartupWizardCompleted>false</IsStartupWizardCompleted>|<IsStartupWizardCompleted>true</IsStartupWizardCompleted>|' "$system_config"
        return 0
    fi

    echo "Jellyfin system.xml not present yet; leaving first-run database initialization to Jellyfin"
}

promote_configured_admin_users() {
    local database="/config/data/jellyfin.db"
    local admin_users="${JELLYFIN_ADMIN_USERS:-${STACK_ADMIN_USER:-}}"

    if [ -z "$admin_users" ] || [ ! -s "$database" ]; then
        return 0
    fi

    if ! command -v sqlite3 >/dev/null 2>&1; then
        echo "sqlite3 is unavailable; cannot reconcile Jellyfin admin users" >&2
        return 0
    fi

    IFS=',' read -ra users <<< "$admin_users"
    for username in "${users[@]}"; do
        username="$(printf '%s' "$username" | xargs)"
        if [ -z "$username" ]; then
            continue
        fi
        username_sql="${username//\'/\'\'}"

        sqlite3 "$database" <<SQL
INSERT INTO Permissions (Kind, Permission_Permissions_Guid, RowVersion, UserId, Value)
SELECT 0, NULL, 0, Id, 1
FROM Users
WHERE lower(Username) = lower('$username_sql')
  AND NOT EXISTS (
    SELECT 1 FROM Permissions WHERE Permissions.UserId = Users.Id AND Kind = 0
  );
UPDATE Permissions
SET Value = 1
WHERE Kind = 0
  AND UserId IN (SELECT Id FROM Users WHERE lower(Username) = lower('$username_sql'));
SQL
    done
}

configure_playback_policy() {
    local database="/config/data/jellyfin.db"
    local enable_remuxing="${JELLYFIN_ENABLE_PLAYBACK_REMUXING:-false}"
    local remuxing_value="0"

    if [ ! -s "$database" ]; then
        return 0
    fi

    if ! command -v sqlite3 >/dev/null 2>&1; then
        echo "sqlite3 is unavailable; cannot reconcile Jellyfin playback policy" >&2
        return 0
    fi

    case "$(printf '%s' "$enable_remuxing" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on)
            remuxing_value="1"
            ;;
    esac

    sqlite3 "$database" <<SQL
INSERT INTO Permissions (Kind, Permission_Permissions_Guid, RowVersion, UserId, Value)
SELECT 19, NULL, 0, Id, $remuxing_value
FROM Users
WHERE NOT EXISTS (
    SELECT 1 FROM Permissions WHERE Permissions.UserId = Users.Id AND Kind = 19
);
UPDATE Permissions
SET Value = $remuxing_value
WHERE Kind = 19
  AND UserId IN (SELECT Id FROM Users);
SQL
}

render_branding() {
    : "${DOMAIN:?ERROR: DOMAIN not set}"

    cat > /config/config/branding.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<BrandingOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <LoginDisclaimer><![CDATA[<form action="https://jellyfin.${DOMAIN}/sso/OID/start/keycloak"><button class="raised block emby-button button-submit">Sign in with Keycloak</button></form>]]></LoginDisclaimer>
  <CustomCss><![CDATA[
.disclaimerContainer {
  display: block;
}
.manualLoginForm,
.readOnlyContent form:not([action*="/sso/"]),
.loginSection form:not([action*="/sso/"]),
.loginSection .inputContainer,
.loginSection .checkboxContainer,
.loginSection .forgotPassword,
.loginSection .raised.button-submit:not([formaction*="/sso/"]),
.loginSection button[type="submit"]:not([formaction*="/sso/"]) {
  display: none !important;
}
.disclaimerContainer form {
  margin: 1rem 0 0;
}
.disclaimerContainer .button-submit {
  width: 100%;
}
  ]]></CustomCss>
  <SplashscreenEnabled>false</SplashscreenEnabled>
</BrandingOptions>
EOF
}

echo "Waiting for config directory..."
while [ ! -d /config ]; do
    sleep 1
done

install_caddy_ca
install_sso_plugin
mark_startup_wizard_completed
promote_configured_admin_users
configure_playback_policy
render_branding

mkdir -p /config/plugins/configurations
mkdir -p /config/data
if [ -f /config-templates/SSO-Auth.xml ]; then
    : "${DOMAIN:?ERROR: DOMAIN not set}"
    : "${JELLYFIN_OIDC_SECRET:?ERROR: JELLYFIN_OIDC_SECRET not set}"

    echo "Rendering SSO-Auth configuration..."
    domain_escaped="$(escape_sed_replacement "$DOMAIN")"
    secret_escaped="$(escape_sed_replacement "$JELLYFIN_OIDC_SECRET")"
    sed \
        -e "s|{{DOMAIN}}|$domain_escaped|g" \
        -e "s|{{JELLYFIN_OIDC_SECRET}}|$secret_escaped|g" \
        /config-templates/SSO-Auth.xml \
        > /config/plugins/configurations/SSO-Auth.xml
    chmod 0644 /config/plugins/configurations/SSO-Auth.xml
    echo "SSO-Auth configuration rendered"
fi
echo "Starting Jellyfin..."
exec /jellyfin/jellyfin \
    --datadir /config \
    --cachedir /cache \
    --ffmpeg /usr/local/bin/jellyfin-ffmpeg-websafe
