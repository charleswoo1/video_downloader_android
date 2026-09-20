package com.charleswoo1.videodownloader.domain.url

object SharedTextUrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    private val TRAILING_TRIM_CHARS = charArrayOf(
        ')', ']', '}', '>', '」', '』', '）', '】', '》',
        ',', '.', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？',
        '\'', '"', '”', '’'
    )

    private val LEADING_TRIM_CHARS = charArrayOf(
        '(', '[', '{', '<', '「', '『', '（', '【', '《',
        '\'', '"', '“', '‘'
    )

    /**
     * Extracts all valid HTTP / HTTPS URLs found in the text in order.
     */
    fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        return URL_REGEX.findAll(text).mapNotNull { matchResult ->
            cleanUrl(matchResult.value)
        }.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .toList()
    }

    /**
     * Extracts the first valid HTTP / HTTPS URL found in the text, or null if none.
     */
    fun extractFirstUrl(text: String?): String? {
        return extractUrls(text).firstOrNull()
    }

    private fun cleanUrl(raw: String): String? {
        var url = raw.trim()
        url = url.trimStart(*LEADING_TRIM_CHARS)
        url = url.trimEnd(*TRAILING_TRIM_CHARS)

        var openCount = url.count { it == '(' }
        var closeCount = url.count { it == ')' }
        while (closeCount > openCount && url.endsWith(')')) {
            url = url.dropLast(1).trimEnd(*TRAILING_TRIM_CHARS)
            closeCount--
        }

        if (url.length < 10) return null
        return url.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }
}
