package com.charleswoo1.videodownloader.ui.auth

import com.charleswoo1.videodownloader.data.download.http.CookieCandidateResult
import com.charleswoo1.videodownloader.data.download.http.CookieScrubber
import com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo
import com.charleswoo1.videodownloader.data.download.http.PlatformWebLoginConfig
import com.charleswoo1.videodownloader.data.download.http.SessionState
import com.charleswoo1.videodownloader.data.download.http.WebViewCookieCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates WebView login lifecycle, cookie extraction, session validation,
 * and completion-safe cookie scrubbing without holding a WebView reference.
 */
class PlatformWebLoginCoordinator(
    val config: PlatformWebLoginConfig,
    private val cookieScrubber: CookieScrubber,
    private val importSessionAction: suspend (String) -> Result<PlatformSessionInfo>,
    private val validateSessionAction: suspend () -> Result<PlatformSessionInfo>,
    private val cookieProbe: (String) -> String?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _state = MutableStateFlow<PlatformWebLoginState>(PlatformWebLoginState.Preparing)
    val state: StateFlow<PlatformWebLoginState> = _state.asStateFlow()

    val isValidating = AtomicBoolean(false)

    /**
     * Initializes a fresh login by clearing all stale WebView cookies first,
     * invoking [onReadyToLoad] strictly upon scrubbing completion.
     */
    fun prepareFreshLogin(onReadyToLoad: (String) -> Unit) {
        _state.value = PlatformWebLoginState.Preparing
        cookieScrubber.scrubCookies {
            _state.value = PlatformWebLoginState.LoadingLogin
            onReadyToLoad(config.loginUrl)
        }
    }

    /**
     * Handles navigation start.
     */
    fun onPageStarted(url: String?) {
        if (_state.value !is PlatformWebLoginState.Validating && _state.value !is PlatformWebLoginState.Active) {
            _state.value = PlatformWebLoginState.LoadingLogin
        }
    }

    /**
     * Evaluates UI classification and candidate validation on page finish or history update.
     * Decouples intermediate UI labeling from candidate eligibility: if on a valid platform
     * origin with required cookies, candidate validation proceeds.
     */
    fun onPageFinishedOrHistory(url: String?, onActiveSuccess: suspend () -> Unit) {
        if (isValidating.get()) return
        if (url.isNullOrBlank()) return

        // 1. Classify UI intermediate patterns (e.g. 2FA / challenge page)
        if (WebViewCookieCapture.isIntermediateUrl(url, config)) {
            if (url.contains("/challenge/", ignoreCase = true) ||
                url.contains("/two_factor", ignoreCase = true)
            ) {
                _state.value = PlatformWebLoginState.Challenge("請完成頁面安全驗證")
            } else if (_state.value is PlatformWebLoginState.LoadingLogin) {
                _state.value = PlatformWebLoginState.AwaitingUser
            }
        }

        // 2. Strict origin check: only trigger candidate extraction on configured platform web origins
        if (!WebViewCookieCapture.isCandidateOrigin(url, config.platform)) {
            return
        }

        // 3. Probe cookies from native store
        val probeUrl = config.cookieProbeUrls.firstOrNull() ?: config.loginUrl
        val rawCookies = cookieProbe(probeUrl)
        val candidate = WebViewCookieCapture.extractCandidate(rawCookies, config)

        if (candidate !is CookieCandidateResult.Candidate) {
            if (_state.value is PlatformWebLoginState.LoadingLogin || _state.value is PlatformWebLoginState.Preparing) {
                _state.value = PlatformWebLoginState.AwaitingUser
            }
            return
        }

        // 4. Candidate found: proceed with atomic import and validation
        if (isValidating.compareAndSet(false, true)) {
            _state.value = PlatformWebLoginState.Validating
            scope.launch {
                try {
                    val importRes = importSessionAction(candidate.filteredCookieHeader)
                    if (!isValidating.get()) return@launch
                    if (importRes.isFailure) {
                        isValidating.set(false)
                        _state.value = PlatformWebLoginState.Error(
                            "憑證儲存失敗: ${importRes.exceptionOrNull()?.message}",
                            canRetry = true
                        )
                        return@launch
                    }

                    val valRes = validateSessionAction()
                    if (!isValidating.get()) return@launch
                    val sessionInfo = valRes.getOrNull()

                    when (sessionInfo?.state) {
                        SessionState.ACTIVE -> {
                            _state.value = PlatformWebLoginState.Active()
                            onActiveSuccess()
                        }
                        SessionState.CONFIGURED -> {
                            isValidating.set(false)
                            _state.value = PlatformWebLoginState.Challenge(
                                "已取得 Session，但尚未完成登入驗證，請完成 ${config.platform.displayName} 頁面上的驗證後再試。"
                            )
                        }
                        SessionState.EXPIRED -> {
                            isValidating.set(false)
                            _state.value = PlatformWebLoginState.Error(
                                "${config.platform.displayName} 拒絕此 Session，請重新登入。",
                                canRetry = true
                            )
                        }
                        else -> {
                            isValidating.set(false)
                            val msg = sessionInfo?.details ?: valRes.exceptionOrNull()?.message ?: "網路驗證逾時"
                            _state.value = PlatformWebLoginState.Error(
                                "驗證未完成 ($msg)，可繼續在頁面操作或按重試。",
                                canRetry = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    isValidating.set(false)
                    _state.value = PlatformWebLoginState.Error(
                        "驗證程序發生異常: ${e.message}",
                        canRetry = true
                    )
                }
            }
        }
    }

    /**
     * Cleans up cookies asynchronously and flushes prior to triggering exit callback.
     */
    fun onCancelOrExit(onCleanupDone: () -> Unit) {
        isValidating.set(false)
        cookieScrubber.scrubCookies {
            onCleanupDone()
        }
    }

    /**
     * Handles redirect loops (ERR_TOO_MANY_REDIRECTS).
     */
    fun onRedirectLoopDetected() {
        isValidating.set(false)
        _state.value = PlatformWebLoginState.Error(
            "平台拒絕目前的內嵌登入流程 (重新導向過多)。你仍可使用「手動匯入 Cookie」作為備援。",
            canRetry = false
        )
    }
}
