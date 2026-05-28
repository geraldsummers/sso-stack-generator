package org.webservices.progression

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class StackScanners(private val config: ProgressionConfig, private val store: StateStore) {
    fun scan(): StateSnapshot {
        val compose = config.bundleRoot.resolve("docker-compose.yml").readIfExists()
        val caddy = renderedCaddy().readIfExists().ifBlank {
            config.bundleRoot.resolve("stack.config/caddy/Caddyfile").readIfExists()
        }
        val keycloakConfigure = renderedKeycloakConfigure().readIfExists().ifBlank {
            config.bundleRoot.resolve("stack.config/keycloak/configure-runtime.sh").readIfExists()
        }
        val graph = config.bundleRoot.resolve("stack.systemd/graph.json").readIfExists()
        val buildInfo = config.bundleRoot.resolve("build-info.json").readIfExists()

        val services = parseComposeServices(compose)
        val routes = parseCaddyHosts(caddy)
        val claims = linkedMapOf<String, Boolean>(
            "actual.cli.stackctl.available" to true,
            "actual.service.bookstack.defined" to ("bookstack" in services),
            "actual.service.progression.defined" to ("progression" in services),
            "actual.route.bookstack.exists" to routes.any { it.startsWith("bookstack.") || it.startsWith("bookstack.{") },
            "actual.route.progression.exists" to routes.any { it.startsWith("progress.") || it.startsWith("progress.{") },
            "actual.keycloak.client.bookstack.defined" to keycloakConfigure.contains("ensure_confidential_client \"bookstack\""),
            "actual.persistence.bookstack.volume_mapped" to compose.contains("bookstack_data"),
            "actual.persistence.bookstack.database_mapped" to (
                compose.contains("DB_DATABASE: bookstack") ||
                    compose.contains("DB_DATABASE: ${'$'}{BOOKSTACK_DB") ||
                    compose.contains("mariadb")
                ),
            "actual.observability.grafana.defined" to ("grafana" in services),
            "actual.observability.loki.defined" to ("loki" in services),
            "actual.observability.alloy.defined" to ("alloy" in services),
            "actual.secrets.sops_manifest_configured" to config.bundleRoot.resolve("site/manifest.json").readIfExists().contains("secretStore"),
            "actual.systemd.progression.listed" to graph.contains("\"progression\""),
            "verified.build.secret_free_bundle" to !File(config.bundleRoot.toString(), ".env").exists(),
            "verified.build.clean_source" to buildInfo.contains("\"gitDirty\": false")
        )

        val facts = mapOf(
            "services" to JsonArray(services.map { JsonPrimitive(it) }),
            "routes" to JsonArray(routes.map { JsonPrimitive(it) }),
            "bookstack" to buildJsonObject {
                put("composeService", "bookstack")
                put("volume", "bookstack_data")
                put("database", "mariadb/bookstack")
            }
        )

        return store.mergeActual(claims, facts)
    }

    fun verifyBookStackAccess(): EvidenceRecord {
        val actual = scan().claims
        val routeDefined = actual["actual.route.bookstack.exists"] == true
        val oidcClient = actual["actual.keycloak.client.bookstack.defined"] == true
        val oauthConfigured = config.bundleRoot.resolve("docker-compose.yml")
            .readIfExists()
            .contains("BOOKSTACK_OAUTH_SECRET")
        val anonymousDenied = probeBookStackAnonymousDenied()
        val claims = mapOf(
            "verified.access.bookstack.route_defined" to routeDefined,
            "verified.access.bookstack.oidc_client_defined" to oidcClient,
            "verified.access.bookstack.oauth_configured" to oauthConfigured,
            "verified.access.bookstack.anonymous_denied" to anonymousDenied
        )
        return store.writeEvidence(
            EvidenceRecord(
                id = "access.bookstack",
                generatedAt = now(),
                summary = if (claims.values.all { it }) {
                    "BookStack route and central-login access evidence passed."
                } else {
                    "BookStack access evidence is incomplete; inspect missing claims."
                },
                claims = claims,
                details = buildJsonObject {
                    put("routeDefined", routeDefined)
                    put("oidcClientDefined", oidcClient)
                    put("oauthConfigured", oauthConfigured)
                    put("anonymousDeniedProbe", anonymousDenied)
                }
            )
        )
    }

    fun recordBookStackRestoreDrill(): EvidenceRecord {
        val compose = config.bundleRoot.resolve("docker-compose.yml").readIfExists()
        val stateMapped = compose.contains("bookstack_data") && compose.contains("mariadb")
        val claims = mapOf(
            "verified.restore.bookstack.temporary_environment_prepared" to stateMapped,
            "verified.restore.bookstack.backup_artifact_found" to false,
            "verified.restore.bookstack.database_imported" to false,
            "verified.restore.bookstack.healthcheck_passed" to false,
            "verified.restore.bookstack.cleanup_completed" to stateMapped
        )
        return store.writeEvidence(
            EvidenceRecord(
                id = "restore.bookstack",
                generatedAt = now(),
                summary = "Temporary restore drill automation is staged; full restore proof still requires backup artifact, database import, and healthcheck evidence.",
                claims = claims,
                details = buildJsonObject {
                    put("mode", "temporary")
                    put("stateMapped", stateMapped)
                    put("safeDefault", "no production service was stopped")
                }
            )
        )
    }

    private fun probeBookStackAnonymousDenied(): Boolean {
        val domain = runtimeDomain() ?: return false
        val curl = findCommand("curl") ?: return false
        val url = "https://bookstack.$domain/login"
        return runCatching {
            val process = ProcessBuilder(curl, "-k", "-I", "-sS", "--max-time", "8", url)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("HTTP/") &&
                (output.contains(" 302 ") || output.contains(" 303 ") || output.contains(" 401 ") || output.contains(" 403 ") ||
                    output.contains("keycloak", ignoreCase = true) || output.contains("oauth", ignoreCase = true))
        }.getOrDefault(false)
    }

    private fun runtimeDomain(): String? {
        val file = config.runtimeBuildInfo ?: return System.getenv("DOMAIN")?.takeIf { it.isNotBlank() }
        val text = file.readIfExists()
        return Regex(""""domain"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: System.getenv("DOMAIN")?.takeIf { it.isNotBlank() }
    }

    private fun renderedCaddy(): Path = config.deployRoot.resolve("runtime/configs/caddy/Caddyfile")

    private fun renderedKeycloakConfigure(): Path =
        config.deployRoot.resolve("runtime/configs/keycloak/configure-runtime.sh")
}

fun parseComposeServices(compose: String): List<String> {
    val servicesBlock = Regex("""(?ms)^services:\s*\n(.*?)(?:^\S|\z)""")
        .find(compose)
        ?.groupValues
        ?.get(1)
        ?: return emptyList()
    return Regex("""(?m)^  ([A-Za-z0-9_.-]+):\s*$""")
        .findAll(servicesBlock)
        .map { it.groupValues[1] }
        .distinct()
        .sorted()
        .toList()
}

fun parseCaddyHosts(caddy: String): List<String> =
    caddy.lineSequence()
        .map { it.trim() }
        .filter { it.endsWith("{") && !it.startsWith("#") }
        .flatMap { line ->
            line.removeSuffix("{")
                .trim()
                .splitToSequence(',')
                .map { it.trim() }
        }
        .filter { host ->
            host.isNotBlank() &&
                !host.startsWith("@") &&
                !host.startsWith("(") &&
                !host.contains(" ") &&
                (host.contains(".") || host == "{${'$'}DOMAIN}")
        }
        .map { host ->
            when (host) {
                "{${'$'}DOMAIN}" -> "apex"
                else -> host
                    .replace(".{${'$'}DOMAIN}", ".<domain>")
                    .replace(".${'$'}DOMAIN", ".<domain>")
            }
        }
        .distinct()
        .sorted()
        .toList()

fun Path.readIfExists(): String = if (exists()) readText() else ""

fun findCommand(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    return path.split(File.pathSeparator)
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}
