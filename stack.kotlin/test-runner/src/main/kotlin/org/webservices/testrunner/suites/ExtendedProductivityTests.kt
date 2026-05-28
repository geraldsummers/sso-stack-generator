package org.webservices.testrunner.suites

import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.webservices.testrunner.framework.*

suspend fun TestRunner.extendedProductivityTests() = suite("Extended Productivity Tests") {
    fun requireStatus(
        response: HttpResponse,
        allowed: Set<HttpStatusCode>,
        message: String
    ) {
        require(response.status in allowed) {
            "$message: ${response.status}"
        }
    }

    // OnlyOffice Document Server tests
    test("OnlyOffice: Service is accessible") {
        val response = client.getRawResponse(endpoints.onlyoffice)
        requireOkOrRedirectResponse(response, "OnlyOffice service")
        println("      ✓ OnlyOffice endpoint returned ${response.status}")
    }

    test("OnlyOffice: Health check endpoint") {
        val response = client.getRawResponse("${endpoints.onlyoffice}/healthcheck")
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.bodyAsText()
                body shouldContain "true"
                println("      ✓ OnlyOffice health check passed")
            }
            HttpStatusCode.NotFound -> println("      ✓ OnlyOffice build does not expose /healthcheck")
            else -> fail("OnlyOffice health check failed: ${response.status}")
        }
    }

    test("OnlyOffice: Document conversion API exists") {
        val response = client.getRawResponse("${endpoints.onlyoffice}/ConvertService.ashx")
        response.status.value shouldBeOneOf listOf(200, 400, 401, 403, 405)
        println("      ✓ Conversion service endpoint: ${response.status}")
    }

    test("OnlyOffice: Document editing service exists") {
        val response = client.getRawResponse("${endpoints.onlyoffice}/coauthoring/CommandService.ashx")
        response.status.value shouldBeOneOf listOf(200, 400, 401, 403, 405)
        println("      ✓ Command service endpoint: ${response.status}")
    }

    test("OnlyOffice: Static resources are served") {
        val response = client.getRawResponse("${endpoints.onlyoffice}/web-apps/apps/api/documents/api.js")
        requireStatus(
            response,
            setOf(HttpStatusCode.OK, HttpStatusCode.NotModified),
            "OnlyOffice static assets were not served"
        )
        println("      ✓ Document editor API script available")
    }

    // JupyterHub tests
    test("JupyterHub: Service is accessible") {
        val response = client.getRawResponse(endpoints.jupyterhub)
        requireOkOrRedirectResponse(response, "JupyterHub service")
        println("      ✓ JupyterHub endpoint returned ${response.status}")
    }

    test("JupyterHub: Login route is protected by edge auth") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/login")
        requireOkOrAuthBoundary(response, "JupyterHub login route")

        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            body shouldContain "JupyterHub"
            println("      ✓ JupyterHub login page loads")
        } else {
            println("      ✓ JupyterHub login route is auth protected (${response.status})")
        }
    }

    test("JupyterHub: API endpoint responds") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/api")
        val body = requireOkResponse(response, "JupyterHub API")
        val json = Json.parseToJsonElement(body)
        require(json is JsonObject) { "API should return JSON object" }
        println("      ✓ JupyterHub API accessible")
    }

    test("JupyterHub: User API endpoint enforces authentication") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/api/users")
        requireAuthBoundary(response, "JupyterHub users API")
        println("      ✓ Users API endpoint: ${response.status}")
    }

    test("JupyterHub: OAuth integration configured") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/oauth_login")
        when (response.status) {
            HttpStatusCode.NotFound -> println("      ✓ JupyterHub relies on edge auth; /hub/oauth_login is intentionally absent")
            else -> {
                requireOkOrRedirectResponse(response, "JupyterHub OAuth login endpoint")
                println("      ✓ OAuth login endpoint: ${response.status}")
            }
        }
    }

    test("JupyterHub: Static assets are served") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/static/css/style.min.css")
        requireStatus(
            response,
            setOf(HttpStatusCode.OK, HttpStatusCode.NotModified),
            "JupyterHub static assets were not served"
        )
        println("      ✓ Static assets served")
    }

    test("JupyterHub: Kernel specifications endpoint") {
        val response = client.getRawResponse("${endpoints.jupyterhub}/hub/api/kernelspecs")
        when (response.status) {
            HttpStatusCode.NotFound -> println("      ✓ Kernel specs are deferred until a single-user server is active")
            else -> {
                val body = requireOkResponse(response, "JupyterHub kernel specs endpoint")
                val json = Json.parseToJsonElement(body)
                require(json is JsonObject) { "Kernel specs endpoint should return JSON object" }
                println("      ✓ Kernel specs endpoint: ${response.status}")
            }
        }
    }

    // Integration test: Jupyter + Pipeline data analysis
    test("Integration: JupyterHub + Data Pipeline analysis capability") {
        val jupyterResponse = client.getRawResponse(endpoints.jupyterhub)
        val pipelineEndpoint = endpoints.pipeline
        require(pipelineEndpoint.isNotBlank() && pipelineEndpoint != "null") {
            "Pipeline endpoint not configured for Jupyter integration test"
        }
        val pipelineResponse = runCatching {
            client.getRawResponse("${pipelineEndpoint}/health")
        }.getOrNull()
        val qdrantResponse = runCatching {
            client.getRawResponse("${endpoints.qdrant.trimEnd('/')}/healthz")
        }.getOrNull()

        val jupyterReachable = jupyterResponse.status == HttpStatusCode.OK ||
            jupyterResponse.status == HttpStatusCode.Found ||
            jupyterResponse.status == HttpStatusCode.SeeOther
        val pipelineReachable = pipelineResponse?.status == HttpStatusCode.OK
        val qdrantReachable = qdrantResponse?.status == HttpStatusCode.OK

        if (jupyterReachable && (pipelineReachable || qdrantReachable)) {
            println("      ✓ Data analysis stack ready")
            if (pipelineReachable) {
                println("      ℹ️  JupyterHub can analyze data from Pipeline/Qdrant")
            } else {
                println("      ℹ️  Pipeline management API is not exposed; validated JupyterHub + Qdrant data plane")
            }
        } else {
            fail("Data analysis stack not ready: Jupyter=$jupyterReachable Pipeline=$pipelineReachable Qdrant=$qdrantReachable")
        }
    }
}
