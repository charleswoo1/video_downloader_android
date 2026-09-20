package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthenticatedPlatformSessionProviderTest {

    private lateinit var store: InMemoryPlatformCredentialStore
    private lateinit var provider: AuthenticatedPlatformSessionProvider

    @Before
    fun setUp() {
        store = InMemoryPlatformCredentialStore()
        provider = AuthenticatedPlatformSessionProvider(store)
    }

    @Test
    fun initialStatus_isNotConfigured() {
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertFalse(provider.hasAuthenticatedSession(Platform.X))
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue(provider.cookiesFor(Platform.INSTAGRAM).isEmpty())
    }

    @Test
    fun importSession_instagramValidSession_becomesConfigured() {
        val raw = "sessionid=test_sess_ig; ds_user_id=12345; csrftoken=csrf123"
        val result = provider.importSession(Platform.INSTAGRAM, raw)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, info.state)
        assertEquals(3, info.cookieCount)
        // CONFIGURED credentials must NOT be exposed to extraction engines
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.cookiesFor(Platform.INSTAGRAM).isEmpty())

        // Credentials are stored and available to validator
        val stored = store.getCookies(Platform.INSTAGRAM)
        assertEquals(3, stored.size)
        assertTrue(stored.any { it.name == "sessionid" && it.value == "test_sess_ig" })
    }

    @Test
    fun importSession_missingMandatorySessionId_fails() {
        val raw = "csrftoken=only_csrf; ds_user_id=12345"
        val result = provider.importSession(Platform.INSTAGRAM, raw)

        assertTrue(result.isFailure)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    @Test
    fun importSession_xValidAuthTokenAndCt0_becomesConfigured() {
        val raw = "auth_token=secret_auth_token_value; ct0=csrf_ct0_value"
        val result = provider.importSession(Platform.X, raw)

        assertTrue(result.isSuccess)
        assertEquals(SessionState.CONFIGURED, result.getOrThrow().state)
        assertFalse(provider.hasAuthenticatedSession(Platform.X))
        assertTrue(provider.cookiesFor(Platform.X).isEmpty())
        assertEquals(2, store.getCookies(Platform.X).size)
    }

    @Test
    fun importSession_xMissingCt0_fails() {
        val raw = "auth_token=secret_auth_token_value; other=val"
        val result = provider.importSession(Platform.X, raw)

        assertTrue("Importing X without ct0 must fail", result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.X).state)
    }

    @Test
    fun importMetaSession_domainlessInput_fails() {
        val raw = "sessionid=meta_shared_sess; ds_user_id=888; csrftoken=meta_csrf"
        val result = provider.importMetaSession(raw)

        assertTrue("Domainless raw header must be rejected by importMetaSession", result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
    }

    @Test
    fun importMetaSession_validNetscapeWithBothIgAndThreads_succeedsAtomically() {
        val netscape = """
            # Netscape HTTP Cookie File
            .instagram.com	TRUE	/	TRUE	1893456000	sessionid	ig_sess_123
            .threads.com	TRUE	/	TRUE	1893456000	sessionid	th_sess_456
        """.trimIndent()
        val result = provider.importMetaSession(netscape)

        assertTrue(result.isSuccess)
        val (igInfo, thInfo) = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, igInfo.state)
        assertEquals(SessionState.CONFIGURED, thInfo.state)
        assertEquals("instagram.com", store.getCookies(Platform.INSTAGRAM)[0].domain)
        assertEquals("threads.com", store.getCookies(Platform.THREADS)[0].domain)
    }

    @Test
    fun importMetaSession_missingOnePlatform_doesNotPerformPartialWrite() {
        val netscapeIgOnly = """
            # Netscape HTTP Cookie File
            .instagram.com	TRUE	/	TRUE	1893456000	sessionid	ig_sess_123
        """.trimIndent()
        val result = provider.importMetaSession(netscapeIgOnly)

        assertTrue(result.isFailure)
        // Neither platform should be written
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(store.getCookies(Platform.INSTAGRAM).isEmpty())
        assertTrue(store.getCookies(Platform.THREADS).isEmpty())
    }

    @Test
    fun importSession_platformSpecificImports_workIndependently() {
        // Instagram-only import does not require Threads cookies
        val igResult = provider.importSession(Platform.INSTAGRAM, "sessionid=ig_only_sess")
        assertTrue(igResult.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        // Threads-only import does not require Instagram cookies
        val thResult = provider.importSession(Platform.THREADS, "sessionid=th_only_sess")
        assertTrue(thResult.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
    }

    @Test
    fun validateSession_instagramSuccess_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=valid_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok","user":{"pk":123,"username":"ig_user"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertEquals(1, provider.cookiesFor(Platform.INSTAGRAM).size)
    }

    @Test
    fun validateSession_instagramWithoutUserObject_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=valid_sess")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.sessionStatus(Platform.INSTAGRAM).details?.contains("未檢測到有效登入帳號資料") == true)
    }

    @Test
    fun validateSession_instagramWithNullUser_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=valid_sess")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"user":null,"status":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.sessionStatus(Platform.INSTAGRAM).details?.contains("未檢測到有效登入帳號資料") == true)
    }

    @Test
    fun validateSession_instagramStatusOk_usernameOnlyWithoutPk_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=valid_sess")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok","user":{"username":"ig_username_only"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        // Username without numeric pk/id must NOT mark ACTIVE
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.sessionStatus(Platform.INSTAGRAM).details?.contains("未檢測到有效登入帳號資料") == true)
    }

    @Test
    fun validateSession_instagramRejection_becomesExpired() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=expired_sess")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(302)
                    .header("Location", "https://www.instagram.com/accounts/login/")
                    .message("Found")
                    .body("Redirecting to login".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.EXPIRED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.cookiesFor(Platform.INSTAGRAM).isEmpty())
    }

    @Test
    fun validateSession_threadsPublic200WithoutAuthMarker_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        // Public Threads homepage returns HTTP 200 without login markers
        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><head><title>Threads</title></head><body>Public feed</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // Public 200 MUST NOT mark session ACTIVE
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("未檢測到有效登入憑證與帳號標記") == true)
    }

    @Test
    fun validateSession_threadsWithDtsgOnlyWithoutUserId_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_token_123"}]</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // DTSG without numeric user ID must NOT mark ACTIVE
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("未檢測到有效登入憑證與帳號標記") == true)
    }

    @Test
    fun validateSession_threadsWithUserIdOnlyWithoutDtsg_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>{"ACCOUNT_ID":"987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // Numeric user ID without DTSG token must NOT mark ACTIVE
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("未檢測到有效登入憑證與帳號標記") == true)
    }

    @Test
    fun validateSession_threadsWithBothDtsgAndUserId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_token_123"}],{"ACCOUNT_ID":"987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
        assertEquals(1, provider.cookiesFor(Platform.THREADS).size)
    }

    @Test
    fun validateSession_threadsArbitraryTokenWithUserId_withoutDtsg_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>{"token":"AQarbitrary_token","ACCOUNT_ID":"987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // Arbitrary AQ-looking token without DTSGInitialData / fb_dtsg must NOT mark ACTIVE
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("未檢測到有效登入憑證與帳號標記") == true)
    }

    @Test
    fun validateSession_threadsDtsgTokenWithDsUserIdCookie_withoutPageUserId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess; ds_user_id=12345678")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_dtsg"}]</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsDtsgTokenWithDocumentedPageUserId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_dtsg"}],{"IG_USER_EIMU":"987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsDtsgWithWhitespaceAccountId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_dtsg"}],{"ACCOUNT_ID": "987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsDtsgWithArbitraryUndocumentedMarker_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_dtsg"}],{"actor_id": "987654321", "currentUser": "987654321"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("未檢測到有效登入憑證與帳號標記") == true)
    }

    @Test
    fun validateSession_threadsDtsgWithShortNonCredibleUserId_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><script>["DTSGInitialData",[],{"token":"AQtest_dtsg"}],{"ACCOUNT_ID": "12"}</script></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // Less than 3 digits (\d{3,}) must not be accepted as credible user id
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsDsUserIdWithoutDtsgProof_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=valid_th_sess; ds_user_id=987654321")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""<html><body>Page without DTSG token</body></html>""".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // ds_user_id without valid DTSG token MUST remain CONFIGURED
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_transientNetworkFailure_preservesConfiguredState() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.THREADS, "sessionid=any_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { _ ->
                throw java.io.IOException("Simulated network timeout")
            }
        })

        val res = provider.validateSession(Platform.THREADS, mockSession)
        assertTrue(res.isSuccess)
        // Must NOT falsely expire on network failure
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
        assertTrue(provider.sessionStatus(Platform.THREADS).details?.contains("網路連線失敗") == true)
        assertFalse(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsSuccessAndExpired_workIndependently() = kotlinx.coroutines.runBlocking {
        val netscape = """
            # Netscape HTTP Cookie File
            .instagram.com	TRUE	/	TRUE	1893456000	sessionid	ig_sess_123
            .threads.com	TRUE	/	TRUE	1893456000	sessionid	th_sess_456
        """.trimIndent()
        provider.importMetaSession(netscape)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.THREADS).state)

        // Validate Threads with rejection
        val rejectThreadsSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(302)
                    .header("Set-Cookie", "sessionid=deleted; path=/")
                    .message("Found")
                    .body("".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        provider.validateSession(Platform.THREADS, rejectThreadsSession)
        assertEquals(SessionState.EXPIRED, provider.sessionStatus(Platform.THREADS).state)
        // Instagram remains CONFIGURED, not affected by Threads failure
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
    }

    @Test
    fun markExpired_marksStateExpiredAndHidesCookies() {
        provider.importSession(Platform.X, "auth_token=tok; ct0=ct")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.X).state)

        provider.markExpired(Platform.X, "HTTP 401 Unauthorized")
        val status = provider.sessionStatus(Platform.X)
        assertEquals(SessionState.EXPIRED, status.state)
        assertEquals("HTTP 401 Unauthorized", status.details)
        assertFalse(provider.hasAuthenticatedSession(Platform.X))
        assertTrue(provider.cookiesFor(Platform.X).isEmpty())
    }

    @Test
    fun clearSession_resetsToNotConfigured() {
        provider.importSession(Platform.INSTAGRAM, "sessionid=ig_sess")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)

        provider.clearSession(Platform.INSTAGRAM)
        val status = provider.sessionStatus(Platform.INSTAGRAM)
        assertEquals(SessionState.NOT_CONFIGURED, status.state)
        assertEquals(0, status.cookieCount)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.cookiesFor(Platform.INSTAGRAM).isEmpty())
    }
}
