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
        val host = url.host
        val hostStore = cookieStore.computeIfAbsent(host) { ConcurrentHashMap() }
        for (cookie in cookies) {
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
        val store = cookieStore[domain] ?: return null
        val cookie = store[name] ?: return null
        if (cookie.expiresAt < System.currentTimeMillis()) {
            store.remove(name)
            return null
        }
        return cookie.value
    }

    fun putCookie(url: HttpUrl, cookie: Cookie) {
        val hostStore = cookieStore.computeIfAbsent(url.host) { ConcurrentHashMap() }
        hostStore[cookie.name] = cookie
    }

    fun clear() {
        cookieStore.clear()
    }
}
