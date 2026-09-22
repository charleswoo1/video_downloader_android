package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformWebLoginConfigTest {

    @Test
    fun instagramConfig_resolvesCorrectUrlsAndCookies() {
        val config = PlatformWebLoginCatalog.configFor(Platform.INSTAGRAM)
        assertNotNull(config)
        assertEquals(Platform.INSTAGRAM, config!!.platform)
        assertEquals("https://www.instagram.com/accounts/login/", config.loginUrl)
        assertEquals(listOf("https://www.instagram.com/"), config.cookieProbeUrls)

        assertEquals(setOf("sessionid"), config.requiredCookieNames)
        assertEquals(setOf("csrftoken", "ds_user_id"), config.optionalCookieNames)
        assertTrue(config.allowThirdPartyCookies)
        assertNull("Phase 1 postLoginProbe must be null", config.postLoginProbe)
    }

    @Test
    fun instagramConfig_hasExpectedIntermediatePatterns() {
        val config = PlatformWebLoginCatalog.INSTAGRAM
        assertTrue(config.intermediatePatterns.contains("/accounts/login/"))
        assertTrue(config.intermediatePatterns.contains("/challenge/"))
        assertTrue(config.intermediatePatterns.contains("/two_factor"))
        assertTrue(config.intermediatePatterns.contains("/accounts/onetap/"))
        assertTrue(config.intermediatePatterns.contains("/verify/"))
    }

    @Test
    fun threadsConfig_resolvesCorrectUrlsAndCookies() {
        val config = PlatformWebLoginCatalog.configFor(Platform.THREADS)
        assertNotNull(config)
        assertEquals(Platform.THREADS, config!!.platform)
        assertEquals("https://www.threads.com/login", config.loginUrl)
        assertEquals(listOf("https://www.threads.com/"), config.cookieProbeUrls)

        assertEquals(setOf("sessionid"), config.requiredCookieNames)
        assertEquals(setOf("csrftoken", "ds_user_id"), config.optionalCookieNames)
        assertTrue(config.allowThirdPartyCookies)
        assertNull("Phase 2 postLoginProbe must be null", config.postLoginProbe)
    }

    @Test
    fun threadsConfig_hasExpectedIntermediatePatterns() {
        val config = PlatformWebLoginCatalog.THREADS
        assertTrue(config.intermediatePatterns.contains("/login"))
        assertTrue(config.intermediatePatterns.contains("/accounts/login/"))
        assertTrue(config.intermediatePatterns.contains("/accounts/onetap/"))
        assertTrue(config.intermediatePatterns.contains("/accounts/password/"))
        assertTrue(config.intermediatePatterns.contains("/challenge/"))
        assertTrue(config.intermediatePatterns.contains("/two_factor"))
        assertTrue(config.intermediatePatterns.contains("/verify/"))
        assertTrue(config.intermediatePatterns.contains("security_check"))
        assertTrue(config.intermediatePatterns.contains("checkpoint"))
    }

    @Test
    fun xConfig_resolvesCorrectUrlsAndCookies() {
        val config = PlatformWebLoginCatalog.configFor(Platform.X)
        assertNotNull(config)
        assertEquals(Platform.X, config!!.platform)
        assertEquals("https://x.com/i/flow/login", config.loginUrl)
        assertEquals(listOf("https://x.com/"), config.cookieProbeUrls)

        assertEquals(setOf("auth_token", "ct0"), config.requiredCookieNames)
        assertEquals(setOf("twid", "kdt"), config.optionalCookieNames)
        assertTrue(config.allowThirdPartyCookies)
        assertNull("Phase 3 postLoginProbe must be null", config.postLoginProbe)
    }

    @Test
    fun xConfig_hasExpectedIntermediatePatterns() {
        val config = PlatformWebLoginCatalog.X
        assertTrue(config.intermediatePatterns.contains("/i/flow/login"))
        assertTrue(config.intermediatePatterns.contains("/i/flow/"))
        assertTrue(config.intermediatePatterns.contains("/login"))
        assertTrue(config.intermediatePatterns.contains("/account/access"))
        assertTrue(config.intermediatePatterns.contains("/account/login_verification"))
    }

    @Test
    fun unsupportedPlatformsInPhase3_returnNull() {
        assertNotNull("X login is enabled in Phase 3", PlatformWebLoginCatalog.configFor(Platform.X))
        assertNull("TikTok is not a web login platform", PlatformWebLoginCatalog.configFor(Platform.TIKTOK))
        assertNull("YouTube is not a web login platform", PlatformWebLoginCatalog.configFor(Platform.YOUTUBE))
        assertNull("Facebook is not a web login platform", PlatformWebLoginCatalog.configFor(Platform.FACEBOOK))
    }
}
