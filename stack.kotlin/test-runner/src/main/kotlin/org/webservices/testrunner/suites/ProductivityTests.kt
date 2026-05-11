package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.productivityTests() = suite("Productivity Tests") {

    suspend fun getBookStackResponse(path: String, attempts: Int = 12, delayMs: Long = 5000): HttpResponse? {
        val suffix = if (path.startsWith("/")) path else "/$path"
        val url = "${env.endpoints.bookstack}$suffix"

        repeat(attempts) { attempt ->
            try {
                return client.getRawResponse(url)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val retryable = msg.contains("Connection refused", ignoreCase = true) ||
                    msg.contains("ConnectException", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)
                if (!retryable || attempt == attempts - 1) {
                    println("      ℹ️  BookStack request failed at $suffix: ${e.message}")
                    return null
                }
                if (attempt == 0) {
                    println("      ℹ️  Waiting for BookStack to become reachable...")
                }
                delay(delayMs)
            }
        }

        return null
    }

    
    test("BookStack web interface loads") {
        val response = getBookStackResponse("/")
            ?: fail("BookStack unavailable after retries while loading web interface")
        requireOkOrRedirectResponse(response, "BookStack web interface")
    }

    test("BookStack API endpoint is accessible") {
        val response = getBookStackResponse("/api/docs")
            ?: fail("BookStack unavailable after retries while checking API docs")
        requireOkOrRedirectResponse(response, "BookStack API docs")
    }

    test("BookStack health check responds") {
        val response = getBookStackResponse("/")
            ?: fail("BookStack unavailable after retries while checking health")
        requireOkOrRedirectResponse(response, "BookStack health page")
    }

    
    test("Forgejo git server web interface is healthy") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/")
        requireOkOrRedirectResponse(response, "Forgejo web interface")
    }

    test("Forgejo web interface loads") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/")
        requireOkOrRedirectResponse(response, "Forgejo web interface")
    }

    test("Forgejo API enforces authentication") {
        val response = client.getRawResponse("${env.endpoints.forgejo}/api/v1/version")
        requireAuthBoundary(response, "Forgejo version API")
    }

    
    test("Planka board server is healthy") {
        val response = client.getRawResponse("${env.endpoints.planka}/")
        response.status shouldBe HttpStatusCode.OK
    }

    test("Planka web app loads") {
        val response = client.getRawResponse("${env.endpoints.planka}/")
        val body = response.bodyAsText()
        body.uppercase() shouldContain "<!DOCTYPE HTML>"
    }

    test("Jupyter notebook image is present for JupyterHub spawns") {
        val imageName = System.getenv("JUPYTER_NOTEBOOK_IMAGE") ?: "platform-jupyter-notebook:5.4.3"
        val result = DockerCli.run("image", "inspect", imageName)
        require(result.exitCode == 0) {
            "Jupyter notebook image '$imageName' not found: ${result.output.trim()}"
        }
        println("      ✓ Jupyter notebook image available: $imageName")
    }
}
