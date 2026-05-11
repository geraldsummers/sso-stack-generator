package org.webservices.inferencegateway

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MainTest {
    private var upstream: MockWebServer? = null
    private val json = Json { ignoreUnknownKeys = true }

    @AfterEach
    fun tearDown() {
        upstream?.shutdown()
        upstream = null
    }

    @Test
    fun `llm responses requests are translated to chat completions upstream with correlation headers`() = testApplication {
        val server = MockWebServer()
        upstream = server
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Request-Id", "upstream-request-123")
                .setBody("""{"id":"chatcmpl_123","object":"chat.completion","model":"webservices-qwen2.5-coder-14b-cpu","choices":[{"index":0,"message":{"role":"assistant","content":"READY"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""")
        )

        application {
            configureServer(service(server.url("/").toString().removeSuffix("/"), llmTarget = "llm-cpu-fallback"))
        }

        val requestBody = """{"model":"webservices-qwen2.5-coder-14b-cpu","input":"Reply with exactly READY","tools":[{"type":"function","name":"exec_command","description":"run shell","parameters":{"type":"object","properties":{}}}]}"""
        val response = client.post("/llm/v1/responses") {
            contentType(ContentType.Application.Json)
            header("X-Client-Request-Id", "client-request-abc")
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("client-request-abc", response.headers["X-Client-Request-Id"])
        assertEquals("upstream-request-123", response.headers["X-Upstream-Request-Id"])
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val responseObject = payload.getValue("response").jsonObject
        assertEquals("response", responseObject.getValue("object").jsonPrimitive.content)
        assertEquals("completed", responseObject.getValue("status").jsonPrimitive.content)
        assertEquals("READY", responseObject.getValue("output").jsonArray[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)

        val upstreamRequest = server.takeRequest()
        assertEquals("POST", upstreamRequest.method)
        assertEquals("/v1/chat/completions", upstreamRequest.path)
        assertEquals(
            """{"model":"webservices-qwen2.5-coder-14b-cpu","stream":false,"messages":[{"role":"user","content":"Reply with exactly READY"}],"tools":[{"type":"function","name":"exec_command","description":"run shell","parameters":{"type":"object","properties":{}}}]}""",
            upstreamRequest.body.readUtf8()
        )
        assertEquals("client-request-abc", upstreamRequest.getHeader("X-Client-Request-Id"))
        assertNotNull(upstreamRequest.getHeader("X-Request-Id"))
    }

    @Test
    fun `llm cpu direct route rewrites shared model alias to cpu local alias`() = testApplication {
        val server = MockWebServer()
        upstream = server
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"chatcmpl_cpu","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"READY"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""")
        )

        application {
            configureServer(
                serviceWithUnavailableController(
                    llmCpuBaseUrl = server.url("/").toString().removeSuffix("/")
                )
            )
        }

        val response = client.post("/llm/cpu/v1/responses") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"webservices-qwen2.5-coder-14b","input":"Reply with exactly READY"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val upstreamRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", upstreamRequest.path)
        assertEquals(
            """{"model":"webservices-qwen2.5-coder-14b-cpu","stream":false,"messages":[{"role":"user","content":"Reply with exactly READY"}]}""",
            upstreamRequest.body.readUtf8()
        )
    }

    @Test
    fun `llm gpu direct route proxies models without controller`() = testApplication {
        val gpuServer = MockWebServer()
        upstream = gpuServer
        gpuServer.start()
        gpuServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"webservices-qwen2.5-coder-14b-gpu","object":"model"}]}""")
        )

        application {
            configureServer(
                serviceWithUnavailableController(
                    llmGpuBaseUrl = gpuServer.url("/").toString().removeSuffix("/")
                )
            )
        }

        val response = client.get("/llm/gpu/v1/models")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"object":"list","data":[{"id":"webservices-qwen2.5-coder-14b-gpu","object":"model"}]}""", response.bodyAsText())
        assertEquals("/v1/models", gpuServer.takeRequest().path)
        gpuServer.shutdown()
    }

    @Test
    fun `proxy routes fail closed when internal token is not configured outside explicit dev or test mode`() = testApplication {
        val gpuServer = MockWebServer()
        upstream = gpuServer
        gpuServer.start()

        application {
            configureServer(
                serviceWithUnavailableController(
                    llmGpuBaseUrl = gpuServer.url("/").toString().removeSuffix("/"),
                    internalApiToken = null,
                    allowUnauthenticatedInternalApi = false
                )
            )
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/llm/gpu/v1/models").status)
        gpuServer.shutdown()
    }

    @Test
    fun `proxy routes require configured internal token`() = testApplication {
        val gpuServer = MockWebServer()
        upstream = gpuServer
        gpuServer.start()
        gpuServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[]}""")
        )

        application {
            configureServer(
                serviceWithUnavailableController(
                    llmGpuBaseUrl = gpuServer.url("/").toString().removeSuffix("/"),
                    internalApiToken = "shared-secret"
                )
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/llm/gpu/v1/models").status)
        val authorized = client.get("/llm/gpu/v1/models") {
            header("X-Internal-Api-Token", "shared-secret")
        }

        assertEquals(HttpStatusCode.OK, authorized.status)
        assertEquals("/v1/models", gpuServer.takeRequest().path)
        gpuServer.shutdown()
    }

    @Test
    fun `llm responses route gpu model requests to gpu backend even when controller defaults to cpu`() = testApplication {
        val cpuServer = MockWebServer()
        val gpuServer = MockWebServer()
        upstream = cpuServer
        cpuServer.start()
        gpuServer.start()
        gpuServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"chatcmpl_gpu","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"GPU"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""")
        )

        application {
            configureServer(
                service(
                    llmCpuBaseUrl = cpuServer.url("/").toString().removeSuffix("/"),
                    llmGpuBaseUrl = gpuServer.url("/").toString().removeSuffix("/"),
                    llmTarget = "llm-cpu-fallback"
                )
            )
        }

        val requestBody = """{"model":"webservices-qwen2.5-coder-14b-gpu","input":"Reply with exactly GPU"}"""
        val response = client.post("/llm/v1/responses") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responsePayload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("GPU", responsePayload.getValue("response").jsonObject.getValue("output").jsonArray[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(0, cpuServer.requestCount)
        val gpuRequest = gpuServer.takeRequest()
        assertEquals("/v1/chat/completions", gpuRequest.path)
        assertEquals("""{"model":"webservices-qwen2.5-coder-14b-gpu","stream":false,"messages":[{"role":"user","content":"Reply with exactly GPU"}]}""", gpuRequest.body.readUtf8())
        gpuServer.shutdown()
    }

    @Test
    fun `llm responses rewrite stale model ids to the selected backend default model`() = testApplication {
        val server = MockWebServer()
        upstream = server
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"chatcmpl_rewritten","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"READY"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""")
        )

        application {
            configureServer(service(server.url("/").toString().removeSuffix("/"), llmTarget = "llm-cpu-fallback"))
        }

        val response = client.post("/llm/v1/responses") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"qwen2.5-7b-instruct","input":"Reply with exactly READY"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val upstreamRequest = server.takeRequest()
        assertEquals(
            """{"model":"webservices-qwen2.5-coder-14b-cpu","stream":false,"messages":[{"role":"user","content":"Reply with exactly READY"}]}""",
            upstreamRequest.body.readUtf8()
        )
    }

    @Test
    fun `llm streaming responses are synthesized from upstream chat completion usage`() = testApplication {
        val server = MockWebServer()
        upstream = server
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"chatcmpl_stream","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"READY"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}""")
        )

        application {
            configureServer(service(server.url("/").toString().removeSuffix("/"), llmTarget = "llm-cpu-fallback"))
        }

        val response = client.post("/llm/v1/responses") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model":"webservices-qwen2.5-coder-14b-cpu",
                  "stream":true,
                  "input":[
                    {
                      "role":"user",
                      "content":[
                        {"type":"input_text","text":"Reply with READY only."}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        kotlin.test.assertTrue(body.contains("event: response.created"))
        kotlin.test.assertTrue(body.contains("event: response.output_item.done"))
        kotlin.test.assertTrue(body.contains("event: response.completed"))
        kotlin.test.assertTrue(body.contains("\"input_tokens\":8"))
        kotlin.test.assertTrue(body.contains("\"output_tokens\":2"))

        val upstreamRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", upstreamRequest.path)
        assertEquals(
            """{"model":"webservices-qwen2.5-coder-14b-cpu","stream":false,"messages":[{"role":"user","content":"Reply with READY only."}]}""",
            upstreamRequest.body.readUtf8()
        )
    }

    @Test
    fun `llm models endpoint aggregates healthy cpu and gpu catalogs`() = testApplication {
        val cpuServer = MockWebServer()
        val gpuServer = MockWebServer()
        upstream = cpuServer
        cpuServer.start()
        gpuServer.start()
        cpuServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"webservices-qwen2.5-coder-14b-cpu","object":"model"}]}""")
        )
        gpuServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"webservices-qwen2.5-coder-14b-gpu","object":"model","owned_by":"vllm-gpu"}]}""")
        )

        application {
            configureServer(
                service(
                    llmCpuBaseUrl = cpuServer.url("/").toString().removeSuffix("/"),
                    llmGpuBaseUrl = gpuServer.url("/").toString().removeSuffix("/"),
                    llmTarget = "llm-cpu-fallback"
                )
            )
        }

        val response = client.get("/llm/v1/models")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val ids = payload.getValue("data").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("webservices-qwen2.5-coder-14b-cpu", "webservices-qwen2.5-coder-14b-gpu"), ids)
        val gpuModel = payload.getValue("data").jsonArray[1].jsonObject
        assertEquals("vllm-gpu", gpuModel.getValue("owned_by").jsonPrimitive.content)
        assertEquals("/v1/models", cpuServer.takeRequest().path)
        assertEquals("/v1/models", gpuServer.takeRequest().path)
        gpuServer.shutdown()
    }

    private fun service(
        llmCpuBaseUrl: String,
        llmGpuBaseUrl: String = llmCpuBaseUrl,
        llmTarget: String = "llm-cpu-fallback"
    ): InferenceGatewayService {
        return InferenceGatewayService(
            config = config(llmCpuBaseUrl = llmCpuBaseUrl, llmGpuBaseUrl = llmGpuBaseUrl),
            controllerStatusClient = object : InferenceControllerStatusClient {
                override suspend fun fetch(): InferenceControllerStatusPayload = controllerStatus(llmTarget = llmTarget)
            }
        )
    }

    private fun serviceWithUnavailableController(
        llmCpuBaseUrl: String = "http://llm-cpu-fallback:11434",
        llmGpuBaseUrl: String = "http://vllm-gpu:11434",
        internalApiToken: String? = null,
        allowUnauthenticatedInternalApi: Boolean = true
    ): InferenceGatewayService {
        return InferenceGatewayService(
            config = config(
                llmCpuBaseUrl = llmCpuBaseUrl,
                llmGpuBaseUrl = llmGpuBaseUrl,
                internalApiToken = internalApiToken,
                allowUnauthenticatedInternalApi = allowUnauthenticatedInternalApi
            ),
            controllerStatusClient = object : InferenceControllerStatusClient {
                override suspend fun fetch(): InferenceControllerStatusPayload {
                    error("controller unavailable")
                }
            }
        )
    }

    private fun config(
        llmCpuBaseUrl: String = "http://llm-cpu-fallback:11434",
        llmGpuBaseUrl: String = "http://vllm-gpu:11434",
        internalApiToken: String? = null,
        allowUnauthenticatedInternalApi: Boolean = true
    ) = InferenceGatewayConfig(
        port = 8111,
        controllerStatusUrl = "http://inference-controller:8110/api/status",
        controllerApiToken = null,
        internalApiToken = internalApiToken,
        allowUnauthenticatedInternalApi = allowUnauthenticatedInternalApi,
        controllerTimeoutMs = 10000,
        llmCpuBaseUrl = llmCpuBaseUrl,
        llmGpuBaseUrl = llmGpuBaseUrl,
        llmCpuDefaultModel = "webservices-qwen2.5-coder-14b-cpu",
        llmGpuDefaultModel = "webservices-qwen2.5-coder-14b-gpu",
        llmCpuModels = setOf("webservices-qwen2.5-coder-14b-cpu", "webservices-qwen2.5-coder-14b", "qwen2.5-coder:14b"),
        llmGpuModels = setOf("webservices-qwen2.5-coder-14b-gpu", "webservices-qwen2.5-coder-14b", "qwen2.5-coder:14b"),
        embeddingCpuBaseUrl = "http://embedding-cpu:8080",
        embeddingGpuBaseUrl = "http://embedding-gpu:8080"
    )

    private fun controllerStatus(
        llmTarget: String = "llm-cpu-fallback",
        embeddingTarget: String = "embedding-cpu",
        mode: String = "cpu_default"
    ) = InferenceControllerStatusPayload(
        mode = mode,
        llmTarget = llmTarget,
        embeddingTarget = embeddingTarget,
        targetsReady = true,
        ready = true,
        transitioning = false,
        backends = listOf(
            backend("llm-cpu-fallback"),
            backend("vllm-gpu"),
            backend("embedding-cpu")
        )
    )

    private fun backend(serviceName: String) = InferenceControllerBackendStatusPayload(
        serviceName = serviceName,
        running = true,
        healthy = true,
        activeState = "active",
        subState = "running"
    )
}
