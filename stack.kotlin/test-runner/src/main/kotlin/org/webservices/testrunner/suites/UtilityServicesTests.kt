package org.webservices.testrunner.suites

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.webservices.testrunner.framework.*


suspend fun TestRunner.utilityServicesTests() = suite("Utility Services Tests") {

    
    
    

    test("Stack Portal dashboard loads") {
        val response = client.getRawResponse("${env.endpoints.portal!!}")
        require(response.status == HttpStatusCode.OK) {
            "Stack Portal not accessible: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("Stack Portal") && body.contains("Role dashboards")) {
            "Stack Portal content not detected"
        }

        println("      ✓ Stack Portal dashboard loads")
    }

    test("Stack Portal serves generated module API") {
        
        val response = client.getRawResponse("${env.endpoints.portal!!}/api/modules") {
            headers {
                append(HttpHeaders.Accept, "application/json")
            }
        }

        require(response.status == HttpStatusCode.OK) {
            "Portal module API not loading: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("\"component\"") || body == "[]") {
            "Portal module API returned unexpected payload: $body"
        }

        println("      ✓ Stack Portal generated module API accessible")
    }

    test("Stack Portal profile API endpoint accessible") {
        
        val response = client.getRawResponse("${env.endpoints.portal!!}/api/profiles")
        
        require(response.status == HttpStatusCode.OK) {
            "Portal profile API should respond, got ${response.status}"
        }

        println("      ✓ Stack Portal profile API endpoint responds")
    }

    
    
    

    test("Ntfy server is accessible") {
        val response = client.getRawResponse("${env.endpoints.ntfy!!}")
        require(response.status == HttpStatusCode.OK) {
            "Ntfy not accessible: ${response.status}"
        }

        val body = response.bodyAsText()
        require(body.contains("ntfy") || body.contains("notification") || body.contains("<html")) {
            "Ntfy web interface not detected"
        }

        println("      ✓ Ntfy notification server accessible")
    }

    test("Ntfy health endpoint responds") {
        val response = client.getRawResponse("${env.endpoints.ntfy!!}/v1/health")
        require(response.status == HttpStatusCode.OK) {
            "Ntfy health check failed: ${response.status}"
        }

        println("      ✓ Ntfy health check passed")
    }

    test("Ntfy can create test topic") {
        val testTopic = "test-topic-${System.currentTimeMillis()}"
        val ntfyUsername = System.getenv("NTFY_USERNAME") ?: "admin"
        val ntfyPassword = System.getenv("NTFY_PASSWORD") ?: ""

        val response = client.postRaw("${env.endpoints.ntfy!!}/$testTopic") {
            basicAuth(ntfyUsername, ntfyPassword)
            headers {
                append(HttpHeaders.ContentType, "text/plain")
            }
            setBody("Integration test message")
        }

        require(response.status == HttpStatusCode.OK) {
            "Failed to publish to ntfy: ${response.status}"
        }

        println("      ✓ Ntfy can publish notifications")
    }

    test("Ntfy JSON API works") {
        val testTopic = "test-json-${System.currentTimeMillis()}"
        val ntfyUsername = System.getenv("NTFY_USERNAME") ?: "admin"
        val ntfyPassword = System.getenv("NTFY_PASSWORD") ?: ""

        val response = client.postRaw("${env.endpoints.ntfy!!}/$testTopic") {
            basicAuth(ntfyUsername, ntfyPassword)
            headers {
                append(HttpHeaders.ContentType, "application/json")
            }
            setBody("""{"message":"Test notification","title":"Integration Test","priority":3}""")
        }

        require(response.status == HttpStatusCode.OK) {
            "Failed to publish JSON to ntfy: ${response.status}"
        }

        println("      ✓ Ntfy JSON API works")
    }

}
