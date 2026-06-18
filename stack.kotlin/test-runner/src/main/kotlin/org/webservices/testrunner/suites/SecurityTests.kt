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

    test("CrowdSec IDS replayed Caddy attack trips detection") {
        val result = crowdsecExec(
            "sh", "-lc",
            """
            set -eu
            log_file="${'$'}(mktemp)"
            dump_dir="${'$'}(mktemp -d)"
            trap 'rm -f "${'$'}log_file"; rm -rf "${'$'}dump_dir"' EXIT

            printf '%s\n' '{"level":"info","ts":1781819574.0889907,"logger":"http.log.access.log0","msg":"handled request","request":{"remote_ip":"198.51.100.250","client_ip":"198.51.100.250","proto":"HTTP/1.1","method":"GET","host":"portal.example.test","uri":"/w00tw00t.at.ISC.SANS.DFind:)"},"status":404}' > "${'$'}log_file"

            crowdsec -dsn "file://${'$'}log_file" -type caddy -no-api -no-capi -dump-data "${'$'}dump_dir" >/tmp/crowdsec-ids-replay.out 2>&1
            grep -F "performed 'ltsich/http-w00tw00t'" /tmp/crowdsec-ids-replay.out
            grep -F 'scenario: ltsich/http-w00tw00t' "${'$'}dump_dir/bucket-dump.yaml"
            grep -F 'source_ip' "${'$'}dump_dir/bucket-dump.yaml"
            grep -F '198.51.100.250' "${'$'}dump_dir/bucket-dump.yaml"
            rm -f /tmp/crowdsec-ids-replay.out
            """.trimIndent()
        )
        require(result.exitCode == 0) {
            "CrowdSec did not trip an IDS scenario from replayed Caddy attack logs: ${result.output}"
        }
        result.output shouldContain "ltsich/http-w00tw00t"
        result.output shouldContain "198.51.100.250"
        println("      ✓ CrowdSec parser and scenario engine tripped on a replayed Caddy attack")
    }
}
