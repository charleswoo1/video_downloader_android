package com.charleswoo1.videodownloader.data.download.http

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformHttpSessionTest {

    @Test
    fun browserIdentity_requestProfileHeaders_areCoherent() {
        val desktopHeaders = RequestProfile.DESKTOP_NAVIGATION.buildHeaders(BrowserIdentity.DESKTOP)
        assertEquals(BrowserIdentity.DESKTOP.userAgent, desktopHeaders["User-Agent"])
        assertEquals("\"Windows\"", desktopHeaders["sec-ch-ua-platform"])
        assertEquals("?0", desktopHeaders["sec-ch-ua-mobile"])
        assertEquals("document", desktopHeaders["sec-fetch-dest"])
        assertEquals("navigate", desktopHeaders["sec-fetch-mode"])

        val mobileHeaders = RequestProfile.MOBILE_NAVIGATION.buildHeaders(BrowserIdentity.MOBILE)
        assertEquals(BrowserIdentity.MOBILE.userAgent, mobileHeaders["User-Agent"])
        assertEquals("\"Android\"", mobileHeaders["sec-ch-ua-platform"])
        assertEquals("?1", mobileHeaders["sec-ch-ua-mobile"])

        val crawlerHeaders = RequestProfile.CRAWLER_NAVIGATION.buildHeaders(BrowserIdentity.CRAWLER)
        assertEquals(BrowserIdentity.CRAWLER.userAgent, crawlerHeaders["User-Agent"])
        assertNull("Crawler should not have sec-ch-ua", crawlerHeaders["sec-ch-ua"])

        val apiHeaders = RequestProfile.API.buildHeaders(
            BrowserIdentity.DESKTOP,
            origin = "https://x.com",
            referer = "https://x.com/"
        )
        assertEquals("application/json", apiHeaders["content-type"])
        assertEquals("cors", apiHeaders["sec-fetch-mode"])
        assertEquals("https://x.com", apiHeaders["origin"])

        val mediaHeaders = RequestProfile.MEDIA.buildHeaders(BrowserIdentity.DESKTOP)
        assertEquals("*/*", mediaHeaders["Accept"])
        assertEquals("cross-site", mediaHeaders["sec-fetch-site"])
    }

    @Test
    fun platformCookieJar_managesAndPersistsAnonymousCookiesInMemory() {
        val jar = PlatformCookieJar()
        val url = "https://x.com/status/123".toHttpUrl()

        val cookie = Cookie.Builder()
            .domain("x.com")
            .path("/")
            .name("gt")
            .value("1234567890")
            .build()

        jar.saveFromResponse(url, listOf(cookie))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("gt", loaded[0].name)
        assertEquals("1234567890", loaded[0].value)

        val retrievedValue = jar.getCookieValue("x.com", "gt")
        assertEquals("1234567890", retrievedValue)

        jar.clear()
        assertNull(jar.getCookieValue("x.com", "gt"))
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun platformCookieJar_preservesCookieDomainSemantics_fromSubdomainToParentDomain() {
        val jar = PlatformCookieJar()
        val apiUrl = "https://api.x.com/1.1/guest/activate.json".toHttpUrl()

        // Set-Cookie received from api.x.com with Domain=.x.com
        val cookie = Cookie.Builder()
            .domain("x.com")
            .path("/")
            .name("gt")
            .value("guest_token_abc_123")
            .build()

        jar.saveFromResponse(apiUrl, listOf(cookie))

        // NativeXEngine queries getCookieValue("x.com", "gt")
        val retrievedValue = jar.getCookieValue("x.com", "gt")
        assertEquals("guest_token_abc_123", retrievedValue)

        // Loading for request to x.com or api.x.com should find the cookie
        val loadedForXCom = jar.loadForRequest("https://x.com/status/123".toHttpUrl())
        assertEquals(1, loadedForXCom.size)
        assertEquals("guest_token_abc_123", loadedForXCom[0].value)

        val loadedForApiXCom = jar.loadForRequest("https://api.x.com/graphql/abc".toHttpUrl())
        assertEquals(1, loadedForApiXCom.size)
        assertEquals("guest_token_abc_123", loadedForApiXCom[0].value)
    }

    @Test
    fun platformCookieJar_hostOnlyCookie_doesNotMatchParentDomain() {
        val jar = PlatformCookieJar()
        val apiUrl = "https://api.x.com/1.1/guest/activate.json".toHttpUrl()

        val hostOnlyCookie = Cookie.Builder()
            .hostOnlyDomain("api.x.com")
            .path("/")
            .name("gt")
            .value("host_only_token_value")
            .build()

        jar.saveFromResponse(apiUrl, listOf(hostOnlyCookie))

        // Querying for parent domain x.com MUST return null for host-only cookie
        val parentValue = jar.getCookieValue("x.com", "gt")
        assertNull("Host-only cookie for api.x.com must not be returned for parent domain x.com", parentValue)

        // Querying for exact host api.x.com MUST return the value
        val exactValue = jar.getCookieValue("api.x.com", "gt")
        assertEquals("host_only_token_value", exactValue)
    }

    @Test
    fun retryPolicy_computesBoundedExponentialDelay() {
        val policy = RetryPolicy(maxRetries = 3, initialBackoffMs = 200L, backoffMultiplier = 2.0, maxBackoffMs = 1000L)

        assertEquals(0L, policy.computeDelayMs(0))

        val delay1 = policy.computeDelayMs(1)
        assertTrue("Attempt 1 delay should be >= 200ms", delay1 >= 200L)

        val delay2 = policy.computeDelayMs(2)
        assertTrue("Attempt 2 delay should be >= 400ms", delay2 >= 400L)

        val delay4 = policy.computeDelayMs(4)
        assertTrue("Delay should be capped near maxBackoffMs", delay4 <= 1300L)
    }

    @Test
    fun sanitizeLogText_redactsTokensAndCookies() {
        val raw = "GET https://x.com/api?token=secret123&sessionid=mysession&gt=1998877"
        val sanitized = PlatformHttpSession.sanitizeLogText(raw)

        assertFalse("Token must not be visible", sanitized.contains("secret123"))
        assertFalse("Sessionid must not be visible", sanitized.contains("mysession"))
        assertFalse("Guest token must not be visible", sanitized.contains("1998877"))
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("sessionid=[REDACTED]"))
        assertTrue(sanitized.contains("gt=[REDACTED]"))
    }
}
