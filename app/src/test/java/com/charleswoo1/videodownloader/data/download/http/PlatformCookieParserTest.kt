package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCookieParserTest {

    @Test
    fun parseHeader_instagramCookies_succeedsAndDefaultsDomain() {
        val header = "sessionid=test_session_12345; ds_user_id=12345678; csrftoken=csrf_test_abc"
        val cookies = PlatformCookieParser.parse(header, Platform.INSTAGRAM)

        assertEquals(3, cookies.size)
        val sessionCookie = cookies.find { it.name == "sessionid" }
        assertNotNull(sessionCookie)
        assertEquals("test_session_12345", sessionCookie?.value)
        assertEquals("instagram.com", sessionCookie?.domain)

        val dsCookie = cookies.find { it.name == "ds_user_id" }
        assertEquals("12345678", dsCookie?.value)
    }

    @Test
    fun parseNetscape_threadsCookies_preservesDomainAndExpires() {
        val netscape = """
            # Netscape HTTP Cookie File
            .threads.net	TRUE	/	TRUE	1893456000	sessionid	threads_sess_999
            .threads.net	TRUE	/	TRUE	1893456000	csrftoken	csrf_token_threads
            .instagram.com	TRUE	/	TRUE	1893456000	ignored_ig_cookie	ignore_me
        """.trimIndent()

        val cookies = PlatformCookieParser.parse(netscape, Platform.THREADS)

        // Only threads.net cookies should be accepted; instagram.com must be filtered out for domain isolation
        assertEquals(2, cookies.size)
        val sess = cookies.find { it.name == "sessionid" }
        assertNotNull(sess)
        assertEquals("threads_sess_999", sess?.value)
        assertEquals("threads.net", sess?.domain)
        assertTrue(sess!!.expiresAt > 0L)
    }

    @Test
    fun parseJson_xCookies_parsesAndIsolatesProperly() {
        val json = """
            [
                {
                    "name": "auth_token",
                    "value": "x_auth_secret_999",
                    "domain": ".x.com",
                    "path": "/",
                    "secure": true,
                    "expirationDate": 1893456000.0
                },
                {
                    "name": "ct0",
                    "value": "x_csrf_ct0_value",
                    "domain": ".x.com",
                    "path": "/",
                    "secure": true
                },
                {
                    "name": "meta_cookie",
                    "value": "should_be_dropped",
                    "domain": ".instagram.com",
                    "path": "/"
                }
            ]
        """.trimIndent()

        val cookies = PlatformCookieParser.parse(json, Platform.X)

        assertEquals(2, cookies.size)
        val auth = cookies.find { it.name == "auth_token" }
        assertNotNull(auth)
        assertEquals("x_auth_secret_999", auth?.value)
        assertEquals("x.com", auth?.domain)

        val ct0 = cookies.find { it.name == "ct0" }
        assertNotNull(ct0)
        assertEquals("x_csrf_ct0_value", ct0?.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_blankInput_throwsIllegalArgument() {
        PlatformCookieParser.parse("   \n\t  ", Platform.X)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_wrongPlatformDomainOnly_throwsIllegalArgument() {
        val metaOnly = """
            .instagram.com	TRUE	/	TRUE	1893456000	sessionid	12345
        """.trimIndent()

        // Importing instagram cookie into X must be rejected by domain isolation
        PlatformCookieParser.parse(metaOnly, Platform.X)
    }
}
