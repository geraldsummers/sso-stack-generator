package org.webservices.testrunner.framework

import io.ktor.client.request.*
import io.ktor.http.HttpHeaders

private fun normalizePath(path: String): String {
    if (path.isBlank()) return "/"
    return if (path.startsWith("/")) path else "/$path"
}

fun stackDomain(): String {
    return System.getenv("DOMAIN")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("DOMAIN environment variable is required for Caddy virtual host tests")
}

fun caddyHost(subdomain: String): String {
    val trimmed = subdomain.trim()
    require(trimmed.isNotEmpty()) { "subdomain must not be blank" }
    return "$trimmed.${stackDomain()}"
}

fun caddyUrl(caddyBaseUrl: String, path: String = "/"): String =
    caddyBaseUrl.trimEnd('/') + normalizePath(path)

fun HttpRequestBuilder.applyCaddyVirtualHost(host: String) {
    if (url.protocol.name.equals("https", ignoreCase = true)) {
        url.host = host
        headers.remove(HttpHeaders.Host)
        headers.remove("X-Forwarded-Host")
        headers.remove("X-Forwarded-Proto")
        return
    }

    // Preserve the caller's configured Caddy endpoint and only override the routed vhost.
    // This avoids silently rewriting http://caddy:80 requests to https://caddy:443.
    header(HttpHeaders.Host, host)
    header("X-Forwarded-Host", host)
    header("X-Forwarded-Proto", "https")
}
