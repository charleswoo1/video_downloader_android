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
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))

        val cookies = provider.cookiesFor(Platform.INSTAGRAM)
        assertEquals(3, cookies.size)
        assertTrue(cookies.any { it.name == "sessionid" && it.value == "test_sess_ig" })
    }

    @Test
    fun importSession_missingMandatorySessionId_fails() {
        val raw = "csrftoken=only_csrf; ds_user_id=12345"
        val result = provider.importSession(Platform.INSTAGRAM, raw)

        assertTrue(result.isFailure)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
    }

    @Test
    fun importSession_xValidAuthToken_becomesConfigured() {
        val raw = "auth_token=secret_auth_token_value; ct0=csrf_ct0_value"
        val result = provider.importSession(Platform.X, raw)

        assertTrue(result.isSuccess)
        assertEquals(SessionState.CONFIGURED, result.getOrThrow().state)
        assertTrue(provider.hasAuthenticatedSession(Platform.X))
        assertEquals(2, provider.cookiesFor(Platform.X).size)
    }

    @Test
    fun importMetaSession_populatesBothInstagramAndThreadsAsConfigured() {
        val raw = "sessionid=meta_shared_sess; ds_user_id=888; csrftoken=meta_csrf"
        val result = provider.importMetaSession(raw)

        assertTrue(result.isSuccess)
        val (igInfo, thInfo) = result.getOrThrow()
        assertEquals(SessionState.CONFIGURED, igInfo.state)
        assertEquals(SessionState.CONFIGURED, thInfo.state)
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
        assertEquals("instagram.com", provider.cookiesFor(Platform.INSTAGRAM)[0].domain)
        assertEquals("threads.net", provider.cookiesFor(Platform.THREADS)[0].domain)
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
                    .body("""{"status":"ok","user":{"pk":123}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        })

        val res = provider.validateSession(Platform.INSTAGRAM, mockSession)
        assertTrue(res.isSuccess)
        assertEquals(SessionState.ACTIVE, provider.sessionStatus(Platform.INSTAGRAM).state)
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
        assertTrue(provider.hasAuthenticatedSession(Platform.THREADS))
    }

    @Test
    fun validateSession_threadsSuccessAndExpired_workIndependently() = kotlinx.coroutines.runBlocking {
        provider.importMetaSession("sessionid=shared_sess")
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
        assertTrue(provider.hasAuthenticatedSession(Platform.X))

        provider.markExpired(Platform.X, "HTTP 401 Unauthorized")
        val status = provider.sessionStatus(Platform.X)
        assertEquals(SessionState.EXPIRED, status.state)
        assertEquals("HTTP 401 Unauthorized", status.details)
        assertFalse(provider.hasAuthenticatedSession(Platform.X))
        // cookiesFor returns empty list when session is expired
        assertTrue(provider.cookiesFor(Platform.X).isEmpty())
    }

    @Test
    fun clearSession_resetsToNotConfigured() {
        provider.importSession(Platform.INSTAGRAM, "sessionid=ig_sess")
        assertTrue(provider.hasAuthenticatedSession(Platform.INSTAGRAM))

        provider.clearSession(Platform.INSTAGRAM)
        val status = provider.sessionStatus(Platform.INSTAGRAM)
        assertEquals(SessionState.NOT_CONFIGURED, status.state)
        assertEquals(0, status.cookieCount)
        assertFalse(provider.hasAuthenticatedSession(Platform.INSTAGRAM))
        assertTrue(provider.cookiesFor(Platform.INSTAGRAM).isEmpty())
    }
}
