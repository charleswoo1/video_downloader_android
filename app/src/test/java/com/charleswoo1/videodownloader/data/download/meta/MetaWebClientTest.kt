package com.charleswoo1.videodownloader.data.download.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaWebClientTest {

    @Test
    fun sanitizeUrl_redactsTokensAndKeys() {
        val sensitiveUrl = "https://instagram.com/reel/123/?token=SECRET123&sessionid=XYZ987&normal=keep"
        val sanitized = MetaWebClient.sanitizeUrl(sensitiveUrl)

        assertFalse("Token should be redacted", sanitized.contains("SECRET123"))
        assertFalse("Sessionid should be redacted", sanitized.contains("XYZ987"))
        assertTrue("Normal params should be kept", sanitized.contains("normal=keep"))
        assertTrue("Sanitized should contain REDACTED", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun formatSpeed_formatsAppropriately() {
        assertEquals("500 B/s", MetaWebClient.formatSpeed(500f))
        assertEquals("1.5 KiB/s", MetaWebClient.formatSpeed(1536f))
        assertEquals("2.0 MiB/s", MetaWebClient.formatSpeed(2 * 1024 * 1024f))
    }
}
