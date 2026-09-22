package com.charleswoo1.videodownloader.data.download.http

import android.util.Log
import com.charleswoo1.videodownloader.domain.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import org.json.JSONObject
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
        if (status.state != SessionState.ACTIVE) {
            return emptyList()
        }
        return credentialStore.getCookies(platform)
    }

    override fun hasAuthenticatedSession(platform: Platform): Boolean {
        val status = credentialStore.getStatus(platform)
        return status.state == SessionState.ACTIVE && credentialStore.getCookies(platform).isNotEmpty()
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

            validateMandatoryCookies(platform, cookies)

            credentialStore.saveCookies(platform, cookies)
            val info = credentialStore.getStatus(platform)
            _statusFlows[platform]?.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imports session cookies captured directly from on-device WebView CookieManager.
     * Enforces domain isolation and mandatory cookie checks, saving encrypted credentials
     * with CONFIGURED state.
     */
    fun importCapturedSession(platform: Platform, rawCookieHeader: String): Result<PlatformSessionInfo> {
        return try {
            val cookies = PlatformCookieParser.parse(rawCookieHeader, platform)
            if (cookies.isEmpty()) {
                return Result.failure(IllegalArgumentException("未能解析出任何屬於 ${platform.displayName} 的有效 Cookie"))
            }

            validateMandatoryCookies(platform, cookies)

            credentialStore.saveCookies(platform, cookies)
            val info = credentialStore.getStatus(platform)
            _statusFlows[platform]?.value = info
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validateMandatoryCookies(platform: Platform, cookies: List<Cookie>) {
        when (platform) {
            Platform.INSTAGRAM, Platform.THREADS -> {
                val hasSessionId = cookies.any { it.name.equals("sessionid", ignoreCase = true) }
                if (!hasSessionId) {
                    throw IllegalArgumentException("匯入的 Cookie 中缺少必要的 'sessionid'")
                }
            }
            Platform.X -> {
                val hasAuthToken = cookies.any { it.name.equals("auth_token", ignoreCase = true) }
                val hasCt0 = cookies.any { it.name.equals("ct0", ignoreCase = true) }
                if (!hasAuthToken || !hasCt0) {
                    throw IllegalArgumentException("匯入的 X Cookie 中必須同時包含 'auth_token' 與 'ct0'")
                }
            }
            else -> {}
        }
    }

    /**
     * Imports Meta session cookies simultaneously for both Instagram and Threads,
     * requiring explicit domain exports (Netscape/JSON) and maintaining atomic all-or-nothing storage.
     */
    fun importMetaSession(rawInput: String): Result<Pair<PlatformSessionInfo, PlatformSessionInfo>> {
        val trimmed = rawInput.trim()
        val isExplicitDomainFormat = (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                trimmed.lines().any { it.contains("\t") }
        if (!isExplicitDomainFormat) {
            return Result.failure(IllegalArgumentException("Meta 綜合匯入僅支援具明確網域標籤之 Netscape 或 JSON 匯出格式，不得使用無網域 Header 字串"))
        }

        val igCookies = try {
            PlatformCookieParser.parse(trimmed, Platform.INSTAGRAM)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Meta 匯入失敗：缺少 Instagram 有效 Cookie (${e.message})", e))
        }
        val thCookies = try {
            PlatformCookieParser.parse(trimmed, Platform.THREADS)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Meta 匯入失敗：缺少 Threads 有效 Cookie (${e.message})", e))
        }

        if (!igCookies.any { it.name.equals("sessionid", ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Meta 匯入失敗：Instagram Cookie 中缺少 'sessionid'"))
        }
        if (!thCookies.any { it.name.equals("sessionid", ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("Meta 匯入失敗：Threads Cookie 中缺少 'sessionid'"))
        }

        // Atomically save both only after both are verified
        credentialStore.saveCookies(Platform.INSTAGRAM, igCookies)
        credentialStore.saveCookies(Platform.THREADS, thCookies)

        val igInfo = credentialStore.getStatus(Platform.INSTAGRAM)
        val thInfo = credentialStore.getStatus(Platform.THREADS)
        _statusFlows[Platform.INSTAGRAM]?.value = igInfo
        _statusFlows[Platform.THREADS]?.value = thInfo

        return Result.success(Pair(igInfo, thInfo))
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

                    val previousState = sessionStatus(platform).state

                    val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    val respResult = httpSession.fetch(
                        url = "https://www.instagram.com/api/v1/accounts/edit/web_form_data/",
                        profile = RequestProfile.API,
                        identity = BrowserIdentity.DESKTOP,
                        origin = "https://www.instagram.com",
                        referer = "https://www.instagram.com/accounts/edit/",
                        customHeaders = mapOf(
                            "X-IG-App-ID" to "936619743392459",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json",
                            "Referer" to "https://www.instagram.com/accounts/edit/",
                            "Cookie" to cookieHeader
                        )
                    )

                    val dsUserIdCookie = cookies.firstOrNull { it.name.equals("ds_user_id", ignoreCase = true) }?.value?.trim()
                    val hasCredibleDsUserId = isCredibleNumericId(dsUserIdCookie)

                    val resp = respResult.getOrNull()
                    if (resp != null) {
                        val code = resp.code
                        val body = resp.body
                        val contentType = resp.getHeader("Content-Type") ?: resp.getHeader("content-type") ?: ""
                        val isJson = contentType.contains("application/json", ignoreCase = true)
                        val finalUrl = resp.finalUrl
                        val json = try { JSONObject(body) } catch (_: Exception) { null }

                        val jsonMessage = json?.optString("message").orEmpty()
                        val jsonStatus = json?.optString("status").orEmpty()

                        // 1. Challenge / checkpoint classification (precedence over generic auth rejection)
                        val hasExplicitChallenge = body.contains("checkpoint_required", ignoreCase = true) ||
                                body.contains("challenge_required", ignoreCase = true) ||
                                jsonMessage.contains("checkpoint_required", ignoreCase = true) ||
                                jsonMessage.contains("challenge_required", ignoreCase = true) ||
                                jsonStatus.contains("checkpoint_required", ignoreCase = true) ||
                                jsonStatus.contains("challenge_required", ignoreCase = true)

                        if (hasExplicitChallenge) {
                            val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=false username_present=false form_user_id_present=false ds_user_id_present=$hasCredibleDsUserId classification=CONFIGURED"
                            Log.d(TAG, diag)
                            credentialStore.updateStatus(platform, SessionState.CONFIGURED, "需要完成 Instagram 安全驗證")
                            _statusFlows[platform]?.value = sessionStatus(platform)
                        } else {
                            // 2. Explicit auth rejection (when NOT a checkpoint/challenge)
                            val isLoginRedirect = code == 302 || finalUrl.contains("/accounts/login")
                            val hasExplicitLoginRequired = body.contains("login_required", ignoreCase = true) ||
                                    jsonMessage.contains("login_required", ignoreCase = true) ||
                                    jsonStatus.contains("login_required", ignoreCase = true)
                            val hasExplicitLoggedOut = body.contains("user_has_logged_out", ignoreCase = true) ||
                                    jsonMessage.contains("user_has_logged_out", ignoreCase = true)

                            val isExplicitAuthRejection = code == 401 ||
                                    (code == 403 && (hasExplicitLoginRequired || hasExplicitLoggedOut || isLoginRedirect)) ||
                                    isLoginRedirect ||
                                    hasExplicitLoginRequired ||
                                    hasExplicitLoggedOut

                            if (isExplicitAuthRejection) {
                                val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=false username_present=false form_user_id_present=false ds_user_id_present=$hasCredibleDsUserId classification=EXPIRED"
                                Log.d(TAG, diag)
                                markExpired(platform, "登入狀態已失效 (HTTP $code)")
                            } else if (code == 200) {
                                val formDataObj = json?.optJSONObject("form_data")
                                val formDataPresent = formDataObj != null
                                val username = formDataObj?.optString("username")?.trim()?.ifBlank { null }
                                val usernamePresent = !username.isNullOrBlank()

                                val formUserId = extractUserId(formDataObj, "user_id")
                                    ?: extractUserId(formDataObj, "username_id")
                                    ?: extractUserId(formDataObj, "pk")
                                    ?: extractUserId(formDataObj, "id")
                                val hasCredibleFormUserId = isCredibleNumericId(formUserId)

                                val resolvedUserId = if (hasCredibleFormUserId) formUserId else if (hasCredibleDsUserId) dsUserIdCookie else null
                                val hasActiveProof = formDataPresent && usernamePresent && resolvedUserId != null

                                if (hasActiveProof) {
                                    val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=$formDataPresent username_present=$usernamePresent form_user_id_present=$hasCredibleFormUserId ds_user_id_present=$hasCredibleDsUserId classification=ACTIVE"
                                    Log.d(TAG, diag)
                                    markActive(platform, "驗證成功 (已連線)")
                                } else if (json == null) {
                                    // Malformed JSON is treated as transient failure
                                    val retainedState = retainTransientState(previousState)
                                    val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=false username_present=false form_user_id_present=false ds_user_id_present=$hasCredibleDsUserId classification=$retainedState"
                                    Log.d(TAG, diag)
                                    credentialStore.updateStatus(platform, retainedState, "伺服器回應格式異常，保留目前狀態")
                                    _statusFlows[platform]?.value = sessionStatus(platform)
                                } else {
                                    // Valid JSON but insufficient identity proof -> Keep CONFIGURED (do not claim ACTIVE)
                                    val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=$formDataPresent username_present=$usernamePresent form_user_id_present=$hasCredibleFormUserId ds_user_id_present=$hasCredibleDsUserId classification=CONFIGURED"
                                    Log.d(TAG, diag)
                                    credentialStore.updateStatus(platform, SessionState.CONFIGURED, "未檢測到有效帳號身分資料，保留待驗證")
                                    _statusFlows[platform]?.value = sessionStatus(platform)
                                }
                            } else {
                                // Transient server errors (429, 5xx, or unexpected non-auth HTTP codes)
                                val retainedState = retainTransientState(previousState)
                                val diag = "validator=instagram_web_form_data http_status=$code content_type_json=$isJson form_data_present=false username_present=false form_user_id_present=false ds_user_id_present=$hasCredibleDsUserId classification=$retainedState"
                                Log.d(TAG, diag)
                                val errorDetail = if (code == 429) {
                                    "請求過於頻繁 (HTTP 429)，保留目前狀態"
                                } else {
                                    "伺服器暫時無法驗證 (HTTP $code)，保留目前狀態"
                                }
                                credentialStore.updateStatus(platform, retainedState, errorDetail)
                                _statusFlows[platform]?.value = sessionStatus(platform)
                            }
                        }
                    } else {
                        // Network failure / timeout
                        val retainedState = retainTransientState(previousState)
                        val diag = "validator=instagram_web_form_data http_status=0 content_type_json=false form_data_present=false username_present=false form_user_id_present=false ds_user_id_present=$hasCredibleDsUserId classification=$retainedState"
                        Log.d(TAG, diag)
                        val err = respResult.exceptionOrNull()
                        val msg = err?.message ?: "連線逾時"
                        credentialStore.updateStatus(platform, retainedState, "網路連線失敗 ($msg)，保留目前狀態")
                        _statusFlows[platform]?.value = sessionStatus(platform)
                    }
                    Result.success(sessionStatus(platform))
                }
                Platform.X -> {
                    val authToken = cookies.firstOrNull { it.name.equals("auth_token", ignoreCase = true) }?.value
                    val ct0 = cookies.firstOrNull { it.name.equals("ct0", ignoreCase = true) }?.value
                    if (authToken.isNullOrBlank() || ct0.isNullOrBlank()) {
                        markExpired(platform, "缺少 auth_token 或 ct0")
                        return@withContext Result.failure(IllegalStateException("缺少 auth_token 或 ct0"))
                    }

                    val cookieHeader = buildString {
                        append("auth_token=").append(authToken)
                        append("; ct0=").append(ct0)
                        for (c in cookies) {
                            if (!c.name.equals("auth_token", ignoreCase = true) && !c.name.equals("ct0", ignoreCase = true)) {
                                append("; ").append(c.name).append("=").append(c.value)
                            }
                        }
                    }

                    val headers = mapOf(
                        "Authorization" to "Bearer $X_BEARER_TOKEN",
                        "x-csrf-token" to ct0,
                        "x-twitter-auth-type" to "OAuth2Session",
                        "x-twitter-active-user" to "yes",
                        "x-twitter-client-language" to "zh-tw",
                        "Cookie" to cookieHeader
                    )

                    val previousState = sessionStatus(platform).state
                    val attempts = mutableListOf<String>()

                    for (endpoint in X_VALIDATOR_ENDPOINTS) {
                        val respResult = httpSession.fetch(
                            url = endpoint.url,
                            profile = RequestProfile.API,
                            origin = "https://x.com",
                            referer = "https://x.com/",
                            customHeaders = headers
                        )

                        val resp = respResult.getOrNull()
                        if (resp == null) {
                            attempts.add("${endpoint.name}:0:network_failure")
                            continue
                        }

                        val code = resp.code
                        when (code) {
                            200 -> {
                                val identityProof = extractXIdentityProof(resp.body)
                                if (!identityProof.isNullOrBlank()) {
                                    attempts.add("${endpoint.name}:200:identity")
                                    val diag = formatXValidatorDiagnostics(attempts, SessionState.ACTIVE)
                                    Log.d(TAG, diag)
                                    markActive(platform, "驗證成功 (已連線: $identityProof)")
                                    return@withContext Result.success(sessionStatus(platform))
                                } else {
                                    attempts.add("${endpoint.name}:200:insufficient_identity")
                                }
                            }
                            401 -> {
                                attempts.add("${endpoint.name}:401:auth_rejected")
                                val diag = formatXValidatorDiagnostics(attempts, SessionState.EXPIRED)
                                Log.d(TAG, diag)
                                markExpired(platform, "登入狀態已失效 (HTTP 401)")
                                return@withContext Result.success(sessionStatus(platform))
                            }
                            403 -> {
                                val isExplicitAuthRejection = isXExplicitAuthRejection(resp.body)
                                if (isExplicitAuthRejection) {
                                    attempts.add("${endpoint.name}:403:auth_rejected")
                                    val diag = formatXValidatorDiagnostics(attempts, SessionState.EXPIRED)
                                    Log.d(TAG, diag)
                                    markExpired(platform, "登入狀態已失效 (HTTP 403)")
                                    return@withContext Result.success(sessionStatus(platform))
                                } else {
                                    attempts.add("${endpoint.name}:403:ambiguous")
                                }
                            }
                            404 -> {
                                attempts.add("${endpoint.name}:404")
                            }
                            429 -> {
                                attempts.add("${endpoint.name}:429:rate_limited")
                            }
                            in 500..599 -> {
                                attempts.add("${endpoint.name}:$code:server_error")
                            }
                            else -> {
                                attempts.add("${endpoint.name}:$code:unexpected")
                            }
                        }
                    }

                    val finalState = previousState
                    val diag = formatXValidatorDiagnostics(attempts, finalState)
                    Log.d(TAG, diag)

                    val finalDetail = when {
                        attempts.all { it.endsWith(":404") } ->
                            if (finalState == SessionState.CONFIGURED) "驗證端點皆無法使用 (HTTP 404)，保留待驗證" else "驗證端點皆無法使用 (HTTP 404)，保留目前狀態"
                        attempts.all { it.contains(":0:network_failure") } ->
                            "網路連線失敗，保留目前狀態"
                        attempts.any { it.contains(":429:rate_limited") } ->
                            "請求過於頻繁 (HTTP 429)，保留目前狀態"
                        attempts.all { it.contains(":server_error") } -> {
                            val first5xx = attempts.firstOrNull { it.contains(":server_error") }?.split(":")?.getOrNull(1) ?: "5xx"
                            "伺服器暫時無法驗證 (HTTP $first5xx)，保留目前狀態"
                        }
                        attempts.all { it.contains(":200:insufficient_identity") || it.endsWith(":404") } && attempts.any { it.contains(":200:insufficient_identity") } ->
                            if (finalState == SessionState.CONFIGURED) "未檢測到有效帳號標記，保留待驗證" else "未檢測到有效帳號標記，保留目前狀態"
                        attempts.any { it.contains(":403:ambiguous") } ->
                            if (finalState == SessionState.CONFIGURED) "需要安全驗證或受到限制 (HTTP 403)，保留待驗證" else "需要安全驗證或受到限制 (HTTP 403)，保留目前狀態"
                        else ->
                            if (finalState == SessionState.CONFIGURED) "無法完成 X 登入驗證，已嘗試 ${attempts.size} 個驗證方式，保留待驗證" else "無法完成 X 登入驗證，已嘗試 ${attempts.size} 個驗證方式，保留目前狀態"
                    }

                    credentialStore.updateStatus(platform, finalState, finalDetail)
                    _statusFlows[platform]?.value = sessionStatus(platform)
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
                        url = "https://www.threads.com/",
                        profile = RequestProfile.DESKTOP_NAVIGATION,
                        origin = "https://www.threads.com",
                        referer = "https://www.threads.com/",
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
                            val body = resp.body
                            val dtsgRegex = Regex("""\["DTSGInitialData",\[\],\{"token":"([^"]+)"""")
                            val dtsgHtmlRegex = Regex("""name="fb_dtsg" value="([^"]+)"""")
                            val dtsgJsonRegex = Regex(""""fb_dtsg":"([^"]+)"""")
                            val dtsgToken = dtsgRegex.find(body)?.groupValues?.get(1)
                                ?: dtsgHtmlRegex.find(body)?.groupValues?.get(1)
                                ?: dtsgJsonRegex.find(body)?.groupValues?.get(1)

                            val userIdRegex = Regex(""""(?:ACCOUNT_ID|USER_ID|IG_USER_EIMU)"\s*:\s*"(\d{3,})"""")
                            val pageUserId = userIdRegex.find(body)?.groupValues?.get(1)
                            val dsUserIdCookie = cookies.firstOrNull { it.name.equals("ds_user_id", ignoreCase = true) }?.value?.trim()?.takeIf {
                                it.isNotBlank() && it.all { ch -> ch.isDigit() } && it.length >= 3 && (it.toLongOrNull() ?: 0L) > 0L
                            }
                            val userId = pageUserId ?: dsUserIdCookie

                            val hasValidProof = !dtsgToken.isNullOrBlank() && !userId.isNullOrBlank()

                            if (hasValidProof) {
                                markActive(platform, "驗證成功 (已連線)")
                            } else {
                                credentialStore.updateStatus(platform, SessionState.CONFIGURED, "未檢測到有效登入憑證與帳號標記，請確認 Cookie 是否有效")
                                _statusFlows[platform]?.value = sessionStatus(platform)
                            }
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

    companion object {
        private const val TAG = "AuthPlatformSession"

        private fun extractUserId(obj: JSONObject?, key: String): String? {
            if (obj == null || !obj.has(key)) return null
            val str = obj.optString(key, "").trim()
            if (str.isNotBlank() && str != "null") {
                return str
            }
            val num = obj.optLong(key, 0L)
            if (num > 0L) {
                return num.toString()
            }
            return null
        }

        private fun isCredibleNumericId(id: String?): Boolean {
            return !id.isNullOrBlank() && id.all { it.isDigit() } && (id.toLongOrNull() ?: 0L) > 0L
        }

        private fun retainTransientState(previousState: SessionState): SessionState {
            return if (previousState == SessionState.ACTIVE) SessionState.ACTIVE else SessionState.CONFIGURED
        }

        internal fun formatXValidatorDiagnostics(
            attempts: List<String>,
            classification: SessionState
        ): String {
            return "validator=x attempts=${attempts.joinToString(",")} classification=$classification"
        }

        data class XValidatorEndpoint(
            val name: String,
            val url: String
        )

        val X_VALIDATOR_ENDPOINTS = listOf(
            XValidatorEndpoint("verify_credentials", "https://api.x.com/1.1/account/verify_credentials.json"),
            XValidatorEndpoint("account_settings", "https://x.com/i/api/1.1/account/settings.json"),
            XValidatorEndpoint("legacy_account_settings", "https://api.x.com/1.1/account/settings.json")
        )

        const val X_BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        private fun extractXIdentityProof(body: String): String? {
            return runCatching {
                val json = JSONObject(body)
                val rootScreenName = json.optString("screen_name", "").trim().takeIf { it.isNotBlank() && it != "null" }
                val userObj = json.optJSONObject("user")
                val userScreenName = userObj?.optString("screen_name", "")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                val screenName = rootScreenName ?: userScreenName

                val rootIdStr = json.optString("id_str", "").trim().takeIf { it.isNotBlank() && it != "null" && it.all { ch -> ch.isDigit() } }
                val userIdStr = userObj?.optString("id_str", "")?.trim()?.takeIf { it.isNotBlank() && it != "null" && it.all { ch -> ch.isDigit() } }
                val rootIdNum = if (json.has("id")) json.optLong("id", 0L).takeIf { it > 0L }?.toString() else null
                val userId = rootIdStr ?: userIdStr ?: rootIdNum

                when {
                    !screenName.isNullOrBlank() && !userId.isNullOrBlank() -> "@$screenName ($userId)"
                    !screenName.isNullOrBlank() -> "@$screenName"
                    !userId.isNullOrBlank() -> "User ID: $userId"
                    else -> null
                }
            }.getOrNull()
        }

        private fun isXExplicitAuthRejection(body: String): Boolean {
            return runCatching {
                val json = JSONObject(body)
                val errors = json.optJSONArray("errors")
                if (errors != null) {
                    for (i in 0 until errors.length()) {
                        val err = errors.optJSONObject(i) ?: continue
                        val code = err.optInt("code", 0)
                        val msg = err.optString("message", "")
                        if (code in listOf(32, 89, 99) ||
                            msg.contains("Could not authenticate", ignoreCase = true) ||
                            msg.contains("Invalid or expired token", ignoreCase = true) ||
                            msg.contains("token_expired", ignoreCase = true)
                        ) {
                            return true
                        }
                    }
                }
                body.contains("Could not authenticate", ignoreCase = true) ||
                        body.contains("Invalid or expired token", ignoreCase = true) ||
                        body.contains("login_required", ignoreCase = true)
            }.getOrDefault(false)
        }
    }
}
