package com.charleswoo1.videodownloader.ui.preferences

import android.content.Context
import android.content.SharedPreferences

class UiPreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    var showDebugUi: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEBUG_UI, DEFAULT_SHOW_DEBUG_UI)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_DEBUG_UI, value).apply()
        }

    companion object {
        const val PREFS_NAME = "ui_preferences"
        const val KEY_SHOW_DEBUG_UI = "show_debug_ui"
        const val DEFAULT_SHOW_DEBUG_UI = false
    }
}
