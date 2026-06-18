#!/bin/bash
set -e
echo "========================================="
echo "MariaDB Initialization Starting"
echo "========================================="
echo "Checking environment variables..."
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    echo "ERROR: MYSQL_ROOT_PASSWORD not set"
    exit 1
fi
if [ -z "$MARIADB_BOOKSTACK_PASSWORD" ]; then
    echo "ERROR: MARIADB_BOOKSTACK_PASSWORD not set"
    exit 1
fi
if [ -z "$MARIADB_SEAFILE_PASSWORD" ]; then
    echo "ERROR: MARIADB_SEAFILE_PASSWORD not set"
    exit 1
fi
if [ -z "$MARIADB_AGENT_PASSWORD" ]; then
    echo "ERROR: MARIADB_AGENT_PASSWORD not set"
    exit 1
fi
echo "✓ All required environment variables present"
echo ""
echo "Environment variables available for substitution:"
echo "  MYSQL_ROOT_PASSWORD: [SET]"
echo "  MARIADB_BOOKSTACK_PASSWORD: [SET]"
echo "  MARIADB_SEAFILE_PASSWORD: [SET]"
echo "  MARIADB_AGENT_PASSWORD: [SET]"
echo ""
sql_hex() {
    printf '%s' "$1" | od -An -tx1 | tr -d ' \n'
}

BOOKSTACK_PASSWORD_HEX="$(sql_hex "$MARIADB_BOOKSTACK_PASSWORD")"
SEAFILE_PASSWORD_HEX="$(sql_hex "$MARIADB_SEAFILE_PASSWORD")"
AGENT_PASSWORD_HEX="$(sql_hex "$MARIADB_AGENT_PASSWORD")"

echo "Building SQL initialization with escaped password literals..."
read -r -d '' SUBSTITUTED_SQL <<EOSQL || true
CREATE DATABASE IF NOT EXISTS bookstack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS ccnet_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seafile_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seahub_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET @bookstack_password = CONVERT(UNHEX('$BOOKSTACK_PASSWORD_HEX') USING utf8mb4);
SET @seafile_password = CONVERT(UNHEX('$SEAFILE_PASSWORD_HEX') USING utf8mb4);
SET @agent_password = CONVERT(UNHEX('$AGENT_PASSWORD_HEX') USING utf8mb4);

SET @sql = CONCAT('CREATE USER IF NOT EXISTS ''bookstack''@''%'' IDENTIFIED BY ', QUOTE(@bookstack_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = CONCAT('ALTER USER ''bookstack''@''%'' IDENTIFIED BY ', QUOTE(@bookstack_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('CREATE USER IF NOT EXISTS ''seafile''@''%'' IDENTIFIED BY ', QUOTE(@seafile_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = CONCAT('ALTER USER ''seafile''@''%'' IDENTIFIED BY ', QUOTE(@seafile_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

GRANT ALL PRIVILEGES ON bookstack.* TO 'bookstack'@'%';
GRANT ALL PRIVILEGES ON ccnet_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seafile_db.* TO 'seafile'@'%';
GRANT ALL PRIVILEGES ON seahub_db.* TO 'seafile'@'%';

SET @sql = CONCAT('CREATE USER IF NOT EXISTS ''agent_observer''@''%'' IDENTIFIED BY ', QUOTE(@agent_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = CONCAT('ALTER USER ''agent_observer''@''%'' IDENTIFIED BY ', QUOTE(@agent_password));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

GRANT SELECT ON bookstack.* TO 'agent_observer'@'%';
GRANT SELECT ON ccnet_db.* TO 'agent_observer'@'%';
GRANT SELECT ON seafile_db.* TO 'agent_observer'@'%';
GRANT SELECT ON seahub_db.* TO 'agent_observer'@'%';

FLUSH PRIVILEGES;
EOSQL
echo "Generated SQL (first 10 lines, passwords hidden):"
echo "$SUBSTITUTED_SQL" | head -10 | sed 's/UNHEX([^)]*)/UNHEX([HIDDEN])/g; s/IDENTIFIED BY.*$/IDENTIFIED BY [HIDDEN]/'
echo ""
echo "Executing SQL initialization..."
echo "$SUBSTITUTED_SQL" | mariadb -u root -p"${MYSQL_ROOT_PASSWORD}"
echo ""
echo "========================================="
echo "MariaDB Initialization Complete!"
echo "========================================="
echo ""
echo "Created databases:"
mariadb -u root -p"${MYSQL_ROOT_PASSWORD}" -e "SHOW DATABASES;" | grep -E "bookstack|ccnet_db|seafile_db|seahub_db" || echo "  (checking...)"
echo ""
echo "Created users:"
mariadb -u root -p"${MYSQL_ROOT_PASSWORD}" -e "SELECT User, Host FROM mysql.user WHERE User IN ('bookstack', 'seafile', 'agent_observer');"
echo ""
echo "Writing init completion marker..."
touch /var/lib/mysql/.init_complete
echo "✓ Init marker written to /var/lib/mysql/.init_complete"
echo ""
