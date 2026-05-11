package org.webservices.testrunner.framework

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

class ResettableCookiesStorage : CookiesStorage {
    @Volatile
    private var delegate: CookiesStorage = AcceptAllCookiesStorage()

    fun clear() {
        delegate.close()
        delegate = AcceptAllCookiesStorage()
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = delegate.get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        delegate.addCookie(requestUrl, cookie)
    }

    override fun close() {
        delegate.close()
    }
}
