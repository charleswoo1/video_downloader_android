package com.charleswoo1.videodownloader.ui.auth

import com.charleswoo1.videodownloader.data.download.http.CookieScrubber
import com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo
import com.charleswoo1.videodownloader.data.download.http.PlatformWebLoginCatalog
import com.charleswoo1.videodownloader.data.download.http.SessionState
import com.charleswoo1.videodownloader.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformWebLoginCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeCookieScrubber(var autoExecuteCallback: Boolean = true) : CookieScrubber {
        var scrubCallCount = 0
        var pendingCallback: ((Boolean) -> Unit)? = null

        override fun scrubCookies(onComplete: (Boolean) -> Unit) {
            scrubCallCount++
            if (autoExecuteCallback) {
                onComplete(true)
            } else {
                pendingCallback = onComplete
            }
        }

        fun completePending(result: Boolean = true) {
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(result)
        }
    }

    // 1. prepareFreshLogin: login URL is not loaded before cookie-clear completion; load begins only after callback.
    @Test
    fun prepareFreshLogin_doesNotLoadUrlBeforeScrubbingCompletes() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber(autoExecuteCallback = false)
        var loadedUrl: String? = null
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1)) },
            cookieProbe = { null },
            scope = this
        )

        coordinator.prepareFreshLogin { loadedUrl = it }

        assertNull("Login URL must not be loaded before scrubbing completes", loadedUrl)
        assertEquals(PlatformWebLoginState.Preparing, coordinator.state.value)

        scrubber.completePending(true)

        assertEquals(PlatformWebLoginCatalog.INSTAGRAM.loginUrl, loadedUrl)
        assertEquals(PlatformWebLoginState.LoadingLogin, coordinator.state.value)
    }

    // 2. no cookie: state transitions to AwaitingUser.
    @Test
    fun onPageFinished_noCookies_transitionsToAwaitingUser() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1)) },
            cookieProbe = { null },
            scope = this
        )
        coordinator.onPageStarted("https://www.instagram.com/accounts/login/")
        assertEquals(PlatformWebLoginState.LoadingLogin, coordinator.state.value)

        var successCalled = false
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/accounts/login/") {
            successCalled = true
        }

        advanceUntilIdle()
        assertEquals(PlatformWebLoginState.AwaitingUser, coordinator.state.value)
        assertFalse(successCalled)
    }

    // 3. candidate + ACTIVE: state transitions to Active, close/return requested via callback.
    @Test
    fun onPageFinished_candidateWithActiveValidation_transitionsToActiveAndInvokesCallback() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        var importedCookie: String? = null
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = {
                importedCookie = it
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 3))
            },
            validateSessionAction = {
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 3, details = "12345"))
            },
            cookieProbe = { "sessionid=test_sess; ds_user_id=12345; csrftoken=test_csrf; mid=strip_me" },
            scope = this
        )

        var successCalled = false
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/") {
            successCalled = true
        }

        advanceUntilIdle()
        assertTrue(coordinator.state.value is PlatformWebLoginState.Active)
        assertTrue("onActiveSuccess callback must be invoked", successCalled)
        assertEquals("sessionid=test_sess; ds_user_id=12345; csrftoken=test_csrf", importedCookie)
    }

    // 4. candidate + CONFIGURED: state transitions to Challenge (recoverable), no success callback.
    @Test
    fun onPageFinished_candidateWithConfiguredSession_transitionsToChallengeAndAllowsRetry() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            cookieProbe = { "sessionid=test_sess" },
            scope = this
        )

        var successCalled = false
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/") {
            successCalled = true
        }

        advanceUntilIdle()
        assertTrue(coordinator.state.value is PlatformWebLoginState.Challenge)
        assertFalse("onActiveSuccess must not be called when state is CONFIGURED", successCalled)
        assertFalse("isValidating lock must be released for user retry", coordinator.isValidating.get())
    }

    // 5. candidate + EXPIRED: state transitions to Error (rejected/retry), no success callback.
    @Test
    fun onPageFinished_candidateWithExpiredSession_transitionsToErrorAndAllowsRetry() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.EXPIRED, 1)) },
            cookieProbe = { "sessionid=test_sess" },
            scope = this
        )

        var successCalled = false
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/") {
            successCalled = true
        }

        advanceUntilIdle()
        val state = coordinator.state.value
        assertTrue(state is PlatformWebLoginState.Error)
        assertTrue("Error must be retryable", (state as PlatformWebLoginState.Error).canRetry)
        assertFalse("onActiveSuccess must not be called when session is EXPIRED", successCalled)
        assertFalse("isValidating lock must be released for user retry", coordinator.isValidating.get())
    }

    // 6. onCancelOrExit: cookie scrubbing requested and completes cleanly.
    @Test
    fun onCancelOrExit_scrubsCookiesAndInvokesCompletionCallback() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber(autoExecuteCallback = false)
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1)) },
            cookieProbe = { null },
            scope = this
        )

        var exitCompleted = false
        coordinator.onCancelOrExit {
            exitCompleted = true
        }

        assertEquals(1, scrubber.scrubCallCount)
        assertFalse(exitCompleted)

        scrubber.completePending(true)
        assertTrue(exitCompleted)
        assertFalse(coordinator.isValidating.get())
    }

    // 7. intermediate URL + valid sessionid: validation is NOT suppressed and proceeds.
    @Test
    fun onPageFinished_intermediateUrlWithValidSessionId_validationNotSuppressed() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        var importCalled = false
        var validateCalled = false
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = {
                importCalled = true
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1))
            },
            validateSessionAction = {
                validateCalled = true
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1))
            },
            cookieProbe = { "sessionid=valid_sess" },
            scope = this
        )

        var successCalled = false
        // Intermediate URL /accounts/onetap/
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/accounts/onetap/?next=%2F") {
            successCalled = true
        }

        advanceUntilIdle()
        assertTrue("Import must be called on intermediate URL with candidate cookies", importCalled)
        assertTrue("Validate must be called on intermediate URL with candidate cookies", validateCalled)
        assertTrue(coordinator.state.value is PlatformWebLoginState.Active)
        assertTrue(successCalled)
    }

    // 8. non-Instagram current origin + valid sessionid: candidate validation is NOT triggered.
    @Test
    fun onPageFinished_nonInstagramOriginWithValidSessionId_validationNotTriggered() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        var importCalled = false
        var validateCalled = false
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = {
                importCalled = true
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1))
            },
            validateSessionAction = {
                validateCalled = true
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1))
            },
            cookieProbe = { "sessionid=valid_sess" },
            scope = this
        )

        var successCalled = false
        // Subdomain bypass attempt
        coordinator.onPageFinishedOrHistory("https://instagram.com.evil.example/login") {
            successCalled = true
        }

        advanceUntilIdle()
        assertFalse("Import must NOT be called for non-Instagram origin", importCalled)
        assertFalse("Validate must NOT be called for non-Instagram origin", validateCalled)
        assertFalse(successCalled)
        assertFalse(coordinator.isValidating.get())

        // Also test about:blank
        coordinator.onPageFinishedOrHistory("about:blank") {
            successCalled = true
        }
        advanceUntilIdle()
        assertFalse("Import must NOT be called for about:blank", importCalled)
        assertFalse(successCalled)
    }

    @Test
    fun onRedirectLoopDetected_setsErrorWithCannotRetry() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1)) },
            validateSessionAction = { Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1)) },
            cookieProbe = { null },
            scope = this
        )

        coordinator.onRedirectLoopDetected()
        val state = coordinator.state.value
        assertTrue(state is PlatformWebLoginState.Error)
        assertFalse((state as PlatformWebLoginState.Error).canRetry)
        assertFalse(coordinator.isValidating.get())
    }

    @Test
    fun onCancelOrExit_whileValidating_discardsPendingValidation() = runTest(testDispatcher) {
        val scrubber = FakeCookieScrubber()
        val coordinator = PlatformWebLoginCoordinator(
            config = PlatformWebLoginCatalog.INSTAGRAM,
            cookieScrubber = scrubber,
            importSessionAction = {
                delay(100)
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.CONFIGURED, 1))
            },
            validateSessionAction = {
                Result.success(PlatformSessionInfo(Platform.INSTAGRAM, SessionState.ACTIVE, 1))
            },
            cookieProbe = { "sessionid=valid_sess" },
            scope = this
        )

        var successCalled = false
        coordinator.onPageFinishedOrHistory("https://www.instagram.com/") {
            successCalled = true
        }

        assertTrue(coordinator.isValidating.get())
        coordinator.onCancelOrExit { }

        advanceUntilIdle()
        assertFalse("Success must not be triggered after cancel/exit", successCalled)
        assertFalse(coordinator.state.value is PlatformWebLoginState.Active)
    }
}
