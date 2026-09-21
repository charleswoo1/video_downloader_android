package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCookieCaptureTest {

    private val instagramConfig = PlatformWebLoginCatalog.INSTAGRAM

    @Test
    fun extractCandidate_emptyOrBlank_returnsEmpty() {
        assertEquals(CookieCandidateResult.Empty, WebViewCookieCapture.extractCandidate(null, instagramConfig))
        assertEquals(CookieCandidateResult.Empty, WebViewCookieCapture.extractCandidate("", instagramConfig))
        assertEquals(CookieCandidateResult.Empty, WebViewCookieCapture.extractCandidate("   ", instagramConfig))
    }

    @Test
    fun extractCandidate_missingRequiredSessionId_returnsMissingRequired() {
        val raw = "csrftoken=csrf123; ds_user_id=1234567; mid=random_mid"
        val result = WebViewCookieCapture.extractCandidate(raw, instagramConfig)

        assertTrue(result is CookieCandidateResult.MissingRequired)
        val missing = (result as CookieCandidateResult.MissingRequired).missingNames
        assertEquals(setOf("sessionid"), missing)
    }

    @Test
    fun extractCandidate_onlySessionId_returnsCandidate() {
        val raw = "sessionid=test_session_id"
        val result = WebViewCookieCapture.extractCandidate(raw, instagramConfig)

        assertTrue(result is CookieCandidateResult.Candidate)
        val candidate = result as CookieCandidateResult.Candidate
        assertEquals(1, candidate.cookieCount)
        assertEquals("sessionid=test_session_id", candidate.filteredCookieHeader)
    }

    @Test
    fun extractCandidate_filtersAllowlistAndDropsTrackingCookies() {
        val raw = "mid=tracking_mid; sessionid=valid_sess; datr=tracking_datr; csrftoken=valid_csrf; ds_user_id=88888; rur=PRN; wd=393x851"
        val result = WebViewCookieCapture.extractCandidate(raw, instagramConfig)

        assertTrue(result is CookieCandidateResult.Candidate)
        val candidate = result as CookieCandidateResult.Candidate
        assertEquals(3, candidate.cookieCount)

        val pairs = candidate.filteredCookieHeader.split("; ").map { it.split("=") }.associate { it[0] to it[1] }
        assertEquals("valid_sess", pairs["sessionid"])
        assertEquals("valid_csrf", pairs["csrftoken"])
        assertEquals("88888", pairs["ds_user_id"])

        assertFalse("mid must be stripped", pairs.containsKey("mid"))
        assertFalse("datr must be stripped", pairs.containsKey("datr"))
        assertFalse("rur must be stripped", pairs.containsKey("rur"))
        assertFalse("wd must be stripped", pairs.containsKey("wd"))
    }

    @Test
    fun extractCandidate_duplicateCookieNames_latestWins() {
        val raw = "sessionid=first_val; csrftoken=csrf1; sessionid=second_val"
        val result = WebViewCookieCapture.extractCandidate(raw, instagramConfig)

        assertTrue(result is CookieCandidateResult.Candidate)
        val candidate = result as CookieCandidateResult.Candidate
        assertEquals(2, candidate.cookieCount)

        val pairs = candidate.filteredCookieHeader.split("; ").map { it.split("=") }.associate { it[0] to it[1] }
        assertEquals("second_val", pairs["sessionid"])
        assertEquals("csrf1", pairs["csrftoken"])
    }

    @Test
    fun candidateToString_doesNotLeakSecrets() {
        val raw = "sessionid=super_secret_session_token_12345"
        val result = WebViewCookieCapture.extractCandidate(raw, instagramConfig) as CookieCandidateResult.Candidate

        val stringRepr = result.toString()
        assertFalse(stringRepr.contains("super_secret"))
        assertFalse(stringRepr.contains("sessionid="))
        assertEquals("Candidate(cookieCount=1)", stringRepr)
    }

    @Test
    fun isAllowedNavigation_webSchemesAllowed_customSchemesBlocked() {
        assertTrue(WebViewCookieCapture.isAllowedNavigation("https://www.instagram.com/accounts/login/"))
        assertTrue(WebViewCookieCapture.isAllowedNavigation("http://www.instagram.com/"))
        assertTrue(WebViewCookieCapture.isAllowedNavigation("about:blank"))

        assertFalse(WebViewCookieCapture.isAllowedNavigation("instagram://user?username=test"))
        assertFalse(WebViewCookieCapture.isAllowedNavigation("intent://instagram.com/#Intent;package=com.instagram.android;end"))
        assertFalse(WebViewCookieCapture.isAllowedNavigation("market://details?id=com.instagram.android"))
        assertFalse(WebViewCookieCapture.isAllowedNavigation("javascript:alert(1)"))
    }

    @Test
    fun resolveFallbackUrl_extractsSafeHttpsFallback() {
        val intentUri = "intent://instagram.com/#Intent;package=com.instagram.android;scheme=https;S.browser_fallback_url=https%3A%2F%2Fwww.instagram.com%2Faccounts%2Flogin%2F;end"
        val fallback = WebViewCookieCapture.resolveFallbackUrl(intentUri)
        assertEquals("https://www.instagram.com/accounts/login/", fallback)
    }

    @Test
    fun resolveFallbackUrl_nonHttpOrMissing_returnsNull() {
        val nonIntent = "https://www.instagram.com"
        assertNull(WebViewCookieCapture.resolveFallbackUrl(nonIntent))

        val intentWithoutFallback = "intent://instagram.com/#Intent;package=com.instagram.android;end"
        assertNull(WebViewCookieCapture.resolveFallbackUrl(intentWithoutFallback))

        val intentWithBadFallback = "intent://instagram.com/#Intent;S.browser_fallback_url=market%3A%2F%2Fdetails;end"
        assertNull(WebViewCookieCapture.resolveFallbackUrl(intentWithBadFallback))
    }

    @Test
    fun normalizeUserAgent_removesWvAndVersionMarkers() {
        val rawUa = "Mozilla/5.0 (Linux; U; Android 14; Pixel 8 Build/UD1A.230803.041; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.88 Mobile Safari/537.36"
        val normalized = WebViewCookieCapture.normalizeUserAgent(rawUa)

        assertFalse("Normalized UA must not contain '; wv'", normalized.contains("; wv", ignoreCase = true))
        assertFalse("Normalized UA must not contain 'Version/4.0'", normalized.contains("Version/", ignoreCase = true))
        assertTrue("Chrome token must be preserved", normalized.contains("Chrome/128.0.6613.88"))
        assertTrue("Android OS must be preserved", normalized.contains("Android 14"))
    }

    @Test
    fun isCandidateOrigin_instagram_validOrigins_returnsTrue() {
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://www.instagram.com/", Platform.INSTAGRAM))
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://instagram.com/accounts/onetap/?next=%2F", Platform.INSTAGRAM))
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://i.instagram.com/api/v1/", Platform.INSTAGRAM))
        assertTrue(WebViewCookieCapture.isCandidateOrigin("http://instagram.com", Platform.INSTAGRAM))
    }

    @Test
    fun isCandidateOrigin_instagram_spoofedOrInvalidOrigins_returnsFalse() {
        assertFalse("Subdomain bypass must be rejected", WebViewCookieCapture.isCandidateOrigin("https://instagram.com.evil.example/", Platform.INSTAGRAM))
        assertFalse("Prefix bypass must be rejected", WebViewCookieCapture.isCandidateOrigin("https://evilinstagram.com/", Platform.INSTAGRAM))
        assertFalse("Google account origin must be rejected", WebViewCookieCapture.isCandidateOrigin("https://accounts.google.com/signin", Platform.INSTAGRAM))
        assertFalse("Facebook origin must be rejected for IG config", WebViewCookieCapture.isCandidateOrigin("https://m.facebook.com/login", Platform.INSTAGRAM))
        assertFalse("about:blank must be rejected", WebViewCookieCapture.isCandidateOrigin("about:blank", Platform.INSTAGRAM))
        assertFalse("null must be rejected", WebViewCookieCapture.isCandidateOrigin(null, Platform.INSTAGRAM))
        assertFalse("blank must be rejected", WebViewCookieCapture.isCandidateOrigin("   ", Platform.INSTAGRAM))
    }

    @Test
    fun isCandidateOrigin_threadsAndX_origins() {
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://www.threads.net/@user", Platform.THREADS))
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://threads.com/", Platform.THREADS))
        assertFalse(WebViewCookieCapture.isCandidateOrigin("https://threads.net.attacker.com/", Platform.THREADS))

        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://x.com/home", Platform.X))
        assertTrue(WebViewCookieCapture.isCandidateOrigin("https://twitter.com/login", Platform.X))
        assertFalse(WebViewCookieCapture.isCandidateOrigin("https://x.com.evil.com/", Platform.X))
    }

    @Test
    fun isIntermediateUrl_matchingPatterns_returnsTrue() {
        assertTrue(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/accounts/onetap/?next=%2F", instagramConfig))
        assertTrue(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/challenge/", instagramConfig))
        assertTrue(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/two_factor", instagramConfig))
        assertTrue(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/accounts/login/", instagramConfig))
    }

    @Test
    fun isIntermediateUrl_nonMatchingPatterns_returnsFalse() {
        assertFalse(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/reels/DA12345/", instagramConfig))
        assertFalse(WebViewCookieCapture.isIntermediateUrl("https://www.instagram.com/p/DB12345/", instagramConfig))
        assertFalse(WebViewCookieCapture.isIntermediateUrl(null, instagramConfig))
        assertFalse(WebViewCookieCapture.isIntermediateUrl("", instagramConfig))
    }
}
