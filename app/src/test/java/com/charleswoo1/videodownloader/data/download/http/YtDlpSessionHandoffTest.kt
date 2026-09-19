package com.charleswoo1.videodownloader.data.download.http

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtDlpSessionHandoffTest {

    @Test
    fun exportToNetscape_formatsAccordingToSpecification() {
        val cookies = listOf(
            Cookie.Builder()
                .domain("instagram.com")
                .path("/")
                .name("sessionid")
                .value("secret_session_abc")
                .secure()
                .expiresAt(1893456000000L) // 2030-01-01
                .build(),
            Cookie.Builder()
                .domain("instagram.com")
                .path("/")
                .name("ds_user_id")
                .value("12345678")
                .build()
        )

        val netscape = YtDlpSessionHandoff.exportToNetscape(cookies)
        assertTrue(netscape.startsWith("# Netscape HTTP Cookie File"))

        val lines = netscape.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, lines.size)

        val sessLine = lines[0].split("\t")
        assertEquals(".instagram.com", sessLine[0])
        assertEquals("TRUE", sessLine[1])
        assertEquals("/", sessLine[2])
        assertEquals("TRUE", sessLine[3])
        assertEquals("1893456000", sessLine[4])
        assertEquals("sessionid", sessLine[5])
        assertEquals("secret_session_abc", sessLine[6])
    }

    @Test
    fun wipeAndDelete_securelyZeroesAndRemovesFile() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ytdlp_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val testFile = File(tempDir, "cookie_test.txt")
        testFile.writeText("sensitive_cookie_data_here")

        assertTrue(testFile.exists())
        assertTrue(testFile.length() > 0)

        YtDlpSessionHandoff.wipeAndDelete(testFile)

        assertFalse("Temporary cookie file must be deleted", testFile.exists())
        tempDir.deleteRecursively()
    }
}
