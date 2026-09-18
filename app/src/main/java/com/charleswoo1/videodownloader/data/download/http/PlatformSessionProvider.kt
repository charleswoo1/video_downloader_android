package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import okhttp3.Cookie

/**
 * Future authentication boundary interface.
 * In Platform Native V2, only anonymous guest sessions are supported.
 */
interface PlatformSessionProvider {
    fun cookiesFor(platform: Platform): List<Cookie>
    fun hasAuthenticatedSession(platform: Platform): Boolean
}

/**
 * Default anonymous-only session provider for V2.
 */
class AnonymousSessionProvider : PlatformSessionProvider {
    override fun cookiesFor(platform: Platform): List<Cookie> = emptyList()
    override fun hasAuthenticatedSession(platform: Platform): Boolean = false
}
