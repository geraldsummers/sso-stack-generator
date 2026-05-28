package org.webservices.progression

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

class ProgressionCli(
    private val config: ProgressionConfig,
    private val registry: Registry,
    private val store: StateStore
) {
    private val engine = ProgressionEngine(registry, store)
    private val scanners = StackScanners(config, store)

    fun run(rawArgs: Array<String>): Int {
        val jsonOutput = rawArgs.contains("--json")
        val args = rawArgs.filterNot { it == "--json" }
        if (args.isEmpty() || args.first() in setOf("-h", "--help", "help")) {
            printUsage()
            return 0
        }
        return try {
            when (args[0]) {
                "scan" -> output(jsonOutput, "ok", "scan complete", progressionJson.encodeToJsonElement(scanners.scan()))
                "progress" -> progress(args.drop(1), jsonOutput)
                "dashboards" -> dashboards(args.drop(1), jsonOutput)
                "services" -> services(args.drop(1), jsonOutput)
                "routes" -> routes(args.drop(1), jsonOutput)
                "access" -> access(args.drop(1), jsonOutput)
                "verify" -> verify(args.drop(1), jsonOutput)
                "persistence" -> persistence(args.drop(1), jsonOutput)
                "restore" -> restore(args.drop(1), jsonOutput)
                "evidence" -> evidence(args.drop(1), jsonOutput)
                "logs" -> logs(args.drop(1), jsonOutput)
                "explain" -> explain(args.drop(1), jsonOutput)
                "completion" -> completion(args.drop(1))
                else -> {
                    System.err.println("unknown stackctl command: ${args[0]}")
                    printUsage()
                    2
                }
            }
        } catch (e: IllegalArgumentException) {
            if (jsonOutput) {
                println(progressionJson.encodeToString(CliMessage("error", e.message ?: "invalid command")))
            } else {
                System.err.println(e.message ?: "invalid command")
            }
            2
        }
    }

    private fun progress(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        val view = engine.view()
        return when (args.firstOrNull()) {
            null -> output(jsonOutput, "ok", progressSummary(view), progressionJson.encodeToJsonElement(view))
            "next" -> output(
                jsonOutput,
                "ok",
                view.primaryNextTask?.let { "${it.id}: ${it.title}" } ?: "all current tasks complete",
                progressionJson.encodeToJsonElement(view.primaryNextTask)
            )
            "show" -> {
                val id = args.getOrNull(1) ?: throw IllegalArgumentException("stackctl progress show requires a task id")
                val task = engine.task(id) ?: throw IllegalArgumentException("unknown task: $id")
                output(jsonOutput, "ok", taskSummary(task), progressionJson.encodeToJsonElement(task))
            }
            else -> throw IllegalArgumentException("unknown progress command: ${args.first()}")
        }
    }

    private fun dashboards(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        val view = engine.view()
        return when (args.firstOrNull()) {
            "list", null -> output(jsonOutput, "ok", view.dashboards.joinToString("\n") { "${it.id}\t${it.title}" }, progressionJson.encodeToJsonElement(view.dashboards))
            "show" -> {
                val id = args.getOrNull(1) ?: throw IllegalArgumentException("stackctl dashboards show requires a dashboard id")
                val dashboard = engine.dashboard(id) ?: throw IllegalArgumentException("unknown dashboard: $id")
                output(jsonOutput, "ok", "${dashboard.title} (${dashboard.density})", progressionJson.encodeToJsonElement(dashboard))
            }
            else -> throw IllegalArgumentException("unknown dashboards command: ${args.first()}")
        }
    }

    private fun services(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        require(args.firstOrNull() == "show") { "usage: stackctl services show <service>" }
        val service = args.getOrNull(1) ?: throw IllegalArgumentException("stackctl services show requires a service id")
        require(service == "bookstack") { "only bookstack is implemented in the MVP service slice" }
        val view = engine.bookstackService()
        return output(jsonOutput, "ok", serviceSummary(view), progressionJson.encodeToJsonElement(view))
    }

    private fun routes(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        require(args.firstOrNull() in setOf("list", "audit", null)) { "usage: stackctl routes list" }
        val routes = store.readActual().facts["routes"] ?: JsonPrimitive("[]")
        return output(jsonOutput, "ok", "Routes are classified from Caddy config. Use --json for machine output.", routes)
    }

    private fun access(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        require(args.firstOrNull() == "audit") { "usage: stackctl access audit" }
        val claims = store.readActual().claims + store.readVerified().claims
        val message = buildString {
            appendLine("BookStack route defined: ${claims["actual.route.bookstack.exists"] == true}")
            appendLine("BookStack Keycloak client defined: ${claims["actual.keycloak.client.bookstack.defined"] == true}")
            appendLine("BookStack anonymous denied: ${claims["verified.access.bookstack.anonymous_denied"] == true}")
        }.trimEnd()
        return output(jsonOutput, "ok", message, progressionJson.encodeToJsonElement(claims))
    }

    private fun verify(args: List<String>, jsonOutput: Boolean): Int {
        require(args.firstOrNull() == "access.bookstack") { "usage: stackctl verify access.bookstack" }
        val record = scanners.verifyBookStackAccess()
        val ok = record.claims.values.all { it }
        output(jsonOutput, if (ok) "ok" else "incomplete", record.summary, progressionJson.encodeToJsonElement(record))
        return if (ok) 0 else 1
    }

    private fun persistence(args: List<String>, jsonOutput: Boolean): Int {
        scanners.scan()
        require(args.size >= 2 && args[0] == "show" && args[1] == "bookstack") { "usage: stackctl persistence show bookstack" }
        val claims = store.readActual().claims
        val data = mapOf(
            "volumeMapped" to (claims["actual.persistence.bookstack.volume_mapped"] == true),
            "databaseMapped" to (claims["actual.persistence.bookstack.database_mapped"] == true),
            "volume" to true,
            "database" to true
        )
        val message = "BookStack state: volume=bookstack_data database=mariadb/bookstack"
        return output(jsonOutput, "ok", message, progressionJson.encodeToJsonElement(data))
    }

    private fun restore(args: List<String>, jsonOutput: Boolean): Int {
        require(args.size >= 3 && args[0] == "drill" && args[1] == "bookstack" && args[2] == "--temporary") {
            "usage: stackctl restore drill bookstack --temporary"
        }
        val record = scanners.recordBookStackRestoreDrill()
        output(jsonOutput, "incomplete", record.summary, progressionJson.encodeToJsonElement(record))
        return 1
    }

    private fun evidence(args: List<String>, jsonOutput: Boolean): Int {
        require(args.firstOrNull() == "show") { "usage: stackctl evidence show <task-or-evidence-id>" }
        val id = args.getOrNull(1) ?: throw IllegalArgumentException("stackctl evidence show requires an id")
        val record = store.readEvidence(id) ?: throw IllegalArgumentException("no evidence found for $id")
        return output(jsonOutput, "ok", evidenceSummary(record), progressionJson.encodeToJsonElement(record))
    }

    private fun logs(args: List<String>, jsonOutput: Boolean): Int {
        val service = args.firstOrNull() ?: throw IllegalArgumentException("usage: stackctl logs <service>")
        require(service == "bookstack") { "only bookstack logs are implemented in the MVP service slice" }
        val journalctl = findCommand("journalctl")
        val output = if (journalctl == null) {
            "journalctl is unavailable on this host"
        } else {
            runCatching {
                val process = ProcessBuilder(journalctl, "--user", "-u", "webservices-bookstack.service", "--no-pager", "-n", "80")
                    .redirectErrorStream(true)
                    .start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                redactSecrets(text).ifBlank { "no recent BookStack logs returned" }
            }.getOrElse { "failed to read BookStack logs: ${it.message}" }
        }
        return output(jsonOutput, "ok", output, JsonPrimitive(output))
    }

    private fun explain(args: List<String>, jsonOutput: Boolean): Int {
        val concept = args.firstOrNull() ?: throw IllegalArgumentException("usage: stackctl explain <concept>")
        val text = when (concept) {
            "caddy" -> "Caddy is the edge. It owns public routes, TLS, and protected reverse proxy behavior."
            "keycloak" -> "Keycloak is the identity source for users, groups, OIDC clients, and RBAC."
            "bookstack" -> "BookStack is the first owned service because it spans route, login, database, uploads, docs, and restore."
            else -> "No explanation registered for $concept"
        }
        return output(jsonOutput, "ok", text, JsonPrimitive(text))
    }

    private fun completion(args: List<String>): Int {
        require(args.firstOrNull() == "zsh") { "usage: stackctl completion zsh" }
        println(zshCompletion(registry))
        return 0
    }

    private fun output(jsonOutput: Boolean, status: String, message: String, data: kotlinx.serialization.json.JsonElement?): Int {
        if (jsonOutput) {
            println(progressionJson.encodeToString(CliMessage(status, message, data)))
        } else {
            println(message)
        }
        return if (status == "error") 1 else 0
    }

    private fun printUsage() {
        println(
            """
            Usage:
              stackctl progress [next|show <task>] [--json]
              stackctl dashboards list|show <dashboard> [--json]
              stackctl services show bookstack [--json]
              stackctl routes list [--json]
              stackctl access audit [--json]
              stackctl verify access.bookstack [--json]
              stackctl persistence show bookstack [--json]
              stackctl restore drill bookstack --temporary [--json]
              stackctl evidence show <id> [--json]
              stackctl logs bookstack [--json]
              stackctl explain <concept> [--json]
              stackctl completion zsh
            """.trimIndent()
        )
    }
}

private fun progressSummary(view: ProgressionView): String = buildString {
    appendLine("Progression state")
    appendLine("Rewards unlocked: ${view.rewardsUnlocked.size}")
    appendLine("Primary next: ${view.primaryNextTask?.let { "${it.id} - ${it.title}" } ?: "none"}")
    appendLine()
    for (task in view.tasks) {
        appendLine("${task.status.padEnd(10)} ${task.id} - ${task.title}")
    }
}.trimEnd()

private fun taskSummary(task: TaskView): String = buildString {
    appendLine("${task.id}: ${task.title}")
    appendLine("Status: ${task.status}")
    appendLine("Now: ${task.surfaces.now}")
    appendLine("Why: ${task.surfaces.why}")
    if (task.missingEvidence.isNotEmpty()) {
        appendLine("Missing evidence:")
        task.missingEvidence.forEach { appendLine("  $it") }
    }
    if (task.commands.guided.isNotEmpty()) {
        appendLine("Commands:")
        task.commands.guided.forEach { appendLine("  $it") }
    }
}.trimEnd()

private fun serviceSummary(service: ServiceView): String = buildString {
    appendLine("Service: ${service.id}")
    appendLine("Defined: ${service.defined}")
    appendLine("Route exists: ${service.routeExists}")
    appendLine("State mapped: ${service.stateMapped}")
    appendLine("Access verified: ${service.accessVerified}")
    appendLine("Restore proven: ${service.restoreProven}")
}.trimEnd()

private fun evidenceSummary(record: EvidenceRecord): String = buildString {
    appendLine("${record.id}: ${record.summary}")
    record.claims.forEach { (claim, value) -> appendLine("${if (value) "pass" else "missing"} $claim") }
}.trimEnd()

fun redactSecrets(text: String): String =
    text.replace(Regex("""(?i)(password|secret|token|key)=([^ \n\t]+)"""), "$1=<redacted>")
        .replace(Regex("""(?i)(authorization:\s*)([^ \n\t]+)"""), "$1<redacted>")

fun zshCompletion(registry: Registry): String {
    val tasks = registry.tasks.joinToString(" ") { it.id }
    val dashboards = registry.dashboards.joinToString(" ") { it.id }
    return """
        #compdef stackctl
        _stackctl() {
          local -a commands services tasks dashboards
          commands=(progress dashboards services routes access verify persistence restore evidence logs explain completion)
          services=(bookstack)
          tasks=($tasks)
          dashboards=($dashboards)
          _arguments \
            '1:command:->command' \
            '2:argument:->argument' \
            '*::rest:->rest'
          case ${'$'}state in
            command) _describe 'command' commands ;;
            argument)
              case ${'$'}words[2] in
                services|persistence|logs) _describe 'service' services ;;
                progress|evidence) _describe 'task' tasks ;;
                dashboards) _describe 'dashboard' dashboards ;;
                verify) _values 'claim' access.bookstack restore.bookstack ;;
                completion) _values 'shell' zsh ;;
              esac
              ;;
          esac
        }
        _stackctl "$@"
    """.trimIndent()
}
