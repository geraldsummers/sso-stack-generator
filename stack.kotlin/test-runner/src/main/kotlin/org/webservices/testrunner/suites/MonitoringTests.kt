package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.monitoringTests() = suite("Monitoring Tests") {
    suspend fun fetchDozzle(path: String): HttpResponse {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return client.getRawResponse("http://dozzle:8080$normalized")
    }

    suspend fun followDozzleRedirects(path: String, maxHops: Int = 3): HttpResponse {
        var current = if (path.startsWith("/")) path else "/$path"
        repeat(maxHops + 1) {
            val response = fetchDozzle(current)
            if (response.status !in setOf(
                    HttpStatusCode.MovedPermanently,
                    HttpStatusCode.Found,
                    HttpStatusCode.TemporaryRedirect,
                    HttpStatusCode.PermanentRedirect
                )
            ) {
                return response
            }
            val location = response.headers[HttpHeaders.Location]?.trim().orEmpty()
            if (location.isBlank()) {
                return response
            }
            current = when {
                location.startsWith("http://dozzle:8080") -> location.removePrefix("http://dozzle:8080")
                location.startsWith("/") -> location
                else -> "/$location"
            }
        }
        return fetchDozzle(current)
    }

    suspend fun dozzleResponse(vararg paths: String): HttpResponse {
        paths.forEach { path ->
            val normalized = if (path.startsWith("/")) path else "/$path"
            val response = followDozzleRedirects(normalized)
            if (response.status != HttpStatusCode.NotFound) {
                return response
            }
        }
        return followDozzleRedirects(paths.first())
    }

    
    test("Prometheus server is healthy") {
        val response = client.getRawResponse("${env.endpoints.prometheus}/-/healthy")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Prometheus can execute PromQL query") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "success"
    }

    test("Prometheus targets endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.prometheus}/api/v1/targets")
        response.status shouldBe HttpStatusCode.OK
    }

    
    test("Grafana server is healthy") {
        val response = client.getRawResponse("${env.endpoints.grafana}/api/health")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "ok"
    }

    test("Grafana login page loads") {
        val response = client.getRawResponse("${env.endpoints.grafana}/login")
        response.status shouldBe HttpStatusCode.OK
    }

    
    test("Node Exporter metrics endpoint") {
        val response = client.getRawResponse("http://node-exporter:9100/metrics")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "node_"  
        println("      ✓ Node Exporter providing system metrics")
    }

    test("cAdvisor metrics endpoint") {
        val response = client.getRawResponse("http://cadvisor:8080/metrics")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "container_"  
        println("      ✓ cAdvisor providing container metrics")
    }

    test("Prometheus scraping node-exporter") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up{job=\"node-exporter\"}")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        
        body shouldContain "node-exporter"
        println("      ✓ Prometheus scraping node-exporter")
    }

    test("Prometheus scraping cadvisor") {
        val response = client.postRaw("${env.endpoints.prometheus}/api/v1/query?query=up{job=\"cadvisor\"}")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        
        body shouldContain "cadvisor"
        println("      ✓ Prometheus scraping cadvisor")
    }

    
    test("Dozzle web interface accessible") {
        val response = dozzleResponse("/", "/dozzle")
        require(response.status == HttpStatusCode.OK) { "Dozzle web interface failed: ${response.status}" }
        println("      ✓ Dozzle server responding")
    }

    test("Dozzle healthcheck endpoint") {
        val response = dozzleResponse("/healthcheck", "/dozzle/healthcheck")
        require(response.status == HttpStatusCode.OK) { "Dozzle healthcheck failed: ${response.status}" }
        println("      ✓ Dozzle server responding")
    }

    
    test("AlertManager status endpoint") {
        
        val response = client.getRawResponse("http://alertmanager:9093/-/ready")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ AlertManager ready endpoint accessible")
    }

    test("AlertManager alerts endpoint") {
        
        val response = client.getRawResponse("http://alertmanager:9093/api/v2/alerts")
        response.status shouldBe HttpStatusCode.OK
        println("      ✓ AlertManager alerts API accessible")
    }
}
