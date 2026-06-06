package org.webservices.testmanager.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class TestManagerConfigTest {

    @Test
    fun `loadConfig uses defaults when environment is absent or invalid`() {
        val config = runPrinter(
            mapOf(
                "TEST_MANAGER_EVALUATION_INTERVAL_SECONDS" to "NaN",
                "TEST_MANAGER_QUEUE_POLL_INTERVAL_SECONDS" to "NaN",
                "TEST_MANAGER_MAX_LOG_TAIL_CHARS" to "NaN",
                "TEST_MANAGER_API_KEY" to "test-api-key"
            )
        )

        assertEquals("8105", config["port"])
        assertEquals("/workspace", config["workspaceRoot"])
        assertEquals("/data", config["dataRoot"])
        assertEquals("/data/test-manager.db", config["databasePath"])
        assertEquals("/data/runs", config["logRoot"])
        assertEquals("/workspace/runtime/configs/test-manager/suites.yaml", config["suitesPath"])
        assertEquals("/workspace/.build-info", config["releaseInfoPath"])
        assertEquals("/workspace/runtime/configs/caddy/Caddyfile", config["domainConfigPath"])
        assertEquals("http://ingestion-runner:8090/health", config["pipelineReadinessUrl"])
        assertNull(config["pipelineApiKey"])
        assertEquals("test-api-key", config["apiKey"])
        assertEquals("tcp://docker-socket-proxy:2375", config["dockerHost"])
        assertEquals("30", config["evaluationIntervalSeconds"])
        assertEquals("5", config["queuePollIntervalSeconds"])
        assertEquals("12000", config["maxLogTailChars"])
    }

    @Test
    fun `loadConfig honors explicit environment overrides`() {
        val config = runPrinter(
            mapOf(
                "TEST_MANAGER_PORT" to "8123",
                "TEST_MANAGER_WORKSPACE_ROOT" to "/tmp/workspace",
                "TEST_MANAGER_DATA_ROOT" to "/tmp/data",
                "TEST_MANAGER_DB_PATH" to "/tmp/custom.db",
                "TEST_MANAGER_LOG_ROOT" to "/tmp/logs",
                "TEST_MANAGER_SUITES_PATH" to "/tmp/suites.yaml",
                "TEST_MANAGER_RELEASE_INFO_PATH" to "/tmp/build-info",
                "TEST_MANAGER_DOMAIN_CONFIG_PATH" to "/tmp/Caddyfile",
                "TEST_MANAGER_PIPELINE_READINESS_URL" to "http://pipeline/readiness",
                "TEST_MANAGER_PIPELINE_API_KEY" to "secret",
                "TEST_MANAGER_API_KEY" to "custom-api-key",
                "DOCKER_HOST" to "tcp://fallback:2375",
                "TEST_MANAGER_DOCKER_HOST" to "tcp://override:2375",
                "TEST_MANAGER_EVALUATION_INTERVAL_SECONDS" to "45",
                "TEST_MANAGER_QUEUE_POLL_INTERVAL_SECONDS" to "9",
                "TEST_MANAGER_MAX_LOG_TAIL_CHARS" to "4096"
            )
        )

        assertEquals("8123", config["port"])
        assertEquals("/tmp/workspace", config["workspaceRoot"])
        assertEquals("/tmp/data", config["dataRoot"])
        assertEquals("/tmp/custom.db", config["databasePath"])
        assertEquals("/tmp/logs", config["logRoot"])
        assertEquals("/tmp/suites.yaml", config["suitesPath"])
        assertEquals("/tmp/build-info", config["releaseInfoPath"])
        assertEquals("/tmp/Caddyfile", config["domainConfigPath"])
        assertEquals("http://pipeline/readiness", config["pipelineReadinessUrl"])
        assertEquals("secret", config["pipelineApiKey"])
        assertEquals("custom-api-key", config["apiKey"])
        assertEquals("tcp://override:2375", config["dockerHost"])
        assertEquals("45", config["evaluationIntervalSeconds"])
        assertEquals("9", config["queuePollIntervalSeconds"])
        assertEquals("4096", config["maxLogTailChars"])
    }

    private fun runPrinter(extraEnv: Map<String, String>): Map<String, String?> {
        val classpath = System.getProperty("java.class.path")
        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            javaBinary,
            "-cp",
            classpath,
            "org.webservices.testmanager.config.ConfigPrinter"
        ).apply {
            val env = environment()
            listOf(
                "TEST_MANAGER_PORT",
                "TEST_MANAGER_WORKSPACE_ROOT",
                "TEST_MANAGER_DATA_ROOT",
                "TEST_MANAGER_DB_PATH",
                "TEST_MANAGER_LOG_ROOT",
                "TEST_MANAGER_SUITES_PATH",
                "TEST_MANAGER_RELEASE_INFO_PATH",
                "TEST_MANAGER_DOMAIN_CONFIG_PATH",
                "TEST_MANAGER_PIPELINE_READINESS_URL",
                "TEST_MANAGER_PIPELINE_API_KEY",
                "TEST_MANAGER_API_KEY",
                "TEST_MANAGER_DOCKER_HOST",
                "TEST_MANAGER_EVALUATION_INTERVAL_SECONDS",
                "TEST_MANAGER_QUEUE_POLL_INTERVAL_SECONDS",
                "TEST_MANAGER_MAX_LOG_TAIL_CHARS",
                "DOCKER_HOST"
            ).forEach(env::remove)
            env.putAll(extraEnv)
        }.start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "Config printer failed: $stderr" }

        return stdout
            .lineSequence()
            .filter { it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value.takeUnless { it == "<null>" }
            }
    }
}

object ConfigPrinter {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = loadConfig()
        println("port=${config.port}")
        println("workspaceRoot=${config.workspaceRoot}")
        println("dataRoot=${config.dataRoot}")
        println("databasePath=${config.databasePath}")
        println("logRoot=${config.logRoot}")
        println("suitesPath=${config.suitesPath}")
        println("releaseInfoPath=${config.releaseInfoPath}")
        println("domainConfigPath=${config.domainConfigPath}")
        println("pipelineReadinessUrl=${config.pipelineReadinessUrl}")
        println("pipelineApiKey=${config.pipelineApiKey ?: "<null>"}")
        println("apiKey=${config.apiKey}")
        println("dockerHost=${config.dockerHost}")
        println("evaluationIntervalSeconds=${config.evaluationIntervalSeconds}")
        println("queuePollIntervalSeconds=${config.queuePollIntervalSeconds}")
        println("maxLogTailChars=${config.maxLogTailChars}")
    }
}
