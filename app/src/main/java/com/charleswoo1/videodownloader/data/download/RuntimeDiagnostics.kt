package com.charleswoo1.videodownloader.data.download

data class RuntimeDiagnostics(
    val youtubedlSource: String = "PR #361 (76ed1bf9) + PR #359 (a505022a)",
    val ytdlpVersion: String = "unknown",
    val pythonVersion: String = "unknown",
    val curlCffiAvailable: Boolean = false,
    val curlCffiVersion: String? = null,
    val ffmpegVersion: String = "unknown",
    val quickJsAvailable: Boolean = false,
    val abi: String = "unknown",
    val threadsPluginAvailable: Boolean = false,
    val threadsPluginCommit: String = YtDlpPluginManager.PINNED_COMMIT,
    val threadsPluginStatus: String = "bundled",
    val details: String? = null
) {
    fun toFormattedReport(): String {
        return buildString {
            appendLine("=== RUNTIME DIAGNOSTICS ===")
            appendLine("youtubedl-android: $youtubedlSource")
            appendLine("yt-dlp version: $ytdlpVersion")
            appendLine("Python version: $pythonVersion")
            appendLine("curl_cffi available: ${if (curlCffiAvailable) "yes ($curlCffiVersion)" else "no"}")
            appendLine("FFmpeg version: $ffmpegVersion")
            appendLine("QuickJS available: ${if (quickJsAvailable) "yes" else "no"}")
            appendLine("ABI: $abi")
            appendLine("Threads plugin: bundled")
            appendLine("Plugin commit: $threadsPluginCommit")
            appendLine("Plugin status: $threadsPluginStatus")
            if (!details.isNullOrBlank()) {
                appendLine("Details: $details")
            }
            append("===========================")
        }
    }
}
