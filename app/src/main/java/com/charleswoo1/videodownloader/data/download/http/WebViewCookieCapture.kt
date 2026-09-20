package com.charleswoo1.videodownloader.data.download.http

import android.content.Intent
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Result of inspecting raw WebView cookies against a platform configuration.
 */
sealed interface CookieCandidateResult {
    data class Candidate(
        val filteredCookieHeader: String,
        val cookieCount: Int
    ) : CookieCandidateResult {
        // Redact cookie values in toString() to prevent secret leakage
        override fun toString(): String = "Candidate(cookieCount=$cookieCount)"
    }

    data class MissingRequired(val missingNames: Set<String>) : CookieCandidateResult {
        override fun toString(): String = "MissingRequired(missingNames=$missingNames)"
    }

    data object Empty : CookieCandidateResult
}

/**
 * Utility for safe cookie extraction, allowlist filtering, User-Agent normalization,
 * and scheme/intent navigation validation.
 */
object WebViewCookieCapture {

    /**
     * Inspects a raw cookie header string from Android CookieManager, verifies required cookies,
     * and retains only platform allowlisted cookies (required + optional).
     *
     * Eliminates duplicate names deterministically (latest occurrence wins).
     */
    fun extractCandidate(rawCookieHeader: String?, config: PlatformWebLoginConfig): CookieCandidateResult {
        if (rawCookieHeader.isNullOrBlank()) {
            return CookieCandidateResult.Empty
        }

        val pairs = rawCookieHeader.split(Regex("[;\r\n]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (pairs.isEmpty()) {
            return CookieCandidateResult.Empty
        }

        // Deduplicate cookies: latest entry for each name wins (case-insensitive name match)
        val allowlist = (config.requiredCookieNames + config.optionalCookieNames)
            .map { it.lowercase() }
            .toSet()

        val parsedMap = LinkedHashMap<String, Pair<String, String>>() // lowerName -> (originalName, value)

        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue

            val name = pair.substring(0, eqIdx).trim()
            val value = pair.substring(eqIdx + 1).trim()
            if (name.isBlank() || value.isBlank()) continue

            val lowerName = name.lowercase()
            if (lowerName in allowlist) {
                parsedMap[lowerName] = Pair(name, value)
            }
        }

        val requiredLower = config.requiredCookieNames.map { it.lowercase() }.toSet()
        val presentLower = parsedMap.keys
        val missing = requiredLower - presentLower

        if (missing.isNotEmpty()) {
            val missingOriginal = config.requiredCookieNames.filter { it.lowercase() in missing }.toSet()
            return CookieCandidateResult.MissingRequired(missingOriginal)
        }

        val filteredHeader = parsedMap.values.joinToString("; ") { "${it.first}=${it.second}" }
        return CookieCandidateResult.Candidate(
            filteredCookieHeader = filteredHeader,
            cookieCount = parsedMap.size
        )
    }

    /**
     * Checks if a navigation URL is safe to load inside the WebView.
     */
    fun isAllowedNavigation(url: String): Boolean {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower == "about:blank"
    }

    /**
     * Extracts browser_fallback_url from an intent:// scheme URI if present and safe (HTTP/HTTPS).
     * Uses Android Intent.parseUri with a regex fallback for pure JVM unit test compatibility.
     */
    fun resolveFallbackUrl(uriString: String): String? {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("intent://", ignoreCase = true)) {
            return null
        }

        val fallback = runCatching {
            val intent = Intent.parseUri(trimmed, Intent.URI_INTENT_SCHEME)
            intent.getStringExtra("browser_fallback_url")
        }.getOrNull() ?: extractFallbackFromIntentString(trimmed)

        val cleanFallback = fallback?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (cleanFallback.startsWith("https://", ignoreCase = true) ||
            cleanFallback.startsWith("http://", ignoreCase = true)
        ) {
            cleanFallback
        } else {
            null
        }
    }

    private fun extractFallbackFromIntentString(intentString: String): String? {
        val pattern = Regex("""(?:S\.browser_fallback_url|browser_fallback_url)=([^;]+)""")
        val match = pattern.find(intentString) ?: return null
        val raw = match.groupValues[1].trim()
        return runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        }.getOrDefault(raw)
    }

    /**
     * Normalizes the runtime default WebView User-Agent by removing the embedded-WebView
     * '; wv' token and 'Version/X.X' marker, ensuring Instagram does not detect an embedded browser
     * while preserving the device's real Android and Chrome version numbers.
     */
    fun normalizeUserAgent(defaultUa: String): String {
        return defaultUa
            .replace(Regex("(?i);\\s*wv"), "")
            .replace(Regex("(?i)Version/\\d+\\.\\d+\\s*"), "")
            .trim()
    }
}
