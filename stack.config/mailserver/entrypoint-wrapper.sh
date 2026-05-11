#!/bin/bash
set -euo pipefail

ensure_mail_account() {
    local email="$1"
    local password="$2"

    if [[ -z "$email" || -z "$password" ]]; then
        return 0
    fi

    if setup email list 2>/dev/null | awk '{print $1}' | grep -Fxiq "$email"; then
        setup email update "$email" "$password" >/dev/null
    else
        setup email add "$email" "$password" >/dev/null
    fi
}

ensure_mail_account "${STACK_ADMIN_EMAIL:-}" "${STACK_ADMIN_PASSWORD:-}"
ensure_mail_account "postmaster@${MAIL_DOMAIN:-${DOMAIN}}" "${STACK_ADMIN_PASSWORD:-}"
ensure_mail_account "vaultwarden@${MAIL_DOMAIN:-${DOMAIN}}" "${VAULTWARDEN_SMTP_PASSWORD:-}"
ensure_mail_account "mastodon@${MAIL_DOMAIN:-${DOMAIN}}" "${MASTODON_SMTP_PASSWORD:-}"

cat > /etc/dovecot/conf.d/99-webservices-hardening.conf <<'EOF'
disable_plaintext_auth = yes
ssl = required
EOF
/bin/bash /tmp/docker-mailserver/find-certs.sh
