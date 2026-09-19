package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.IOException

/**
 * Production implementation of [PlatformSessionProvider].
 *
 * Coordinates hardware-encrypted credential persistence, domain-isolated parsing,
 * session state tracking, and platform validation.
 */
class AuthenticatedPlatformSessionProvider(
    private val credentialStore: PlatformCredentialStore = InMemoryPlatformCredentialStore()
) : PlatformSessionProvider {

    private val _statusFlows = mapOf(
        Platform.INSTAGRAM to MutableStateFlow(credentialStore.getStatus(Platform.INSTAGRAM)),
        Platform.THREADS to MutableStateFlow(credentialStore.getStatus(Platform.THREADS)),
        Platform.X to MutableStateFlow(credentialStore.getStatus(Platform.X))
    )

    override fun cookiesFor(platform: Platform): List<Cookie> {
        val status = credentialStore.getStatus(platform)
        if (status.state != SessionState.ACTIVE && status.state != SessionState.CONFIGURED) {
            return emptyList()
        }
        return credentialStore.getCookies(platform)
    }

    override fun hasAuthenticatedSession(platform: Platform): Boolean {
        val status = credentialStore.getStatus(platform)
        return (status.state == SessionState.ACTIVE || status.state == SessionState.CONFIGURED) && credentialStore.getCookies(platform).isNotEmpty()
    }

    fun sessionStatus(platform: Platform): PlatformSessionInfo {
        return credentialStore.getStatus(platform)
    }

    fun statusFlow(platform: Platform): StateFlow<PlatformSessionInfo>? {
        return _statusFlows[platform]?.asStateFlow()
    }

    /**
     * Imports session cookies from user-supplied raw text (header, Netscape, or JSON).
     * Enforces strict domain isolation and saves encrypted credentials with initial CONFIGURED state.
     */
    fun importSession(platform: Platform, rawInput: String): Result<PlatformSessionInfo> {
        return try {
            val cookies = PlatformCookieParser.parse(rawInput, platform)
            if (cookies.isEmpty()) {
                return Result.failure(IllegalArgumentException("未能解析出任何屬於 ${platform.displayName} 的有效 Cookie"))
            }

            // Platform-specific mandatory cookie presence checks
            when (platform) {
                Platform.INSTAGRAM, Platform.THREADS -> {
                    val hasSessionId = cookies.any { it.name.equals("sessionid", ignoreCase = true) }
                    if (!hasSessionId) {
                        return Result.failure(IllegalArgumentException("匯入的 Cookie 中缺少必要的 'sessionid'"))
                    }
                }
                Platform.X -> {
                    val hasAuthToken = cookies.any { it.name.equals("auth_token", ignoreCase = true) }
                    if (!hasAuthToken) {
                        return Result.failure(IllegalArgumentException("匯入的 Cookie 中缺少必要的 'auth_token'"))
                    }
                }
                else -> {}
            }

            credentialStore.saveCookies(platform, cookies)
            val info = credentialStore.getStatus(platform)
            _statusFlows[platform]?.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imports Meta session cookies simultaneously for both Instagram and Threads,
     * maintaining strict per-platform domain isolation for each and setting both to CONFIGURED.
     */
    fun importMetaSession(rawInput: String): Result<Pair<PlatformSessionInfo, PlatformSessionInfo>> {
        val igResult = importSession(Platform.INSTAGRAM, rawInput)
        if (igResult.isFailure) return Result.failure(igResult.exceptionOrNull()!!)

        val thResult = importSession(Platform.THREADS, rawInput)
        if (thResult.isFailure) return Result.failure(thResult.exceptionOrNull()!!)

        return Result.success(Pair(igResult.getOrThrow(), thResult.getOrThrow()))
    }

    /**
     * Clears stored credentials and resets status to NOT_CONFIGURED.
     */
    fun clearSession(platform: Platform) {
        credentialStore.clearCookies(platform)
        val info = credentialStore.getStatus(platform)
        _statusFlows[platform]?.value = info
    }

    /**
     * Marks the session as expired when platform responds with 401/403 or login redirect.
     */
    override fun markExpired(platform: Platform, reason: String?) {
        credentialStore.updateStatus(platform, SessionState.EXPIRED, reason)
        val info = credentialStore.getStatus(platform)
        _statusFlows[platform]?.value = info
    }

    /**
     * Marks the session as active when verified.
     */
    fun markActive(platform: Platform, details: String? = null) {
        credentialStore.updateStatus(platform, SessionState.ACTIVE, details)
        val info = credentialStore.getStatus(platform)
        _statusFlows[platform]?.value = info
    }

    /**
     * Validates stored platform session against the live platform API or structure.
     * Transitions state to ACTIVE on success, EXPIRED on auth rejection,
     * or retains existing state with error details on transient/network failure.
     */
    suspend fun validateSession(platform: Platform, httpSession: PlatformHttpSession): Result<PlatformSessionInfo> = withContext(Dispatchers.IO) {
        val cookies = credentialStore.getCookies(platform)
        if (cookies.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("尚未設定 ${platform.displayName} 的 Session"))
        }

        try {
            when (platform) {
                Platform.INSTAGRAM -> {
                    val sessionid = cookies.firstOrNull { it.name.equals("sessionid", ignoreCase = true) }?.value
                    if (sessionid.isNullOrBlank()) {
                        markExpired(platform, "缺少 sessionid")
                        return@withContext Result.failure(IllegalStateException("缺少 sessionid"))
                    }

                    val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    val respResult = httpSession.fetch(
                        url = "https://i.instagram.com/api/v1/accounts/current_user/?edit=true",
                        profile = RequestProfile.API,
                        customHeaders = mapOf(
                            "X-IG-App-ID" to "936619743392459",
                            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
                            "Cookie" to cookieHeader
                        )
                    )

                    val resp = respResult.getOrNull()
                    if (resp != null) {
                        val body = resp.body
                        val isLoginRedirect = resp.code == 302 || resp.finalUrl.contains("/accounts/login") || body.contains("checkpoint_required") || body.contains("login_required")
                        if (resp.code == 200 && (body.contains("\"status\": \"ok\"") || body.contains("\"status\":\"ok\"") || body.contains("\"user\""))) {
                            markActive(platform, "驗證成功 (已連線)")
                        } else if (resp.code in listOf(401, 403) || isLoginRedirect) {
                            markExpired(platform, "登入狀態已失效 (HTTP ${resp.code})")
                        } else {
                            credentialStore.updateStatus(platform, sessionStatus(platform).state, "伺服器回應狀態異常 (HTTP ${resp.code})")
                            _statusFlows[platform]?.value = sessionStatus(platform)
                        }
                    } else {
                        val err = respResult.exceptionOrNull()
                        val msg = err?.message ?: "連線逾時"
                        credentialStore.updateStatus(platform, sessionStatus(platform).state, "網路連線失敗 ($msg)，保留待驗證")
                        _statusFlows[platform]?.value = sessionStatus(platform)
                    }
                    Result.success(sessionStatus(platform))
                }
                Platform.X -> {
                    val authToken = cookies.firstOrNull { it.name.equals("auth_token", ignoreCase = true) }?.value
                    val ct0 = cookies.firstOrNull { it.name.equals("ct0", ignoreCase = true) }?.value ?: "00000000000000000000000000000000"
                    if (authToken.isNullOrBlank()) {
                        markExpired(platform, "缺少 auth_token")
                        return@withContext Result.failure(IllegalStateException("缺少 auth_token"))
                    }

                    val respResult = httpSession.fetch(
                        url = "https://api.x.com/1.1/account/settings.json",
                        profile = RequestProfile.API,
                        customHeaders = mapOf(
                            "Authorization" to "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA",
                            "x-csrf-token" to ct0,
                            "x-twitter-auth-type" to "OAuth2Session",
                            "Cookie" to "auth_token=$authToken; ct0=$ct0"
                        )
                    )

                    val resp = respResult.getOrNull()
                    if (resp != null) {
                        if (resp.code == 200) {
                            markActive(platform, "驗證成功 (已連線)")
                        } else if (resp.code in listOf(401, 403)) {
                            markExpired(platform, "登入狀態已失效 (HTTP ${resp.code})")
                        } else {
                            credentialStore.updateStatus(platform, sessionStatus(platform).state, "伺服器回應狀態異常 (HTTP ${resp.code})")
                            _statusFlows[platform]?.value = sessionStatus(platform)
                        }
                    } else {
                        val err = respResult.exceptionOrNull()
                        val msg = err?.message ?: "連線逾時"
                        credentialStore.updateStatus(platform, sessionStatus(platform).state, "網路連線失敗 ($msg)，保留待驗證")
                        _statusFlows[platform]?.value = sessionStatus(platform)
                    }
                    Result.success(sessionStatus(platform))
                }
                Platform.THREADS -> {
                    val sessionid = cookies.firstOrNull { it.name.equals("sessionid", ignoreCase = true) }?.value
                    if (sessionid.isNullOrBlank()) {
                        markExpired(platform, "缺少 sessionid")
                        return@withContext Result.failure(IllegalStateException("缺少 sessionid"))
                    }

                    val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    val respResult = httpSession.fetch(
                        url = "https://www.threads.net/",
                        profile = RequestProfile.DESKTOP_NAVIGATION,
                        customHeaders = mapOf(
                            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
                            "Cookie" to cookieHeader
                        )
                    )

                    val resp = respResult.getOrNull()
                    if (resp != null) {
                        val isRejected = resp.code in listOf(401, 403) ||
                                resp.code == 302 ||
                                resp.finalUrl.contains("/login") ||
                                resp.headers.entries.any { it.key.equals("Set-Cookie", ignoreCase = true) && it.value.contains("sessionid=deleted") }

                        if (isRejected) {
                            markExpired(platform, "登入狀態已失效 (HTTP ${resp.code})")
                        } else if (resp.code == 200) {
                            markActive(platform, "驗證成功 (已連線)")
                        } else {
                            credentialStore.updateStatus(platform, sessionStatus(platform).state, "伺服器回應狀態異常 (HTTP ${resp.code})")
                            _statusFlows[platform]?.value = sessionStatus(platform)
                        }
                    } else {
                        val err = respResult.exceptionOrNull()
                        val msg = err?.message ?: "連線逾時"
                        credentialStore.updateStatus(platform, sessionStatus(platform).state, "網路連線失敗 ($msg)，保留待驗證")
                        _statusFlows[platform]?.value = sessionStatus(platform)
                    }
                    Result.success(sessionStatus(platform))
                }
                else -> Result.failure(UnsupportedOperationException("${platform.displayName} 不支援 Session 驗證"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
