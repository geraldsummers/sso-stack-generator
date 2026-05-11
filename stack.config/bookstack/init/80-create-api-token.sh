#!/usr/bin/with-contenv bash
set -uo pipefail
echo "[BookStack API Token] Waiting for BookStack initialization..."
for i in {1..60}; do
    if [ -f "/app/www/artisan" ] && [ -f "/config/www/.env" ]; then
        echo "[BookStack API Token] BookStack is ready"
        break
    fi
    sleep 2
done
if [ ! -f "/app/www/artisan" ]; then
    echo "[BookStack API Token] ERROR: artisan not found, skipping token creation"
    exit 0
fi
if [ -z "${BOOKSTACK_API_TOKEN_ID:-}" ] || [ -z "${BOOKSTACK_API_TOKEN_SECRET:-}" ]; then
    echo "[BookStack API Token] ERROR: BOOKSTACK_API_TOKEN_ID or BOOKSTACK_API_TOKEN_SECRET not set"
    echo "[BookStack API Token] These should be generated at build time by build-webservices-v2.main.kts"
    echo "[BookStack API Token] Please rebuild your stack to generate API tokens"
    exit 1
fi
BOOKSTACK_TOKEN_ID="$BOOKSTACK_API_TOKEN_ID"
BOOKSTACK_TOKEN_SECRET="$BOOKSTACK_API_TOKEN_SECRET"
BOOKSTACK_TOKEN_TTL_DAYS="${BOOKSTACK_API_TOKEN_TTL_DAYS:-90}"
if ! printf '%s' "$BOOKSTACK_TOKEN_TTL_DAYS" | grep -Eq '^[0-9]+$'; then
    echo "[BookStack API Token] ERROR: BOOKSTACK_API_TOKEN_TTL_DAYS must be a positive integer"
    exit 1
fi
BOOKSTACK_TOKEN_EXPIRES_AT="${BOOKSTACK_API_TOKEN_EXPIRES_AT:-$(BOOKSTACK_TOKEN_TTL_DAYS="$BOOKSTACK_TOKEN_TTL_DAYS" php -r '$days = max(1, (int) getenv("BOOKSTACK_TOKEN_TTL_DAYS")); echo (new DateTimeImmutable("now", new DateTimeZone("UTC")))->modify("+{$days} days")->format("Y-m-d H:i:s");')}"
echo "[BookStack API Token] Using build-time generated API token"
echo "[BookStack API Token] Token ID: $BOOKSTACK_TOKEN_ID"
echo "[BookStack API Token] Token expires at: $BOOKSTACK_TOKEN_EXPIRES_AT UTC"
cd /app/www
echo "[BookStack API Token] Ensuring migrations are up to date..."
php artisan migrate --force >/dev/null 2>&1 || true
HAS_API_TOKENS=$(php artisan tinker --execute="echo \\Illuminate\\Support\\Facades\\Schema::hasTable('api_tokens') ? '1' : '0';" 2>/dev/null | tail -1)
if [ "$HAS_API_TOKENS" != "1" ]; then
    echo "[BookStack API Token] WARNING: api_tokens table is missing after migration attempt. Skipping token generation."
    exit 0
fi
echo "[BookStack API Token] Ensuring dedicated automation user exists..."
USER_ID=$(php artisan tinker --execute="
\$roleId = \\Illuminate\\Support\\Facades\\DB::table('roles')->where('system_name', 'automation')->value('id');
if (!\$roleId) { throw new RuntimeException('BookStack automation role is missing; run 50-configure-permissions.sh first'); }
\$userId = \\Illuminate\\Support\\Facades\\DB::table('users')->where('email', 'webservices-automation@localhost')->value('id');
if (!\$userId) {
    \$userId = \\Illuminate\\Support\\Facades\\DB::table('users')->insertGetId([
        'name' => 'webservices Automation',
        'email' => 'webservices-automation@localhost',
        'password' => '',
        'remember_token' => null,
        'created_at' => now(),
        'updated_at' => now(),
        'email_confirmed' => 1,
        'image_id' => 0,
        'external_auth_id' => '',
        'slug' => 'webservices-automation',
        'system_name' => null,
    ]);
}
\\Illuminate\\Support\\Facades\\DB::table('role_user')->insertOrIgnore(['user_id' => \$userId, 'role_id' => \$roleId]);
echo \$userId;
" 2>/tmp/bookstack-api-user.log | tail -1)
if [ -z "$USER_ID" ] || [ "$USER_ID" = "0" ]; then
    echo "[BookStack API Token] ERROR: Could not create or find automation user"
    cat /tmp/bookstack-api-user.log
    exit 1
fi
echo "[BookStack API Token] Using automation user ID: $USER_ID"
echo "[BookStack API Token] Removing old token if exists..."
php artisan tinker --execute="BookStack\Api\ApiToken::where('name', 'webservices Automation')->delete();" 2>/dev/null
echo "[BookStack API Token] Creating API token..."
ESCAPED_TOKEN_ID=$(php -r "echo addslashes('$BOOKSTACK_TOKEN_ID');")
ESCAPED_TOKEN_SECRET=$(php -r "echo addslashes('$BOOKSTACK_TOKEN_SECRET');")
ESCAPED_TOKEN_EXPIRES_AT=$(php -r "echo addslashes('$BOOKSTACK_TOKEN_EXPIRES_AT');")
CREATE_CMD="\$user = BookStack\Users\Models\User::find($USER_ID); "
CREATE_CMD+="\$token = new BookStack\Api\ApiToken(); "
CREATE_CMD+="\$token->user_id = \$user->id; "
CREATE_CMD+="\$token->name = 'webservices Automation'; "
CREATE_CMD+="\$token->token_id = '$ESCAPED_TOKEN_ID'; "
CREATE_CMD+="\$token->secret = \\Illuminate\\Support\\Facades\\Hash::make('$ESCAPED_TOKEN_SECRET'); "
CREATE_CMD+="\$token->expires_at = '$ESCAPED_TOKEN_EXPIRES_AT'; "
CREATE_CMD+="\$token->save(); "
CREATE_CMD+="echo 'SUCCESS';"
RESULT=$(php artisan tinker --execute="$CREATE_CMD" 2>&1 | tail -1)
if echo "$RESULT" | grep -q "SUCCESS"; then
    echo "[BookStack API Token] ✓ API token created successfully"
    echo "[BookStack API Token] Token ID: $BOOKSTACK_TOKEN_ID"
    echo "[BookStack API Token] Pipeline will use this token from environment variables"
else
    echo "[BookStack API Token] ERROR: Failed to create token"
    echo "$RESULT"
    exit 0
fi
