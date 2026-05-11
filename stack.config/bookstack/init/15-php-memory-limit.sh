#!/usr/bin/with-contenv bash
set -eu

PHP_LOCAL_INI="/config/php/php-local.ini"
mkdir -p "$(dirname "$PHP_LOCAL_INI")"
touch "$PHP_LOCAL_INI"

if grep -q '^memory_limit' "$PHP_LOCAL_INI"; then
  sed -i 's/^memory_limit.*/memory_limit = 512M/' "$PHP_LOCAL_INI"
else
  printf '\nmemory_limit = 512M\n' >> "$PHP_LOCAL_INI"
fi

echo "[custom-init] Set BookStack PHP memory_limit to 512M"
