package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.Platform

object YtDlpErrorParser {

    data class ParsedError(
        val userMessage: String,
        val primaryError: String?,
        val warnings: List<String>,
        val category: ErrorCategory
    )

    enum class ErrorCategory {
        PRIVATE_CONTENT,
        CHECKPOINT_REQUIRED,
        LOGIN_REQUIRED,
        RATE_LIMITED,
        UNSUPPORTED_URL,
        DRM_PROTECTED,
        NOT_FOUND,
        NETWORK_ERROR,
        EXTRACTOR_FAILURE,
        GENERAL_ERROR,
        UNKNOWN
    }

    private val REPLACEMENTS = listOf(
        Regex("""(?i)(cookie[s]?|sessionid|csrftoken|auth_token|token|key)=[^&\s]+""") to "$1=[REDACTED]",
        Regex("""(?i)bearer\s+[a-zA-Z0-9_.-]+""") to "Bearer [REDACTED]",
        Regex("""(?i)--cookies?\s+[^\s]+""") to "--cookies [REDACTED]",
        Regex("""(?i)--plugin-dirs\s+[^\s]+""") to "--plugin-dirs [REDACTED]",
        Regex("""/(?:data/user/\d+|data/data)/[a-zA-Z0-9_.-]+[^\s]*""") to "[PRIVATE_PATH]"
    )

    fun sanitize(text: String): String {
        var sanitized = text
        for ((pattern, replacement) in REPLACEMENTS) {
            sanitized = pattern.replace(sanitized, replacement)
        }
        return sanitized.trim()
    }

    fun parse(rawStderr: String?, platform: Platform? = null): ParsedError {
        if (rawStderr.isNullOrBlank()) {
            return ParsedError(
                userMessage = "解析或下載發生未預期的錯誤，請稍候再試",
                primaryError = null,
                warnings = emptyList(),
                category = ErrorCategory.UNKNOWN
            )
        }

        val lines = rawStderr.lines().map { it.trim() }.filter { it.isNotBlank() }
        val warnings = mutableListOf<String>()
        val errorLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()

        for (line in lines) {
            val sanitized = sanitize(line)
            when {
                sanitized.startsWith("WARNING:", ignoreCase = true) ||
                sanitized.startsWith("[warning]", ignoreCase = true) -> {
                    warnings.add(sanitized)
                }
                sanitized.startsWith("ERROR:", ignoreCase = true) ||
                sanitized.startsWith("CRITICAL:", ignoreCase = true) -> {
                    val clean = sanitized.substringAfter(':').trim()
                    errorLines.add(if (clean.isNotBlank()) clean else sanitized)
                }
                else -> {
                    otherLines.add(sanitized)
                }
            }
        }

        // Priority 1: Concrete ERROR: lines
        val primaryError = if (errorLines.isNotEmpty()) {
            errorLines.first()
        } else {
            // Priority 2: Fatal extractor lines or exceptions from otherLines
            otherLines.firstOrNull { isLikelyFatalLine(it) }
        }

        // Priority 3: If no error lines found at all, fallback to warning ONLY if warning is the only explanation
        val targetMessage = primaryError ?: warnings.firstOrNull() ?: otherLines.firstOrNull()

        if (targetMessage == null) {
            return ParsedError(
                userMessage = "未知錯誤或解析失敗，請稍後再試",
                primaryError = null,
                warnings = warnings,
                category = ErrorCategory.UNKNOWN
            )
        }

        val (category, userMessage) = categorize(targetMessage, platform)

        return ParsedError(
            userMessage = userMessage,
            primaryError = primaryError,
            warnings = warnings,
            category = category
        )
    }

    private fun isLikelyFatalLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("error:") ||
                lower.contains("exception:") ||
                lower.contains("unsupported url") ||
                lower.contains("unable to download") ||
                lower.contains("unable to extract") ||
                lower.contains("failed")
    }

    private fun categorize(errorMessage: String, platform: Platform?): Pair<ErrorCategory, String> {
        val msg = errorMessage
        val isInstagram = platform == Platform.INSTAGRAM || msg.contains("instagram", ignoreCase = true)
        val isX = platform == Platform.X || msg.contains("twitter", ignoreCase = true) || msg.contains("x.com", ignoreCase = true)
        val isThreads = platform == Platform.THREADS || msg.contains("threads", ignoreCase = true)

        return when {
            isThreads && (msg.contains("was not found in the page data", ignoreCase = true) ||
                    msg.contains("deleted, private, login-gated", ignoreCase = true)) ->
                Pair(ErrorCategory.PRIVATE_CONTENT, "Threads 貼文不存在、設為私人內容或需要登入帳號驗證")

            isThreads && msg.contains("has no downloadable video", ignoreCase = true) ->
                Pair(ErrorCategory.EXTRACTOR_FAILURE, "此 Threads 貼文未包含可下載的影片內容 (可能為純文字或純圖片)")

            isThreads && msg.contains("No video post found", ignoreCase = true) ->
                Pair(ErrorCategory.EXTRACTOR_FAILURE, "在該 Threads 頁面中找不到有效的影片內容")

            isThreads && msg.contains("carousel post contains no videos", ignoreCase = true) ->
                Pair(ErrorCategory.EXTRACTOR_FAILURE, "此 Threads 輪播貼文未包含任何影片 (不支援純圖片下載)")

            msg.contains("Private video", ignoreCase = true) ||
            msg.contains("This video is private", ignoreCase = true) ->
                Pair(ErrorCategory.PRIVATE_CONTENT, "此影片設為私人內容，無法存取")

            msg.contains("checkpoint_required", ignoreCase = true) ->
                Pair(ErrorCategory.CHECKPOINT_REQUIRED, "Instagram 要求安全驗證 (checkpoint)，無法直接下載")

            msg.contains("login_required", ignoreCase = true) ||
            msg.contains("Sign in to confirm you’re not a bot", ignoreCase = true) ||
            msg.contains("Sign in to confirm you're not a bot", ignoreCase = true) ||
            msg.contains("Sign in to view", ignoreCase = true) ->
                Pair(ErrorCategory.LOGIN_REQUIRED, "來源網站需要登入帳號驗證，目前版本不支援登入下載")

            msg.contains("rate-limit", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("Please wait a few minutes", ignoreCase = true) ->
                Pair(ErrorCategory.RATE_LIMITED, "存取頻率受限 (Rate Limited)，請稍候再試")

            msg.contains("Unsupported URL", ignoreCase = true) ->
                Pair(ErrorCategory.UNSUPPORTED_URL, "不支援的網址或尚未支援該網站之解析")

            msg.contains("DRM", ignoreCase = true) ->
                Pair(ErrorCategory.DRM_PROTECTED, "此內容受 DRM 保護，本工具無法下載")

            msg.contains("HTTP Error 404", ignoreCase = true) ||
            msg.contains("404 Not Found", ignoreCase = true) ->
                Pair(ErrorCategory.NOT_FOUND, "找不到目標影片 (HTTP 404)")

            msg.contains("Unable to download webpage", ignoreCase = true) ||
            msg.contains("Connection refused", ignoreCase = true) ||
            msg.contains("Network is unreachable", ignoreCase = true) ->
                Pair(ErrorCategory.NETWORK_ERROR, "無法連線至目標網頁，請檢查網路連線")

            isInstagram && (msg.contains("Unable to extract", ignoreCase = true) || msg.contains("empty", ignoreCase = true)) ->
                Pair(ErrorCategory.EXTRACTOR_FAILURE, "Instagram 頁面解析失敗：$msg")

            isX && (msg.contains("Unable to extract", ignoreCase = true) || msg.contains("failed to parse", ignoreCase = true)) ->
                Pair(ErrorCategory.EXTRACTOR_FAILURE, "X (Twitter) 解析失敗：$msg")

            else ->
                Pair(ErrorCategory.GENERAL_ERROR, "解析或下載發生錯誤：$msg")
        }
    }
}
