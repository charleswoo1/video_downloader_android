package com.charleswoo1.videodownloader.data.download.http

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, in-memory app-private CookieJar for anonymous/guest sessions.
 * Never logs cookie values or serializes sensitive credentials to disk.
 */
class PlatformCookieJar : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            val domainKey = cookie.domain.removePrefix(".").ifBlank { url.host }
            val hostStore = cookieStore.computeIfAbsent(domainKey) { ConcurrentHashMap() }
            hostStore[cookie.name] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        val host = url.host

        // Match exact host and parent domain cookies
        for ((domain, store) in cookieStore) {
            if (host == domain || host.endsWith(".$domain")) {
                val expiredNames = mutableListOf<String>()
                for ((name, cookie) in store) {
                    if (cookie.expiresAt < now) {
                        expiredNames.add(name)
                    } else if (cookie.matches(url)) {
                        result.add(cookie)
                    }
                }
                expiredNames.forEach { store.remove(it) }
            }
        }

        return result
    }

    fun getCookieValue(domain: String, name: String): String? {
        val cleanTarget = domain.removePrefix(".")
        val now = System.currentTimeMillis()

        // 1. Direct host/domain match
        val directStore = cookieStore[cleanTarget]
        val directCookie = directStore?.get(name)
        if (directCookie != null) {
            if (directCookie.expiresAt < now) {
                directStore.remove(name)
            } else {
                return directCookie.value
            }
        }

        // 2. Parent domain match (only domain cookies, never host-only cookies)
        for ((storeDomain, store) in cookieStore) {
            if (cleanTarget.endsWith(".$storeDomain", ignoreCase = true)) {
                val cookie = store[name] ?: continue
                if (cookie.expiresAt < now) {
                    store.remove(name)
                } else if (!cookie.hostOnly) {
                    return cookie.value
                }
            }
        }
        return null
    }

    fun putCookie(url: HttpUrl, cookie: Cookie) {
        val domainKey = cookie.domain.removePrefix(".").ifBlank { url.host }
        val hostStore = cookieStore.computeIfAbsent(domainKey) { ConcurrentHashMap() }
        hostStore[cookie.name] = cookie
    }

    fun clear() {
        cookieStore.clear()
    }
}
