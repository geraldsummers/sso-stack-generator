package org.webservices.testrunner.suites

import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.webservices.testrunner.framework.TestRunner

suspend fun requireOkResponse(response: HttpResponse, label: String): String {
    require(response.status == HttpStatusCode.OK) {
        "$label must return 200 OK, got ${response.status}"
    }
    return response.bodyAsText()
}

fun requireOkOrRedirectResponse(response: HttpResponse, label: String) {
    require(
        response.status == HttpStatusCode.OK ||
            response.status == HttpStatusCode.MovedPermanently ||
            response.status == HttpStatusCode.Found ||
            response.status == HttpStatusCode.SeeOther ||
            response.status == HttpStatusCode.TemporaryRedirect ||
            response.status == HttpStatusCode.PermanentRedirect
    ) {
        "$label must return content or a service redirect, got ${response.status}"
    }
}

fun requireAuthBoundary(response: HttpResponse, label: String) {
    require(
        response.status == HttpStatusCode.Unauthorized ||
            response.status == HttpStatusCode.Forbidden ||
            response.status == HttpStatusCode.MovedPermanently ||
            response.status == HttpStatusCode.Found ||
            response.status == HttpStatusCode.SeeOther ||
            response.status == HttpStatusCode.TemporaryRedirect ||
            response.status == HttpStatusCode.PermanentRedirect
    ) {
        "$label must enforce authentication, got ${response.status}"
    }
}

fun requireOkOrAuthBoundary(response: HttpResponse, label: String) {
    if (response.status == HttpStatusCode.OK) return
    requireAuthBoundary(response, label)
}

suspend fun TestRunner.getMastodonInternalResponse(path: String): HttpResponse {
    val suffix = if (path.startsWith("/")) path else "/$path"
    val host = System.getenv("MASTODON_HOST_HEADER")
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("DOMAIN")?.takeIf { it.isNotBlank() }?.let { "mastodon.$it" }
        ?: "mastodon.localhost"

    return client.getRawResponse("${endpoints.mastodon.trimEnd('/')}$suffix") {
        header(HttpHeaders.Accept, "text/html,application/json,*/*")
        header(HttpHeaders.Host, host)
        header("X-Forwarded-Proto", "https")
    }
}
