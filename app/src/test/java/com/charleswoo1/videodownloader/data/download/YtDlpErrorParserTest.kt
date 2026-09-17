package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpErrorParserTest {

    @Test
    fun parse_warningPlusError_prioritizesErrorOverWarning() {
        val stderr = """
            WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
            ERROR: [twitter] 18912345: Rate limit exceeded. Please wait a few minutes
        """.trimIndent()

        val result = YtDlpErrorParser.parse(stderr, Platform.X)

        // The user message must NOT contain the 90 days warning!
        assertFalse(result.userMessage.contains("older than 90 days"))
        assertEquals(YtDlpErrorParser.ErrorCategory.RATE_LIMITED, result.category)
        assertEquals("存取頻率受限 (Rate Limited)，請稍候再試", result.userMessage)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.first().contains("older than 90 days"))
    }

    @Test
    fun parse_multipleWarningsPlusError_prioritizesErrorAndCollectsAllWarnings() {
        val stderr = """
            WARNING: Your yt-dlp version is out of date
            WARNING: [youtube] Incomplete data received
            WARNING: [Merger] Non-standard stream layout
            ERROR: [youtube] dQw4w9WgXcQ: Private video. Sign in if you've been granted access
        """.trimIndent()

        val result = YtDlpErrorParser.parse(stderr, Platform.YOUTUBE)

        assertEquals(YtDlpErrorParser.ErrorCategory.PRIVATE_CONTENT, result.category)
        assertEquals("此影片設為私人內容，無法存取", result.userMessage)
        assertEquals(3, result.warnings.size)
        assertFalse(result.userMessage.contains("WARNING"))
    }

    @Test
    fun parse_errorOnly_parsesCorrectly() {
        val stderr = "ERROR: Unsupported URL: https://example.com/unsupported"

        val result = YtDlpErrorParser.parse(stderr, Platform.GENERIC)

        assertEquals(YtDlpErrorParser.ErrorCategory.UNSUPPORTED_URL, result.category)
        assertEquals("不支援的網址或尚未支援該網站之解析", result.userMessage)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parse_warningOnly_fallsBackToWarningAsExplanation() {
        val stderr = "WARNING: Direct access to media was blocked by upstream proxy"

        val result = YtDlpErrorParser.parse(stderr, Platform.GENERIC)

        assertEquals(1, result.warnings.size)
        assertTrue(result.userMessage.contains("Direct access to media was blocked"))
    }

    @Test
    fun parse_emptyStderr_returnsSafeFallback() {
        val emptyResult = YtDlpErrorParser.parse("", Platform.YOUTUBE)
        assertEquals(YtDlpErrorParser.ErrorCategory.UNKNOWN, emptyResult.category)
        assertTrue(emptyResult.userMessage.isNotBlank())

        val nullResult = YtDlpErrorParser.parse(null, Platform.YOUTUBE)
        assertEquals(YtDlpErrorParser.ErrorCategory.UNKNOWN, nullResult.category)
        assertTrue(nullResult.userMessage.isNotBlank())
    }

    @Test
    fun parse_sanitizesSensitiveCookiesAndTokens() {
        val stderr = """
            ERROR: [Instagram] Failed with sessionid=secret_session_12345&auth_token=super_secret_token
            WARNING: --cookies /data/user/0/com.app/cache/cookies.txt was loaded
        """.trimIndent()

        val result = YtDlpErrorParser.parse(stderr, Platform.INSTAGRAM)

        assertFalse(result.userMessage.contains("secret_session_12345"))
        assertFalse(result.userMessage.contains("super_secret_token"))
        assertFalse(result.warnings.first().contains("/data/user/0"))
    }

    @Test
    fun parse_instagramCheckpoint_categorizesCorrectly() {
        val stderr = "ERROR: [Instagram] checkpoint_required: Please confirm your identity"

        val result = YtDlpErrorParser.parse(stderr, Platform.INSTAGRAM)

        assertEquals(YtDlpErrorParser.ErrorCategory.CHECKPOINT_REQUIRED, result.category)
        assertEquals("Instagram 要求安全驗證 (checkpoint)，無法直接下載", result.userMessage)
    }

    @Test
    fun parse_loginRequired_categorizesCorrectly() {
        val stderr = "ERROR: [youtube] Sign in to confirm you’re not a bot"

        val result = YtDlpErrorParser.parse(stderr, Platform.YOUTUBE)

        assertEquals(YtDlpErrorParser.ErrorCategory.LOGIN_REQUIRED, result.category)
        assertEquals("來源網站需要登入帳號驗證，目前版本不支援登入下載", result.userMessage)
    }
}
