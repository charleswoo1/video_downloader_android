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

    // 1. HTTP 200 + form_data.username + form_data.user_id -> ACTIVE
    @Test
    fun validateSession_instagram_http200_withUsernameAndUserId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig; csrftoken=test_csrf")
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)

        var interceptedUrl: String? = null
        var interceptedAppId: String? = null
        var interceptedRequestedWith: String? = null
        var interceptedAccept: String? = null
        var interceptedReferer: String? = null
        var interceptedUa: String? = null
        var interceptedCookie: String? = null

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val req = chain.request()
                interceptedUrl = req.url.toString()
                interceptedAppId = req.header("X-IG-App-ID")
                interceptedRequestedWith = req.header("X-Requested-With")
                interceptedAccept = req.header("Accept")
                interceptedReferer = req.header("Referer")
                interceptedUa = req.header("User-Agent")
                interceptedCookie = req.header("Cookie")

                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body("""{"form_data":{"username":"test_user","user_id":"12345678"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals("驗證成功 (已連線)", provider.sessionStatus(Platform.INSTAGRAM).details)
        assertFalse(provider.sessionStatus(Platform.INSTAGRAM).details?.contains("@") == true)
        assertFalse(provider.sessionStatus(Platform.INSTAGRAM).details?.contains("test_user") == true)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))

        assertEquals("https://www.instagram.com/api/v1/accounts/edit/web_form_data/", interceptedUrl)
        assertEquals("936619743392459", interceptedAppId)
        assertEquals("XMLHttpRequest", interceptedRequestedWith)
        assertEquals("application/json", interceptedAccept)
        assertEquals("https://www.instagram.com/accounts/edit/", interceptedReferer)
        assertTrue(interceptedCookie?.contains("sessionid=test_sess_ig") == true)
        assertTrue(interceptedUa?.contains("Chrome") == true)
        assertFalse("UA must not be native app UA", interceptedUa?.contains("Instagram") == true)
    }

    // 2. HTTP 200 + username + pk -> ACTIVE
    @Test
    fun validateSession_instagram_http200_withUsernameAndPk_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"form_data":{"username":"ig_creator","pk":87654321}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 3. HTTP 200 + username + no form ID + valid stored ds_user_id -> ACTIVE
    @Test
    fun validateSession_instagram_http200_withUsernameNoFormId_validDsUserId_becomesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig; ds_user_id=99887766")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"form_data":{"username":"cookie_fallback_user"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 4. HTTP 200 + username + no usable identity -> CONFIGURED
    @Test
    fun validateSession_instagram_http200_withUsernameNoUsableIdentity_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"form_data":{"username":"user_no_id"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 5. HTTP 200 + missing form_data -> CONFIGURED
    @Test
    fun validateSession_instagram_http200_missingFormData_remainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"status":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 6. login redirect -> EXPIRED
    @Test
    fun validateSession_instagram_loginRedirect_becomesExpired() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

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

    // 7. 401 -> EXPIRED
    @Test
    fun validateSession_instagram_http401_becomesExpired() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"message":"bad_token","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.EXPIRED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 8. explicit login_required -> EXPIRED
    @Test
    fun validateSession_instagram_explicitLoginRequired_becomesExpired() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"message":"login_required","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.EXPIRED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 9. 429 -> retain CONFIGURED
    @Test
    fun validateSession_instagram_http429_retainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("""{"message":"rate limited"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 10. 5xx -> retain CONFIGURED
    @Test
    fun validateSession_instagram_http5xx_retainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Server Error".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 11. network failure -> retain CONFIGURED
    @Test
    fun validateSession_instagram_networkFailure_retainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor {
                throw java.io.IOException("Connection reset by peer")
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // 12. malformed JSON -> retain CONFIGURED
    @Test
    fun validateSession_instagram_malformedJson_retainsConfigured() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><body>Maintenance In Progress</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 1: ACTIVE + HTTP 429 -> ACTIVE
    @Test
    fun validateSession_instagram_activeState_http429_preservesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")
        provider.markActive(Platform.INSTAGRAM, "驗證成功 (已連線)")
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("""{"message":"rate limited"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue("Must keep authenticated session on transient 429", provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 1: ACTIVE + HTTP 500 -> ACTIVE
    @Test
    fun validateSession_instagram_activeState_http500_preservesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")
        provider.markActive(Platform.INSTAGRAM, "驗證成功 (已連線)")
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Server Error".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue("Must keep authenticated session on transient 500", provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 1: ACTIVE + network failure -> ACTIVE
    @Test
    fun validateSession_instagram_activeState_networkFailure_preservesActive() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")
        provider.markActive(Platform.INSTAGRAM, "驗證成功 (已連線)")
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor {
                throw java.io.IOException("Connection reset by peer")
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertTrue("Must keep authenticated session on network failure", provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 2: 403 + checkpoint_required -> CONFIGURED
    @Test
    fun validateSession_instagram_http403_withCheckpointRequired_becomesConfiguredChallenge() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Content-Type", "application/json")
                    .body("""{"message":"checkpoint_required","checkpoint_url":"/challenge/","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals("需要完成 Instagram 安全驗證", provider.sessionStatus(Platform.INSTAGRAM).details)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 2: 401 + challenge_required -> CONFIGURED
    @Test
    fun validateSession_instagram_http401_withChallengeRequired_becomesConfiguredChallenge() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .header("Content-Type", "application/json")
                    .body("""{"message":"challenge_required","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals("需要完成 Instagram 安全驗證", provider.sessionStatus(Platform.INSTAGRAM).details)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 2: 200 + checkpoint_required -> CONFIGURED
    @Test
    fun validateSession_instagram_http200_withCheckpointRequired_becomesConfiguredChallenge() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"message":"checkpoint_required","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertEquals("需要完成 Instagram 安全驗證", provider.sessionStatus(Platform.INSTAGRAM).details)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    // Regression Blocker 2: user_has_logged_out -> EXPIRED
    @Test
    fun validateSession_instagram_userHasLoggedOut_becomesExpired() = kotlinx.coroutines.runBlocking {
        provider.importSession(Platform.INSTAGRAM, "sessionid=test_sess_ig")

        val mockSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Content-Type", "application/json")
                    .body("""{"message":"user_has_logged_out","status":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.EXPIRED, provider.sessionStatus(Platform.INSTAGRAM).state)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
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

    @Test
    fun importCapturedSession_validInstagramCandidate_becomesConfigured() {
        val capturedHeader = "sessionid=captured_sess_123; csrftoken=captured_csrf; ds_user_id=9999"
        val result = provider.importCapturedSession(Platform.INSTAGRAM, capturedHeader)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, info.state)
        assertEquals(3, info.cookieCount)
        assertFalse("CONFIGURED must not be active", provider.hasAuthenticatedSession(Platform.INSTAGRAM))

        val stored = store.getCookies(Platform.INSTAGRAM)
        assertEquals(3, stored.size)
        assertTrue(stored.any { it.name == "sessionid" && it.value == "captured_sess_123" })
    }

    @Test
    fun importCapturedSession_missingSessionId_fails() {
        val capturedHeader = "csrftoken=captured_csrf; ds_user_id=9999"
        val result = provider.importCapturedSession(Platform.INSTAGRAM, capturedHeader)

        assertTrue(result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.INSTAGRAM).state)
    }

    @Test
    fun importCapturedSession_empty_fails() {
        val result = provider.importCapturedSession(Platform.INSTAGRAM, "")
        assertTrue(result.isFailure)
    }

    @Test
    fun importCapturedSession_thenValidateSession_becomesActive() {
        val capturedHeader = "sessionid=captured_valid_sess; ds_user_id=55555"
        val importRes = provider.importCapturedSession(Platform.INSTAGRAM, capturedHeader)
        assertTrue(importRes.isSuccess)

        val successSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val json = """{"form_data":{"user_id":"55555","username":"test_captured_user"}}"""
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.INSTAGRAM, successSession)
        }
        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.ACTIVE, info.state)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertEquals(2, provider.cookiesFor(Platform.INSTAGRAM).size)
    }

    @Test
    fun importCapturedSession_validThreadsCandidate_becomesConfigured() {
        val capturedHeader = "sessionid=captured_threads_123; csrftoken=captured_csrf; ds_user_id=7777"
        val result = provider.importCapturedSession(Platform.THREADS, capturedHeader)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, info.state)
        assertEquals(3, info.cookieCount)
        assertFalse("CONFIGURED must not be active", provider.hasAuthenticatedSession(Platform.THREADS))

        val stored = store.getCookies(Platform.THREADS)
        assertEquals(3, stored.size)
        assertTrue(stored.any { it.name == "sessionid" && it.value == "captured_threads_123" })
        assertEquals("threads.com", stored[0].domain)
    }

    @Test
    fun importCapturedSession_threadsMissingSessionId_fails() {
        val capturedHeader = "csrftoken=captured_csrf; ds_user_id=7777"
        val result = provider.importCapturedSession(Platform.THREADS, capturedHeader)

        assertTrue(result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.THREADS).state)
    }

    @Test
    fun importCapturedSession_threadsThenValidateSession_becomesActive() {
        val capturedHeader = "sessionid=th_valid_sess; ds_user_id=66666"
        val importRes = provider.importCapturedSession(Platform.THREADS, capturedHeader)
        assertTrue(importRes.isSuccess)

        val successSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val body = """<html><body><input type="hidden" name="fb_dtsg" value="NAcTestDtsgToken" />"ACCOUNT_ID":"66666"</body></html>"""
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.THREADS, successSession)
        }
        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.ACTIVE, info.state)
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
        assertEquals(2, provider.cookiesFor(Platform.THREADS).size)
    }

    @Test
    fun importCapturedSession_validXCandidate_becomesConfigured() {
        val capturedHeader = "auth_token=captured_auth_123; ct0=captured_csrf_456; twid=u%3D123; kdt=captured_kdt"
        val result = provider.importCapturedSession(Platform.X, capturedHeader)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, info.state)
        assertEquals(4, info.cookieCount)
        assertFalse("CONFIGURED must not be active", provider.hasAuthenticatedSession(Platform.X))

        val stored = store.getCookies(Platform.X)
        assertEquals(4, stored.size)
        assertTrue(stored.any { it.name == "auth_token" && it.value == "captured_auth_123" })
        assertTrue(stored.any { it.name == "ct0" && it.value == "captured_csrf_456" })
        assertEquals("x.com", stored[0].domain)
    }

    @Test
    fun importCapturedSession_xMissingAuthToken_fails() {
        val capturedHeader = "ct0=captured_csrf_456; twid=u%3D123"
        val result = provider.importCapturedSession(Platform.X, capturedHeader)

        assertTrue(result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.X).state)
    }

    @Test
    fun importCapturedSession_xMissingCt0_fails() {
        val capturedHeader = "auth_token=captured_auth_123; twid=u%3D123"
        val result = provider.importCapturedSession(Platform.X, capturedHeader)

        assertTrue(result.isFailure)
        assertEquals(SessionState.NOT_CONFIGURED, provider.sessionStatus(Platform.X).state)
    }

    @Test
    fun importCapturedSession_xThenValidateSession_becomesActive() {
        val capturedHeader = "auth_token=x_valid_auth; ct0=x_valid_csrf"
        val importRes = provider.importCapturedSession(Platform.X, capturedHeader)
        assertTrue(importRes.isSuccess)

        val successSession = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val json = """{"screen_name":"testuser","id_str":"123456"}"""
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, successSession)
        }
        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.ACTIVE, info.state)
        assertTrue(provider.hasAuthenticatedSession(Platform.X))
        assertEquals(2, provider.cookiesFor(Platform.X).size)
    }

    @Test
    fun validateSession_firstEndpoint200WithIdentity_becomesActive() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf; twid=u%3D123"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val requestedUrls = mutableListOf<String>()
        val requestedHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val req = chain.request()
                requestedUrls.add(req.url.toString())
                for (name in req.headers.names()) {
                    requestedHeaders[name] = req.header(name) ?: ""
                }
                val json = """{"screen_name":"testuser","id_str":"987654321"}"""
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.ACTIVE, info.state)
        assertTrue(info.details?.contains("@testuser (987654321)") == true)
        assertEquals(1, requestedUrls.size)
        assertEquals("https://api.x.com/1.1/account/verify_credentials.json", requestedUrls[0])

        // Verify required headers
        assertEquals("OAuth2Session", requestedHeaders["x-twitter-auth-type"])
        assertEquals("x_test_csrf", requestedHeaders["x-csrf-token"])
        assertEquals("yes", requestedHeaders["x-twitter-active-user"])
        assertEquals("zh-tw", requestedHeaders["x-twitter-client-language"])
        assertEquals("https://x.com", requestedHeaders["Origin"])
        assertEquals("https://x.com/", requestedHeaders["Referer"])
        assertTrue(requestedHeaders["Cookie"]?.contains("auth_token=x_test_auth") == true)
        assertTrue(requestedHeaders["Cookie"]?.contains("ct0=x_test_csrf") == true)
        assertTrue(requestedHeaders["Cookie"]?.contains("twid=u%3D123") == true)
        assertTrue(requestedHeaders["Authorization"]?.startsWith("Bearer ") == true)
    }

    @Test
    fun validateSession_first404Second200WithIdentity_becomesActive() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val requestedUrls = mutableListOf<String>()

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)

                if (url.contains("verify_credentials")) {
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("Endpoint not found".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    val json = """{"screen_name":"fallback_user","language":"zh-tw"}"""
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.ACTIVE, info.state)
        assertTrue(info.details?.contains("@fallback_user") == true)
        assertEquals(2, requestedUrls.size)
        assertEquals("https://api.x.com/1.1/account/verify_credentials.json", requestedUrls[0])
        assertEquals("https://x.com/i/api/1.1/account/settings.json", requestedUrls[1])
    }

    @Test
    fun validateSession_allEndpoints404_remainsConfigured() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val requestedUrls = mutableListOf<String>()

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                val req = chain.request()
                requestedUrls.add(req.url.toString())
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("Endpoint not found".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals("All 404 must remain CONFIGURED, not EXPIRED", SessionState.CONFIGURED, info.state)
        assertTrue("Details must explain endpoints unavailable", info.details?.contains("404") == true)
        assertEquals(3, requestedUrls.size)
    }

    @Test
    fun validateSession_401ExplicitAuthRejection_becomesExpired() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"errors":[{"code":89,"message":"Invalid or expired token."}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.EXPIRED, info.state)
        assertTrue(info.details?.contains("401") == true)
    }

    @Test
    fun validateSession_403ExplicitAuthRejection_becomesExpired() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("""{"errors":[{"code":32,"message":"Could not authenticate you."}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.EXPIRED, info.state)
        assertTrue(info.details?.contains("403") == true)
    }

    @Test
    fun validateSession_403Challenge_remainsConfigured() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("<html><body>Cloudflare challenge</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals(SessionState.CONFIGURED, info.state)
        assertTrue(info.details?.contains("403") == true)
    }

    @Test
    fun validateSession_transientNetworkFailure_retainsPreviousState() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(
            retryPolicy = RetryPolicy.NO_RETRY,
            customClientBuilder = {
                addInterceptor {
                    throw java.io.IOException("Connection timeout")
                }
            }
        )

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals("Network failure must retain CONFIGURED", SessionState.CONFIGURED, info.state)
        assertTrue(info.details?.contains("網路連線失敗") == true)
    }

    @Test
    fun validateSession_429RateLimited_retainsPreviousState() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("Rate limit exceeded".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals("429 must retain previous state", SessionState.CONFIGURED, info.state)
        assertTrue(info.details?.contains("429") == true)
    }

    @Test
    fun validateSession_malformed200WithoutIdentity_remainsConfigured() {
        val capturedHeader = "auth_token=x_test_auth; ct0=x_test_csrf"
        provider.importCapturedSession(Platform.X, capturedHeader)

        val session = PlatformHttpSession(customClientBuilder = {
            addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok","unknown_field":true}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val valRes = kotlinx.coroutines.runBlocking {
            provider.validateSession(Platform.X, session)
        }

        assertTrue(valRes.isSuccess)
        val info = valRes.getOrThrow()
        assertEquals("200 without identity proof must remain CONFIGURED", SessionState.CONFIGURED, info.state)
        assertTrue(info.details?.contains("未檢測到有效帳號標記") == true)
    }
}
