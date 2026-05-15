package org.webservices.testrunner.suites

import org.webservices.testrunner.framework.TestRunner
import java.util.UUID

const val STACK_RECOVERY_SUITE_NAME = "stack-recovery"

suspend fun TestRunner.recoveryTests() = suite("Recovery Drill Tests") {
    test("PostgreSQL logical dump restores into a disposable container") {
        requireTestdevRecoveryContext()
        val runId = recoveryRunId()
        val project = composeProjectName()
        val script = """
            set -euo pipefail
            project=${project.shellQuote()}
            run_id=${runId.shellQuote()}
            source="${'$'}{project}-postgres-1"
            target="webservices-recovery-pg-${'$'}run_id"
            volume="webservices_recovery_pg_${'$'}run_id"
            network="${'$'}{project}_postgres"
            cleanup() {
              docker rm -f "${'$'}target" >/dev/null 2>&1 || true
              docker volume rm "${'$'}volume" >/dev/null 2>&1 || true
            }
            trap cleanup EXIT
            image="$(docker inspect "${'$'}source" --format '{{.Config.Image}}')"
            docker exec "${'$'}source" sh -lc '
              set -eu
              psql -v ON_ERROR_STOP=1 -U "${'$'}POSTGRES_USER" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '\''recovery_drill'\''" | grep -q 1 ||
                psql -v ON_ERROR_STOP=1 -U "${'$'}POSTGRES_USER" -d postgres -c "CREATE DATABASE recovery_drill"
              psql -v ON_ERROR_STOP=1 -U "${'$'}POSTGRES_USER" -d recovery_drill -c "CREATE TABLE IF NOT EXISTS sentinel (id integer PRIMARY KEY, value text NOT NULL)"
              psql -v ON_ERROR_STOP=1 -U "${'$'}POSTGRES_USER" -d recovery_drill -c "INSERT INTO sentinel (id, value) VALUES (1, '\''postgres-recovery-ok'\'') ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value"
            '
            docker volume create "${'$'}volume" >/dev/null
            docker run -d \
              --name "${'$'}target" \
              --network "${'$'}network" \
              --label webservices.recovery.drill=true \
              -e POSTGRES_PASSWORD=recovery \
              -e POSTGRES_USER=postgres \
              -e POSTGRES_DB=postgres \
              -v "${'$'}volume:/var/lib/postgresql/data" \
              "${'$'}image" >/dev/null
            for i in $(seq 1 90); do
              if docker exec "${'$'}target" psql -v ON_ERROR_STOP=1 -U postgres -d postgres -tAc 'SELECT 1' >/dev/null 2>&1; then
                sleep 3
                docker exec "${'$'}target" psql -v ON_ERROR_STOP=1 -U postgres -d postgres -tAc 'SELECT 1' >/dev/null 2>&1 && break
              fi
              sleep 1
            done
            docker exec "${'$'}target" psql -v ON_ERROR_STOP=1 -U postgres -d postgres -tAc 'SELECT 1' >/dev/null
            docker exec "${'$'}target" createdb -U postgres recovery_drill
            docker exec "${'$'}source" sh -lc 'pg_dump -U "${'$'}POSTGRES_USER" -Fc recovery_drill' |
              docker exec -i "${'$'}target" pg_restore -U postgres -d recovery_drill --clean --if-exists --no-owner --no-acl
            restored="$(docker exec "${'$'}target" psql -U postgres -d recovery_drill -tAc 'SELECT value FROM sentinel WHERE id = 1')"
            test "${'$'}restored" = postgres-recovery-ok
        """.trimIndent()
        runRecoveryShell(script)
    }

    test("MariaDB logical dump restores into a disposable container") {
        requireTestdevRecoveryContext()
        val runId = recoveryRunId()
        val project = composeProjectName()
        val script = """
            set -euo pipefail
            project=${project.shellQuote()}
            run_id=${runId.shellQuote()}
            source="${'$'}{project}-mariadb-1"
            target="webservices-recovery-mariadb-${'$'}run_id"
            volume="webservices_recovery_mariadb_${'$'}run_id"
            network="${'$'}{project}_mariadb"
            cleanup() {
              docker rm -f "${'$'}target" >/dev/null 2>&1 || true
              docker volume rm "${'$'}volume" >/dev/null 2>&1 || true
            }
            trap cleanup EXIT
            image="$(docker inspect "${'$'}source" --format '{{.Config.Image}}')"
            docker exec "${'$'}source" sh -lc '
              set -eu
              mariadb --protocol=TCP -h 127.0.0.1 -uroot -p"${'$'}MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS recovery_drill"
              mariadb --protocol=TCP -h 127.0.0.1 -uroot -p"${'$'}MYSQL_ROOT_PASSWORD" recovery_drill -e "CREATE TABLE IF NOT EXISTS sentinel (id INT PRIMARY KEY, value VARCHAR(128) NOT NULL)"
              mariadb --protocol=TCP -h 127.0.0.1 -uroot -p"${'$'}MYSQL_ROOT_PASSWORD" recovery_drill -e "REPLACE INTO sentinel (id, value) VALUES (1, '\''mariadb-recovery-ok'\'')"
            '
            docker volume create "${'$'}volume" >/dev/null
            docker run -d \
              --name "${'$'}target" \
              --network "${'$'}network" \
              --label webservices.recovery.drill=true \
              -e MYSQL_ROOT_PASSWORD=recovery \
              -e MARIADB_ROOT_PASSWORD=recovery \
              -v "${'$'}volume:/var/lib/mysql" \
              "${'$'}image" >/dev/null
            for i in $(seq 1 120); do
              docker exec "${'$'}target" mariadb --protocol=TCP -h 127.0.0.1 -uroot -precovery -e 'SELECT 1' >/dev/null 2>&1 && break
              sleep 1
            done
            docker exec "${'$'}target" mariadb --protocol=TCP -h 127.0.0.1 -uroot -precovery -e 'SELECT 1' >/dev/null
            docker exec "${'$'}target" mariadb --protocol=TCP -h 127.0.0.1 -uroot -precovery -e 'CREATE DATABASE recovery_drill'
            docker exec "${'$'}source" sh -lc 'mariadb-dump --protocol=TCP -h 127.0.0.1 -uroot -p"${'$'}MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --triggers recovery_drill' |
              docker exec -i "${'$'}target" mariadb --protocol=TCP -h 127.0.0.1 -uroot -precovery recovery_drill
            restored="$(docker exec "${'$'}target" mariadb --protocol=TCP -h 127.0.0.1 -N -uroot -precovery recovery_drill -e 'SELECT value FROM sentinel WHERE id = 1')"
            test "${'$'}restored" = mariadb-recovery-ok
        """.trimIndent()
        runRecoveryShell(script)
    }
}

private fun composeProjectName(): String =
    System.getenv("TEST_RUNNER_COMPOSE_PROJECT_NAME").orEmpty()
        .ifBlank { System.getenv("COMPOSE_PROJECT_NAME").orEmpty() }
        .ifBlank { "webservices" }

private fun requireTestdevRecoveryContext() {
    val project = composeProjectName()
    require(project.startsWith("webservices_testdev_")) {
        "Recovery drills are destructive staging tests and must run under testdev; COMPOSE_PROJECT_NAME=$project"
    }
    require(System.getenv("TESTDEV_SKIP_GPU_INGESTION") == "1") {
        "Recovery drills must run through testdev-verify.sh so TESTDEV_SKIP_GPU_INGESTION=1 is set"
    }
}

private fun recoveryRunId(): String =
    UUID.randomUUID().toString().replace("-", "").take(12)

private fun runRecoveryShell(script: String) {
    val process = ProcessBuilder("bash", "-lc", script)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    require(exitCode == 0) {
        "Recovery drill command failed with exit code $exitCode\n$output"
    }
}

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"
