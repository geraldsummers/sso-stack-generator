#!/usr/bin/with-contenv bash
set -euo pipefail

PHP_LOCAL_INI="/config/php/php-local.ini"
PHP_FPM_OVERRIDE="/config/php/www2.conf"
NGINX_SITE_CONF="/config/nginx/site-confs/default.conf"

mkdir -p "$(dirname "$PHP_LOCAL_INI")" "$(dirname "$PHP_FPM_OVERRIDE")"
touch "$PHP_LOCAL_INI" "$PHP_FPM_OVERRIDE"

set_ini_value() {
  local file="$1"
  local key="$2"
  local value="$3"

  if grep -Eq "^[;[:space:]]*${key}[[:space:]]*=" "$file"; then
    sed -i -E "s|^[;[:space:]]*${key}[[:space:]]*=.*|${key} = ${value}|" "$file"
  else
    printf '\n%s = %s\n' "$key" "$value" >> "$file"
  fi
}

set_fpm_value() {
  local file="$1"
  local key="$2"
  local value="$3"

  if ! grep -Eq '^\[www\]$' "$file"; then
    printf '[www]\n' >> "$file"
  fi

  if grep -Eq "^[;[:space:]]*${key}[[:space:]]*=" "$file"; then
    sed -i -E "s|^[;[:space:]]*${key}[[:space:]]*=.*|${key} = ${value}|" "$file"
  else
    printf '%s = %s\n' "$key" "$value" >> "$file"
  fi
}

set_ini_value "$PHP_LOCAL_INI" "max_execution_time" "300"
set_ini_value "$PHP_LOCAL_INI" "max_input_time" "300"
set_ini_value "$PHP_LOCAL_INI" "default_socket_timeout" "300"
set_fpm_value "$PHP_FPM_OVERRIDE" "request_terminate_timeout" "300s"

if [ -f "$NGINX_SITE_CONF" ] && ! grep -q 'fastcgi_read_timeout 300s;' "$NGINX_SITE_CONF"; then
  sed -i '/include \/etc\/nginx\/fastcgi_params;/a\
        fastcgi_read_timeout 300s;\
        fastcgi_send_timeout 300s;' "$NGINX_SITE_CONF"
fi

echo "[custom-init] Hardened BookStack nginx/php-fpm timeouts for long API writes"
