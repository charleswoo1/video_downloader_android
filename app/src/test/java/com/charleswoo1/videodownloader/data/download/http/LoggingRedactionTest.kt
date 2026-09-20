package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.data.download.YtDlpErrorParser
import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingRedactionTest {

    @Test
    fun httpSessionSanitize_redactsAllSensitiveSessionTokensAndCookies() {
        val sampleLog = "Request GET https://x.com/api/1.1?auth_token=super_secret_token&ct0=csrf_secret_123 Header: cookie: sessionid=meta_secret; ds_user_id=123456 Bearer AAAAAAAAAAAAAAAAAAAAA_secret"
        val sanitized = PlatformHttpSession.sanitizeLogText(sampleLog)

        assertFalse("auth_token value must not be leaked", sanitized.contains("super_secret_token"))
        assertFalse("ct0 value must not be leaked", sanitized.contains("csrf_secret_123"))
        assertFalse("sessionid value must not be leaked", sanitized.contains("meta_secret"))
        assertFalse("ds_user_id value must not be leaked", sanitized.contains("123456"))
        assertFalse("Bearer token must not be leaked", sanitized.contains("AAAAAAAAAAAAAAAAAAAAA_secret"))

        assertTrue(sanitized.contains("auth_token=[REDACTED]"))
        assertTrue(sanitized.contains("ct0=[REDACTED]"))
        assertTrue(sanitized.contains("sessionid=[REDACTED]"))
        assertTrue(sanitized.contains("Bearer [REDACTED]"))
    }

    @Test
    fun ytDlpErrorParserSanitize_redactsCookieFilesAndPrivatePaths() {
        val sampleStderr = "ERROR: Command failed with --cookies /data/user/0/com.charleswoo1.videodownloader/cache/ytdlp_sessions/sess_123.txt for user token=sensitive_tok"
        val sanitized = YtDlpErrorParser.sanitize(sampleStderr)

        assertFalse("Cookie file path must not be leaked", sanitized.contains("/data/user/0"))
        assertFalse("Token must not be leaked", sanitized.contains("sensitive_tok"))
        assertTrue(sanitized.contains("--cookies [REDACTED]"))
        assertTrue(sanitized.contains("token=[REDACTED]"))
    }

    @Test
    fun ytDlpErrorParser_ambiguousRateLimitMessage_mustNotBeClassifiedAsRateLimited() {
        val ambiguousStderr = "ERROR: Requested content is not available, rate-limit reached or login required"
        val parsed = YtDlpErrorParser.parse(ambiguousStderr, Platform.INSTAGRAM)

        // Handoff V3 Hard Rule: Ambiguous alternatives such as "rate-limit reached or login required"
        // MUST NOT be classified as confirmed rate limiting!
        assertFalse("Ambiguous error must NOT be classified as RATE_LIMITED", parsed.category == YtDlpErrorParser.ErrorCategory.RATE_LIMITED)
        assertEquals(YtDlpErrorParser.ErrorCategory.LOGIN_REQUIRED, parsed.category)
    }

    @Test
    fun ytDlpErrorParser_confirmedHttp429_isClassifiedAsRateLimited() {
        val trueRateLimitStderr = "ERROR: HTTP Error 429: Too Many Requests"
        val parsed = YtDlpErrorParser.parse(trueRateLimitStderr, Platform.INSTAGRAM)

        assertEquals(YtDlpErrorParser.ErrorCategory.RATE_LIMITED, parsed.category)
        assertTrue(parsed.userMessage.contains("存取頻率受限"))
    }

    @Test
    fun ytDlpErrorParser_expiredCookies_isClassifiedAsSessionExpired() {
        val expiredStderr = "WARNING: The provided Instagram account cookies are no longer valid"
        val parsed = YtDlpErrorParser.parse(expiredStderr, Platform.INSTAGRAM)

        assertEquals(YtDlpErrorParser.ErrorCategory.SESSION_EXPIRED, parsed.category)
        assertTrue(parsed.userMessage.contains("登入狀態已失效"))
    }
}
