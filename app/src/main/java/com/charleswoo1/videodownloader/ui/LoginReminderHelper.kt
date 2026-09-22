package com.charleswoo1.videodownloader.ui

import com.charleswoo1.videodownloader.data.download.http.SessionState

object LoginReminderHelper {

    /**
     * Determines whether the login reminder banner should be displayed.
     * Displays if any of Instagram, Threads, or X is not ACTIVE.
     * Returns false if all three are ACTIVE.
     */
    fun shouldShowReminder(
        instagramState: SessionState,
        threadsState: SessionState,
        xState: SessionState
    ): Boolean {
        return instagramState != SessionState.ACTIVE ||
                threadsState != SessionState.ACTIVE ||
                xState != SessionState.ACTIVE
    }

    /**
     * Counts the number of platforms that are not ACTIVE.
     */
    fun countInactivePlatforms(
        instagramState: SessionState,
        threadsState: SessionState,
        xState: SessionState
    ): Int {
        var count = 0
        if (instagramState != SessionState.ACTIVE) count++
        if (threadsState != SessionState.ACTIVE) count++
        if (xState != SessionState.ACTIVE) count++
        return count
    }

    /**
     * Returns the reminder message text based on inactive count.
     */
    fun getReminderMessage(inactiveCount: Int): String {
        return if (inactiveCount <= 1) {
            "尚有 1 個平台未連線。登入後可下載受限或年齡限制內容。"
        } else {
            "尚有 $inactiveCount 個平台未連線。登入後可下載受限或年齡限制內容。"
        }
    }

    /**
     * Helper to determine if diagnostic/debug UI elements should be visible.
     */
    fun shouldShowDiagnostics(showDebugUi: Boolean): Boolean {
        return showDebugUi
    }
}
