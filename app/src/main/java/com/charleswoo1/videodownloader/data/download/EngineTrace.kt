package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.Platform
import java.util.UUID

/**
 * Operation-scoped execution trace recording which engine processed a request,
 * what request profile escalation was attempted, and whether fallback occurred.
 */
data class EngineTrace(
    val operationId: String = UUID.randomUUID().toString(),
    val platform: Platform,
    val primaryEngine: String,
    val primaryResultCategory: String,
    val requestProfileSequence: List<String> = emptyList(),
    val fallbackAttempted: Boolean = false,
    val fallbackEngine: String? = null,
    val fallbackResultCategory: String? = null,
    val finalEngine: String,
    val finalResult: String,
    val timestamp: Long = System.currentTimeMillis(),
    val diagnosticFingerprint: String? = null
) {
    /**
     * Compact display string for Debug UI badges and user-facing diagnostics.
     * Guaranteed not to leak tokens, cookies, or internal file paths.
     */
    fun toDisplaySummary(): String {
        val profileFlow = if (requestProfileSequence.isNotEmpty()) {
            " (${requestProfileSequence.joinToString("→")})"
        } else ""

        return if (fallbackAttempted) {
            "Engine: $finalEngine · $primaryEngine: $primaryResultCategory"
        } else {
            "Engine: $finalEngine$profileFlow · fallback: no"
        }
    }
}
