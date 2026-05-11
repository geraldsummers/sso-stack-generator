package org.webservices.chatgptconnector

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json as clientJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createDirectories

fun main() {
    val config = loadConfig()
    config.dataDir.createDirectories()
    config.databasePath.parent?.createDirectories()
    val store = ConnectorStore(config.databasePath)
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { clientJson(Json { ignoreUnknownKeys = true }) }
        defaultRequest { accept(ContentType.Application.Json) }
    }
    val auth = Authenticator(config, httpClient)
    val keycloakAdmin: KeycloakAdmin = KeycloakAdminClient(config, httpClient)

    val server = embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        configureServer(config, store, auth, keycloakAdmin, httpClient)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        store.close()
        httpClient.close()
        server.stop(1000, 5000)
    })

    server.start(wait = true)
}

fun Application.configureServer(
    config: ConnectorConfig,
    store: ConnectorStore,
    auth: Authenticator,
    keycloakAdmin: KeycloakAdmin,
    httpClient: HttpClient
) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }

    val mcpFacade = McpFacade(store, config, httpClient)

    routing {
        get("/") {
            val html = this::class.java.classLoader.getResource("static/index.html")?.readText() ?: "chatgpt-connector"
            call.respondText(html, ContentType.Text.Html)
        }
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        route("/api") {
            get("/me") {
                val principal = requirePrincipal(auth, call) ?: return@get
                call.respond(MeResponse(principal.username, principal.email, principal.groups))
            }
            get("/agent-accounts") {
                val principal = requirePrincipal(auth, call) ?: return@get
                val accounts = store.listAccounts(principal.username).map { it to store.listTokensForAccount(it.id) }
                call.respond(accounts.map { buildJsonObject {
                    put("account", Json.encodeToJsonElement(AgentAccountDto.serializer(), it.first))
                    put("tokens", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AgentTokenDto.serializer()), it.second))
                } })
            }
            post("/agent-accounts") {
                val principal = requirePrincipal(auth, call) ?: return@post
                val request = call.receive<CreateAccountRequest>()
                val account = store.createAccount(principal.username, request.displayName.trim(), request.scopes)
                try {
                    val (kcId, kcUsername) = keycloakAdmin.createAgentUser(principal.username, account.id, request.scopes)
                    store.setKeycloakUser(account.id, kcId, kcUsername)
                    store.appendAudit(principal.username, account.id, null, "account.created", "agent account created")
                    call.respond(HttpStatusCode.Created, store.getAccount(account.id)!!)
                } catch (e: Exception) {
                    store.closeAccount(account.id, principal.username)
                    call.respond(HttpStatusCode.BadGateway, ApiError("failed to create Keycloak agent user"))
                }
            }
            post("/agent-accounts/{id}/close") {
                val principal = requirePrincipal(auth, call) ?: return@post
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing account id"))
                val account = requireAccountOwnership(store, principal, id, call) ?: return@post
                account.keycloakUserId?.let { keycloakAdmin.disableUser(it) }
                call.respond(store.closeAccount(id, principal.username))
            }
            post("/agent-accounts/{id}/tokens") {
                val principal = requirePrincipal(auth, call) ?: return@post
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing account id"))
                requireAccountOwnership(store, principal, id, call) ?: return@post
                val request = call.receive<MintTokenRequest>()
                call.respond(HttpStatusCode.Created, store.mintToken(id, request.scopes, request.ttlSeconds, principal.username))
            }
            post("/tokens/{id}/revoke") {
                val principal = requirePrincipal(auth, call) ?: return@post
                val tokenId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("missing token id"))
                val existingToken = store.tokenById(tokenId) ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("token not found"))
                val account = store.getAccount(existingToken.accountId)
                if (account == null || account.ownerUsername != principal.username) {
                    return@post call.respond(HttpStatusCode.Forbidden, ApiError("token not owned by caller"))
                }
                val token = store.revokeToken(tokenId, principal.username) ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("token not found"))
                call.respond(token)
            }
            get("/tokens/{id}") {
                val principal = requirePrincipal(auth, call) ?: return@get
                val tokenId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("missing token id"))
                val token = store.tokenById(tokenId) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("token not found"))
                val account = store.getAccount(token.accountId)
                if (account == null || account.ownerUsername != principal.username) {
                    return@get call.respond(HttpStatusCode.Forbidden, ApiError("token not owned by caller"))
                }
                call.respond(token)
            }
            get("/audit-events") {
                val principal = requirePrincipal(auth, call) ?: return@get
                call.respond(store.listAudit(principal.username, limit = 100))
            }
        }

        post("/mcp") {
            val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
            if (bearer.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, McpResponse(error = McpError(-32001, "missing bearer token")))
                return@post
            }
            val token = store.tokenByValue(bearer)
            if (token == null || token.revokedAt != null || token.expiresAt <= java.time.Instant.now().toString()) {
                call.respond(HttpStatusCode.Unauthorized, McpResponse(error = McpError(-32001, "invalid token")))
                return@post
            }
            store.markTokenUsed(token.id)
            val raw = call.receiveText()
            val response = mcpFacade.handle(raw, token)
            call.respond(response)
        }
    }
}

private suspend fun requirePrincipal(authenticator: Authenticator, call: io.ktor.server.application.ApplicationCall): PrincipalIdentity? {
    val principal = authenticator.authenticate(call)
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
        return null
    }
    return principal
}

private suspend fun requireAccountOwnership(
    store: ConnectorStore,
    principal: PrincipalIdentity,
    accountId: String,
    call: io.ktor.server.application.ApplicationCall
): AgentAccountDto? {
    val account = store.getAccount(accountId)
    if (account == null) {
        call.respond(HttpStatusCode.NotFound, ApiError("account not found"))
        return null
    }
    if (account.ownerUsername != principal.username) {
        call.respond(HttpStatusCode.Forbidden, ApiError("account not owned by caller"))
        return null
    }
    return account
}
