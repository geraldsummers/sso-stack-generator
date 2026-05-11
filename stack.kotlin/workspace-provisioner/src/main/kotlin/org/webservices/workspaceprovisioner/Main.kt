package org.webservices.workspaceprovisioner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json as clientJson
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories

private const val WORKSPACE_HEALTH_HEADER = "X-Workspace-Health"
private const val WORKSPACE_NOTEBOOK_PORT_HEADER = "X-Workspace-Notebook-Port"
private const val WORKSPACE_NOTEBOOK_BASE_PATH_HEADER = "X-Workspace-Notebook-Base-Path"
private const val WORKSPACE_TTYD_PORT_HEADER = "X-Workspace-Ttyd-Port"
private const val WORKSPACE_TTYD_BASE_PATH_HEADER = "X-Workspace-Ttyd-Base-Path"

fun main() {
    val config = loadConfig()
    config.dataDir.createDirectories()
    config.databasePath.parent?.createDirectories()
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            clientJson(Json { ignoreUnknownKeys = true })
        }
        defaultRequest {
            headers.append("Accept", "application/json")
        }
    }
    val store = WorkspaceStore(config.databasePath)
    val sshCa = SmallstepSshCa(config)
    val runtime = DockerWorkspaceRuntime(config, sshCa)
    println("[workspace-provisioner] warming workspace runtime images")
    runtime.ensureWorkspaceImage()
    runtime.ensureNotebookImage()
    println("[workspace-provisioner] workspace runtime images ready")
    val knowledgeGateway = WorkspaceKnowledgeGateway(config.searchServiceBaseUrl, config.searchServiceToken, httpClient)
    val service = WorkspaceProvisionerService(config, store, runtime, sshCa, knowledgeGateway)
    val authenticator = Authenticator(config, httpClient)
    service.start()

    val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(config, service, authenticator)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        service.stop()
        httpClient.close()
        store.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(
    config: WorkspaceProvisionerConfig,
    service: WorkspaceProvisionerService,
    authenticator: Authenticator
) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText()
                ?: "workspace-provisioner"
            call.respondText(html, ContentType.Text.Html)
        }
        get("/theme.css") {
            call.respondText(stackThemeCss(), ContentType.Text.CSS)
        }
        get("/health") {
            call.response.headers.append(WORKSPACE_HEALTH_HEADER, "ok")
            call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
        }
        get("/ready") {
            call.response.headers.append(WORKSPACE_HEALTH_HEADER, "ready")
            call.respond(HttpStatusCode.OK, service.currentStatus())
        }
        route("/api") {
            get("/oidc/discovery") {
                call.respond(service.oidcDiscovery())
            }
            get("/me") {
                val principal = requirePrincipal(authenticator, call) ?: return@get
                call.respond(service.me(principal))
            }
            get("/ssh-keys") {
                val principal = requirePrincipal(authenticator, call) ?: return@get
                call.respond(service.listSshKeys(principal))
            }
            post("/ssh-keys") {
                val principal = requirePrincipal(authenticator, call) ?: return@post
                val request = call.receive<AddSshKeyRequest>()
                call.respond(HttpStatusCode.Created, service.addSshKey(principal, request))
            }
            get("/audit-events") {
                val principal = requirePrincipal(authenticator, call) ?: return@get
                requireAdmin(principal, call) ?: return@get
                call.respond(service.auditEvents())
            }
            route("/workspaces") {
                get {
                    val principal = requirePrincipal(authenticator, call) ?: return@get
                    call.respond(service.listWorkspaces(principal))
                }
                post {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val request = call.receive<CreateWorkspaceRequest>()
                    call.respond(HttpStatusCode.Created, service.createWorkspace(principal, request))
                }
                get("/{id}") {
                    val principal = requirePrincipal(authenticator, call) ?: return@get
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@get
                    }
                    call.respond(service.getWorkspace(principal, id))
                }
                get("/{id}/agent/access") {
                    val principal = requirePrincipal(authenticator, call) ?: return@get
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@get
                    }
                    call.respond(service.agentAccess(principal, id))
                }
                delete("/{id}") {
                    val principal = requirePrincipal(authenticator, call) ?: return@delete
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@delete
                    }
                    call.respond(service.deleteWorkspace(principal, id))
                }
                post("/{id}/start") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    call.respond(service.startWorkspace(principal, id))
                }
                post("/{id}/stop") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    call.respond(service.stopWorkspace(principal, id))
                }
                post("/{id}/notebook/start") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    call.respond(service.startNotebook(principal, id))
                }
                post("/{id}/notebook/stop") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    call.respond(service.stopNotebook(principal, id))
                }
                get("/{id}/notebook/auth") {
                    val principal = requirePrincipal(authenticator, call) ?: return@get
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@get
                    }
                    try {
                        val notebook = service.notebookAccess(principal, id)
                        call.response.headers.append(WORKSPACE_HEALTH_HEADER, "running")
                        call.response.headers.append(WORKSPACE_NOTEBOOK_PORT_HEADER, notebook.port.toString())
                        call.response.headers.append(WORKSPACE_NOTEBOOK_BASE_PATH_HEADER, notebook.basePath)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    } catch (e: IllegalArgumentException) {
                        val status = if ((e.message ?: "").contains("not accessible")) HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                        call.respond(status, mapOf("error" to (e.message ?: "invalid notebook access request")))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "notebook session unavailable")))
                    }
                }
                get("/{id}/shell/auth") {
                    val principal = requirePrincipal(authenticator, call) ?: return@get
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@get
                    }
                    try {
                        val ttyd = service.ttydAccess(principal, id)
                        call.response.headers.append(WORKSPACE_HEALTH_HEADER, "running")
                        call.response.headers.append(WORKSPACE_TTYD_PORT_HEADER, ttyd.port.toString())
                        call.response.headers.append(WORKSPACE_TTYD_BASE_PATH_HEADER, ttyd.basePath)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    } catch (e: IllegalArgumentException) {
                        val status = if ((e.message ?: "").contains("not accessible")) HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                        call.respond(status, mapOf("error" to (e.message ?: "invalid shell access request")))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "shell session unavailable")))
                    }
                }
                post("/{id}/lease/renew") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    val request = call.receive<RenewLeaseRequest>()
                    call.respond(service.renewLease(principal, id, request.days))
                }
                post("/{id}/delegations") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    val request = call.receive<AddDelegationRequest>()
                    call.respond(service.addDelegation(principal, id, request.principalUsername.trim()))
                }
                post("/{id}/codex-token") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    val request = call.receive<SetCodexTokenRequest>()
                    call.respond(service.setCodexToken(principal, id, request.token))
                }
                delete("/{id}/codex-token") {
                    val principal = requirePrincipal(authenticator, call) ?: return@delete
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@delete
                    }
                    call.respond(service.clearCodexToken(principal, id))
                }
                post("/{id}/ssh-cert") {
                    val principal = requirePrincipal(authenticator, call) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    val request = call.receive<IssueCertificateRequest>()
                    call.respond(service.issueSshCertificate(principal, id, request.keyName.trim()))
                }
                post("/{id}/knowledge/search") {
                    val principal = requirePrincipal(authenticator, call, allowWorkspaceTokens = true) ?: return@post
                    val id = call.parameters["id"]
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id"))
                        return@post
                    }
                    val request = call.receive<WorkspaceKnowledgeSearchRequest>()
                    val response = service.knowledgeSearch(principal, id, request)
                    call.respondText(response.body, ContentType.Application.Json, response.status)
                }
                get("/{id}/knowledge/documents/{documentId}") {
                    val principal = requirePrincipal(authenticator, call, allowWorkspaceTokens = true) ?: return@get
                    val id = call.parameters["id"]
                    val documentId = call.parameters["documentId"]
                    if (id == null || documentId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing workspace id or document id"))
                        return@get
                    }
                    val response = service.knowledgeDocument(principal, id, documentId, call.request.queryParameters["collection"])
                    call.respondText(response.body, ContentType.Application.Json, response.status)
                }
            }
        }
    }
}

private suspend fun requirePrincipal(
    authenticator: Authenticator,
    call: io.ktor.server.application.ApplicationCall,
    allowWorkspaceTokens: Boolean = false
): PrincipalIdentity? {
    return try {
        val principal = authenticator.authenticate(call)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            null
        } else if (!allowWorkspaceTokens && principal.subjectKind != "user") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "interactive authentication required"))
            null
        } else {
            principal
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to (e.message ?: "authentication failed")))
        null
    }
}

private suspend fun requireAdmin(principal: PrincipalIdentity, call: io.ktor.server.application.ApplicationCall): PrincipalIdentity? {
    return if (principal.groups.any { it == "admins" || it == "operators" }) {
        principal
    } else {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin role required"))
        null
    }
}
