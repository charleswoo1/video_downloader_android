package com.charleswoo1.videodownloader.ui.auth

/**
 * UI states for the full-screen PlatformWebLoginScreen.
 */
sealed interface PlatformWebLoginState {
    /** Preparing WebView and clearing stale cookies prior to initial navigation. */
    data object Preparing : PlatformWebLoginState

    /** Loading the canonical login page. */
    data object LoadingLogin : PlatformWebLoginState

    /** Visible web page rendered, awaiting user credentials or interaction. */
    data object AwaitingUser : PlatformWebLoginState

    /** Candidate session cookies captured, running validateSession(). */
    data object Validating : PlatformWebLoginState

    /** Session successfully verified as ACTIVE. */
    data class Active(val message: String = "驗證成功 (已連線)") : PlatformWebLoginState

    /** Challenge or 2FA required on the platform page. */
    data class Challenge(val message: String) : PlatformWebLoginState

    /** Error state (e.g. redirect loop or rejection). */
    data class Error(val message: String, val canRetry: Boolean = true) : PlatformWebLoginState
}
