package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.securityTests() = suite("Security Tests") {
    fun crowdsecContainer(): String =
        System.getenv("CROWDSEC_CONTAINER") ?: composeServiceContainerName("crowdsec")

    fun crowdsecExec(vararg command: String) =
        DockerCli.run("exec", crowdsecContainer(), *command)

    test("Vaultwarden server is healthy") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/alive")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Vaultwarden web vault loads") {
        val response = client.getRawResponse("${env.endpoints.vaultwarden}/")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("CrowdSec IDS service is healthy") {
        val result = crowdsecExec("cscli", "lapi", "status")
        require(result.exitCode == 0) {
            "CrowdSec local API is not healthy: ${result.output}"
        }
        result.output shouldContain "Loaded credentials"
        println("      ✓ CrowdSec local API is healthy")
    }

    test("CrowdSec IDS acquires Caddy and identity logs") {
        val result = crowdsecExec(
            "sh", "-lc",
            """
            test -r /etc/crowdsec/acquis.yaml &&
            grep -F 'container_name:' /etc/crowdsec/acquis.yaml &&
            grep -F -- '- caddy' /etc/crowdsec/acquis.yaml &&
            grep -F -- '- keycloak' /etc/crowdsec/acquis.yaml &&
            grep -F -- '- keycloak-auth-gateway' /etc/crowdsec/acquis.yaml &&
            grep -F 'type: caddy' /etc/crowdsec/acquis.yaml &&
            grep -F 'type: syslog' /etc/crowdsec/acquis.yaml
            """.trimIndent()
        )
        require(result.exitCode == 0) {
            "CrowdSec acquisition config is missing Caddy or identity sources: ${result.output}"
        }
        println("      ✓ CrowdSec acquisition watches Caddy and identity containers")
    }

    test("CrowdSec IDS metrics are available") {
        val result = crowdsecExec("cscli", "metrics")
        require(result.exitCode == 0) {
            "CrowdSec metrics command failed: ${result.output}"
        }
        result.output shouldContain "Local API Metrics"
        println("      ✓ CrowdSec metrics are available")
    }

    test("CrowdSec IDS simulated alert creates a local decision") {
        val simulatedIp = "203.0.113.250"
        cleanup {
            crowdsecExec("sh", "-lc", "cscli decisions delete --ip '$simulatedIp' >/dev/null 2>&1 || true")
        }

        crowdsecExec("sh", "-lc", "cscli decisions delete --ip '$simulatedIp' >/dev/null 2>&1 || true")
        val result = crowdsecExec("webservices-crowdsec-simulate-alert", simulatedIp, "5m")
        require(result.exitCode == 0) {
            "CrowdSec simulated alert did not create a decision: ${result.output}"
        }
        result.output shouldContain simulatedIp
        result.output shouldContain "webservices-simulated-alert"
        println("      ✓ CrowdSec simulated alert produced a local decision for $simulatedIp")
    }
}
