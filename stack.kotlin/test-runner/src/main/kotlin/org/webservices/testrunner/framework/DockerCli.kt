package org.webservices.testrunner.framework

data class DockerCommandResult(
    val exitCode: Int,
    val output: String
)

object DockerCli {
    private const val DOCKER_PROXY_HOST = "tcp://docker-socket-controller-proxy:2375"
    private const val ALLOW_LOCAL_MUTATING_FALLBACK_ENV = "TEST_RUNNER_ALLOW_LOCAL_DOCKER_MUTATIONS"

    fun run(vararg args: String): DockerCommandResult {
        val explicitDockerHost = System.getenv("DOCKER_HOST")
        if (!explicitDockerHost.isNullOrBlank()) {
            return runWithDockerHost(args.toList(), explicitDockerHost)
        }

        val commandArgs = args.toList()
        val proxyAttempt = runWithDockerHost(commandArgs, DOCKER_PROXY_HOST)
        if (proxyAttempt.exitCode == 0) return proxyAttempt

        val outputLower = proxyAttempt.output.lowercase()
        val proxyUnavailable = outputLower.contains("no such host") ||
            outputLower.contains("lookup docker-socket-controller-proxy") ||
            outputLower.contains("name or service not known")

        val allowMutatingFallback = allowLocalMutatingFallback()
        val canFallbackToLocalDaemon = proxyUnavailable &&
            (!isMutatingCommand(commandArgs) || allowMutatingFallback)

        return if (canFallbackToLocalDaemon) {
            runWithDockerHost(commandArgs, null)
        } else {
            proxyAttempt
        }
    }

    private fun allowLocalMutatingFallback(): Boolean =
        when (System.getenv(ALLOW_LOCAL_MUTATING_FALLBACK_ENV)?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }

    internal fun isMutatingCommand(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        val top = args.first().lowercase()
        if (top in setOf("build", "run", "start", "stop", "restart", "kill", "rm", "rmi", "pull", "push", "tag")) {
            return true
        }
        if (top == "compose") {
            val composeCommands = args.drop(1)
                .map { it.lowercase() }
                .filterNot { it.startsWith("-") }
            return composeCommands.any {
                it in setOf("up", "down", "start", "stop", "restart", "kill", "rm", "run", "exec", "build", "pull", "push")
            }
        }
        if (top in setOf("container", "image", "network", "volume", "system")) {
            val subCommands = args.drop(1)
                .map { it.lowercase() }
                .filterNot { it.startsWith("-") }
            val subCommand = subCommands.firstOrNull() ?: return false
            return when (top) {
                "container" -> subCommand in setOf("create", "run", "start", "stop", "restart", "kill", "rm", "prune", "exec")
                "image" -> subCommand in setOf("build", "pull", "push", "rm", "prune", "tag", "import", "load")
                "network" -> subCommand in setOf("create", "connect", "disconnect", "rm", "prune")
                "volume" -> subCommand in setOf("create", "rm", "prune")
                "system" -> subCommand == "prune"
                else -> false
            }
        }
        return false
    }

    private fun runWithDockerHost(args: List<String>, dockerHost: String?): DockerCommandResult {
        val command = listOf("docker") + args
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        if (!dockerHost.isNullOrBlank()) {
            processBuilder.environment()["DOCKER_HOST"] = dockerHost
        }
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return DockerCommandResult(exitCode = exitCode, output = output)
    }
}
