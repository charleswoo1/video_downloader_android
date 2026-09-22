package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform

/**
 * Extension point for optional post-login actions (e.g. future side-probes).
 * In Phase 1, this is a no-op / null to keep Instagram validation isolated.
 */
fun interface PostLoginProbe {
    suspend fun probe(): Result<Unit>
}

/**
 * Declarative configuration for platform WebView login flows.
 */
data class PlatformWebLoginConfig(
    val platform: Platform,
    val loginUrl: String,
    val cookieProbeUrls: List<String>,
    val requiredCookieNames: Set<String>,
    val optionalCookieNames: Set<String>,
    val allowThirdPartyCookies: Boolean = true,
    val intermediatePatterns: List<String> = emptyList(),
    val postLoginProbe: PostLoginProbe? = null
)

object PlatformWebLoginCatalog {

    val INSTAGRAM = PlatformWebLoginConfig(
        platform = Platform.INSTAGRAM,
        loginUrl = "https://www.instagram.com/accounts/login/",
        cookieProbeUrls = listOf("https://www.instagram.com/"),
        requiredCookieNames = setOf("sessionid"),
        optionalCookieNames = setOf("csrftoken", "ds_user_id"),
        allowThirdPartyCookies = true,
        intermediatePatterns = listOf(
            "/accounts/login/",
            "/accounts/onetap/",
            "/accounts/password/",
            "/accounts/suspended",
            "/accounts/integrity",
            "/accounts/update_risky",
            "/challenge/",
            "/two_factor",
            "/verify/",
            "security_check"
        ),
        postLoginProbe = null
    )

    val THREADS = PlatformWebLoginConfig(
        platform = Platform.THREADS,
        loginUrl = "https://www.threads.com/login",
        cookieProbeUrls = listOf("https://www.threads.com/"),
        requiredCookieNames = setOf("sessionid"),
        optionalCookieNames = setOf("csrftoken", "ds_user_id"),
        allowThirdPartyCookies = true,
        intermediatePatterns = listOf(
            "/login",
            "/accounts/login/",
            "/accounts/onetap/",
            "/accounts/password/",
            "/challenge/",
            "/two_factor",
            "/verify/",
            "security_check",
            "checkpoint"
        ),
        postLoginProbe = null
    )

    val X = PlatformWebLoginConfig(
        platform = Platform.X,
        loginUrl = "https://x.com/i/flow/login",
        cookieProbeUrls = listOf("https://x.com/"),
        requiredCookieNames = setOf("auth_token", "ct0"),
        optionalCookieNames = setOf("twid", "kdt"),
        allowThirdPartyCookies = true,
        intermediatePatterns = listOf(
            "/i/flow/login",
            "/i/flow/",
            "/login",
            "/account/access",
            "/account/login_verification"
        ),
        postLoginProbe = null
    )

    fun configFor(platform: Platform): PlatformWebLoginConfig? = when (platform) {
        Platform.INSTAGRAM -> INSTAGRAM
        Platform.THREADS -> THREADS
        Platform.X -> X
        else -> null
    }
}
