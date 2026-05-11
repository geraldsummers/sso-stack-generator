#!/usr/bin/with-contenv bash
set -euo pipefail

ENV_FILE="/config/www/.env"
echo "Waiting for BookStack .env file..."
for i in {1..60}; do
    if [ -f "$ENV_FILE" ]; then
        break
    fi
    sleep 1
done
if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: BookStack .env file not found"
    exit 0
fi
if [ -z "${DB_HOST:-}" ]; then
    DB_HOST=$(grep "^DB_HOST=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "${DB_DATABASE:-}" ]; then
    DB_DATABASE=$(grep "^DB_DATABASE=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "${DB_USERNAME:-}" ]; then
    DB_USERNAME=$(grep "^DB_USERNAME=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "${DB_PASSWORD:-}" ]; then
    DB_PASSWORD=$(grep "^DB_PASSWORD=" "$ENV_FILE" | cut -d'=' -f2)
fi
if [ -z "${DB_HOST:-}" ] || [ -z "${DB_DATABASE:-}" ] || [ -z "${DB_USERNAME:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
    echo "ERROR: Could not extract database credentials from environment or .env"
    exit 0
fi
echo "Database configuration:"
echo "  Host: $DB_HOST"
echo "  Database: $DB_DATABASE"
echo "  User: $DB_USERNAME"
echo "Waiting for database connection..."
for i in {1..30}; do
    if mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -e "SELECT 1" >/dev/null 2>&1; then
        echo "Database connection established"
        break
    fi
    sleep 2
done

drop_restricted_pending="$(
    mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -sN -e "
        SELECT CASE
            WHEN EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'migrations'
            ) AND EXISTS (
                SELECT 1 FROM migrations
                WHERE migration = '2022_10_08_104202_drop_entity_restricted_field'
            ) THEN 0
            ELSE 1
        END
    " 2>/dev/null || echo "0"
)"

legacy_schema_present="$(
    mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -sN -e "
        SELECT CASE
            WHEN EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'roles'
            ) AND EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'users'
            ) THEN 1
            ELSE 0
        END
    " 2>/dev/null || echo "0"
)"

restricted_repair_tables_present="$(
    mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -sN -e "
        SELECT CASE
            WHEN EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'entity_permissions'
            ) AND EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'books'
            ) AND EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'chapters'
            ) AND EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'pages'
            ) AND EXISTS (
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'bookshelves'
            ) THEN 1
            ELSE 0
        END
    " 2>/dev/null || echo "0"
)"

echo "Applying legacy schema compatibility fixes (if needed)..."
if [ "$legacy_schema_present" = "1" ]; then
    mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" <<'EOF' >/dev/null
ALTER TABLE roles ADD COLUMN IF NOT EXISTS system_name VARCHAR(191) NULL AFTER id;
ALTER TABLE roles ADD COLUMN IF NOT EXISTS hidden TINYINT(1) NOT NULL DEFAULT 0 AFTER system_name;
ALTER TABLE users ADD COLUMN IF NOT EXISTS system_name VARCHAR(191) NULL;
CREATE INDEX IF NOT EXISTS users_system_name_index ON users(system_name);
SET @roles_has_name := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'roles' AND column_name = 'name'
);
SET @update_roles_sql := IF(
    @roles_has_name = 1,
    'UPDATE roles SET system_name = name WHERE (system_name IS NULL OR system_name = '''') AND name IS NOT NULL AND name != ''''',
    'SELECT 1'
);
PREPARE update_roles_stmt FROM @update_roles_sql;
EXECUTE update_roles_stmt;
DEALLOCATE PREPARE update_roles_stmt;
SET @perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'permissions');
SET @role_perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'role_permissions');
SET @rename_perm_sql := IF(@perm_exists = 1 AND @role_perm_exists = 0, 'RENAME TABLE permissions TO role_permissions', 'SELECT 1');
PREPARE rename_perm_stmt FROM @rename_perm_sql;
EXECUTE rename_perm_stmt;
DEALLOCATE PREPARE rename_perm_stmt;
SET @restr_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'restrictions');
SET @entity_perm_exists := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'entity_permissions');
SET @rename_restr_sql := IF(@restr_exists = 1 AND @entity_perm_exists = 0, 'RENAME TABLE restrictions TO entity_permissions', 'SELECT 1');
PREPARE rename_restr_stmt FROM @rename_restr_sql;
EXECUTE rename_restr_stmt;
DEALLOCATE PREPARE rename_restr_stmt;
EOF
else
    echo "Legacy schema tables not present yet, skipping compatibility SQL"
fi

if [ "$drop_restricted_pending" = "1" ] && [ "$restricted_repair_tables_present" = "1" ]; then
    echo "Restoring legacy restricted columns for pending BookStack migration..."
    mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" <<'EOF' >/dev/null
ALTER TABLE books ADD COLUMN IF NOT EXISTS restricted TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE chapters ADD COLUMN IF NOT EXISTS restricted TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE pages ADD COLUMN IF NOT EXISTS restricted TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE bookshelves ADD COLUMN IF NOT EXISTS restricted TINYINT(1) NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS books_restricted_index ON books(restricted);
CREATE INDEX IF NOT EXISTS chapters_restricted_index ON chapters(restricted);
CREATE INDEX IF NOT EXISTS pages_restricted_index ON pages(restricted);
CREATE INDEX IF NOT EXISTS bookshelves_restricted_index ON bookshelves(restricted);
UPDATE books b
SET restricted = 1
WHERE EXISTS (
    SELECT 1 FROM entity_permissions ep
    WHERE ep.entity_type = 'book' AND ep.entity_id = b.id
);
UPDATE chapters c
SET restricted = 1
WHERE EXISTS (
    SELECT 1 FROM entity_permissions ep
    WHERE ep.entity_type = 'chapter' AND ep.entity_id = c.id
);
UPDATE pages p
SET restricted = 1
WHERE EXISTS (
    SELECT 1 FROM entity_permissions ep
    WHERE ep.entity_type = 'page' AND ep.entity_id = p.id
);
UPDATE bookshelves s
SET restricted = 1
WHERE EXISTS (
    SELECT 1 FROM entity_permissions ep
    WHERE ep.entity_type = 'bookshelf' AND ep.entity_id = s.id
);
EOF
elif [ "$drop_restricted_pending" = "1" ]; then
    echo "Restricted-column repair skipped because prerequisite tables are not present yet"
fi

if [ -f "/app/www/artisan" ]; then
    echo "Running BookStack migrations..."
    if ! (cd /app/www && php artisan migrate --force >/tmp/bookstack-migrate.log 2>&1); then
        echo "ERROR: BookStack migrations failed"
        cat /tmp/bookstack-migrate.log
        exit 1
    fi
fi
CONFIGURED=$(mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" -sN -e "SELECT COUNT(*) FROM settings WHERE setting_key='permissions_configured_bookstack_hardening_v2'" 2>/dev/null || echo "0")
if [ "$CONFIGURED" != "0" ]; then
    echo "Permissions already configured, skipping..."
    exit 0
fi
echo "Configuring BookStack role permissions..."
mariadb -h "$DB_HOST" -u "$DB_USERNAME" -p"$DB_PASSWORD" --protocol=TCP "$DB_DATABASE" <<'EOF'
-- Ensure Admin role exists and has full permissions
UPDATE roles
SET
    system_name = 'admin',
    display_name = 'Admin',
    description = 'Full system administrator with read/write access everywhere',
    mfa_enforced = 0
WHERE id = 1 OR system_name = 'admin';
-- Grant all permissions to Admin role (role_id = 1)
DELETE FROM permission_role WHERE role_id = 1;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT id, 1 FROM role_permissions;
-- Ensure standard Editor role exists
INSERT IGNORE INTO roles (display_name, description, external_auth_id, mfa_enforced, system_name)
VALUES ('Editor', 'Standard user who can create content and read public content', '', 0, 'editor');
-- Get the editor role ID
SET @editor_role_id = (SELECT id FROM roles WHERE system_name = 'editor' OR display_name = 'Editor' ORDER BY id ASC LIMIT 1);
-- Configure Editor role permissions:
-- Can create books, chapters, pages
-- Can edit their own content
-- Can read public content
DELETE FROM permission_role WHERE role_id = @editor_role_id;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT rp.id, @editor_role_id FROM role_permissions rp
WHERE rp.name IN (
    'access-api',
    'content-export',
    'page-create-all',
    'page-create-own',
    'page-view-all',
    'page-view-own',
    'page-update-own',
    'page-delete-own',
    'chapter-create-all',
    'chapter-create-own',
    'chapter-view-all',
    'chapter-view-own',
    'chapter-update-own',
    'chapter-delete-own',
    'book-create-all',
    'book-view-all',
    'book-view-own',
    'book-update-own',
    'book-delete-own',
    'bookshelf-view-all',
    'bookshelf-view-own',
    'bookshelf-create-all',
    'bookshelf-update-own',
    'bookshelf-delete-own',
    'image-create-all',
    'image-update-own',
    'image-delete-own',
    'attachment-create-all',
    'attachment-update-own',
    'attachment-delete-own',
    'comment-create-all',
    'comment-update-own',
    'comment-delete-own'
);
-- Set Editor as the default role for new users
UPDATE roles SET mfa_enforced = 0 WHERE system_name = 'editor';
-- Update system settings to use appropriate registration defaults
INSERT INTO settings (setting_key, value) VALUES ('registration-role', @editor_role_id)
ON DUPLICATE KEY UPDATE value = @editor_role_id;
-- Dedicated non-admin automation identity for pipeline API writes.
INSERT IGNORE INTO roles (display_name, description, external_auth_id, mfa_enforced, system_name)
VALUES ('Automation', 'Service role for webservices pipeline BookStack API writes', '', 0, 'automation');
SET @automation_role_id = (SELECT id FROM roles WHERE system_name = 'automation' LIMIT 1);
DELETE FROM permission_role WHERE role_id = @automation_role_id;
INSERT IGNORE INTO permission_role (permission_id, role_id)
SELECT rp.id, @automation_role_id FROM role_permissions rp
WHERE rp.name IN (
    'access-api',
    'content-export',
    'page-create-all',
    'page-view-all',
    'page-update-all',
    'chapter-create-all',
    'chapter-view-all',
    'chapter-update-all',
    'book-create-all',
    'book-view-all',
    'book-update-all',
    'bookshelf-view-all',
    'image-create-all',
    'image-update-all'
);
INSERT IGNORE INTO users (name, email, password, remember_token, created_at, updated_at, email_confirmed, image_id, external_auth_id, slug, system_name)
VALUES (
    'webservices Automation',
    'webservices-automation@localhost',
    '',
    NULL,
    NOW(),
    NOW(),
    1,
    0,
    '',
    'webservices-automation',
    NULL
);
INSERT IGNORE INTO role_user (user_id, role_id)
SELECT u.id, @automation_role_id
FROM users u
WHERE u.email = 'webservices-automation@localhost';
-- Get or create public role
INSERT IGNORE INTO roles (display_name, description, external_auth_id, mfa_enforced, system_name)
VALUES ('Public', 'Public guest access', '', 0, 'public');
SET @public_role_id = (SELECT id FROM roles WHERE system_name = 'public' LIMIT 1);
INSERT IGNORE INTO users (name, email, password, remember_token, created_at, updated_at, email_confirmed, image_id, external_auth_id, slug, system_name)
VALUES ('Guest', 'guest@example.com', '', NULL, NOW(), NOW(), 1, 0, '', 'guest', 'public');
INSERT IGNORE INTO role_user (user_id, role_id)
SELECT u.id, @public_role_id
FROM users u
WHERE u.system_name = 'public';
-- Public guest access is intentionally disabled by default. BookStack is reached
-- through OIDC for humans, while automation uses API tokens bound to the service user.
DELETE FROM permission_role WHERE role_id = @public_role_id;
-- Promote the first OIDC user (usually the admin who set up the system) to Admin role
SET @first_oidc_user = (SELECT id FROM users WHERE external_auth_id != '' AND email != '' ORDER BY id ASC LIMIT 1);
-- Remove existing role assignment for this user
DELETE FROM role_user WHERE user_id = @first_oidc_user;
-- Assign Admin role (role_id = 1) to first OIDC user
INSERT IGNORE INTO role_user (user_id, role_id)
SELECT @first_oidc_user, 1
WHERE @first_oidc_user IS NOT NULL;
-- Mark permissions as configured
INSERT INTO settings (setting_key, value) VALUES ('permissions_configured', '1')
ON DUPLICATE KEY UPDATE value = '1';
INSERT INTO settings (setting_key, value) VALUES ('permissions_configured_bookstack_hardening_v2', '1')
ON DUPLICATE KEY UPDATE value = '1';
EOF
if [ $? -eq 0 ]; then
    echo "BookStack permissions configured successfully!"
    echo "- Admin role: Full read/write access everywhere"
    echo "- Editor role (default): Create/edit own content, read public content"
    echo "- Automation role: Scoped pipeline API write access"
    echo "- Public role: No content access by default"
else
    echo "ERROR: Failed to configure permissions"
fi
exit 0
