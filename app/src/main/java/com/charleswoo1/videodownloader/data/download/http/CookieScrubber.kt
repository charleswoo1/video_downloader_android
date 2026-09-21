package com.charleswoo1.videodownloader.data.download.http

import android.webkit.CookieManager

/**
 * Contract for completion-safe cookie removal and flushing.
 */
fun interface CookieScrubber {
    /**
     * Clears cookies asynchronously and flushes the cookie store upon removal completion.
     * Invokes [onComplete] with true if successful.
     */
    fun scrubCookies(onComplete: (Boolean) -> Unit)
}

/**
 * Production Android CookieManager implementation.
 * Ensures flush() is called strictly inside the removeAllCookies completion callback
 * before notifying caller.
 */
class AndroidCookieScrubber(
    private val cookieManager: CookieManager = CookieManager.getInstance()
) : CookieScrubber {
    override fun scrubCookies(onComplete: (Boolean) -> Unit) {
        cookieManager.removeAllCookies { result ->
            cookieManager.flush()
            onComplete(result)
        }
    }
}
