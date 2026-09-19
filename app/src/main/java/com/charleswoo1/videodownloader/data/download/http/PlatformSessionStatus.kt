package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform

/**
 * State of a platform authentication session.
 */
enum class SessionState {
    /**
     * No credentials or cookies configured for this platform.
     */
    NOT_CONFIGURED,

    /**
     * Active authenticated session present and validated.
     */
    ACTIVE,

    /**
     * Configured session credentials rejected by platform or expired.
     */
    EXPIRED
}

/**
 * Public summary of platform session status for UI display.
 * Never carries or exposes raw secrets, cookies, or tokens.
 */
data class PlatformSessionInfo(
    val platform: Platform,
    val state: SessionState,
    val cookieCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val details: String? = null
)
