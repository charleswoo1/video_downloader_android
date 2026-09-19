package com.charleswoo1.videodownloader.data.download.http

import com.charleswoo1.videodownloader.domain.model.Platform
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

/**
 * Robust, domain-isolated cookie parser for platform authentication.
 *
 * Supports:
 * 1. HTTP Cookie header string (e.g. `sessionid=...; ds_user_id=...`)
 * 2. Netscape HTTP Cookie file format (tab-delimited, standard export)
 * 3. JSON array format (e.g. EditThisCookie / Cookie-Editor browser extensions)
 * 4. Multi-line key=value pairs
 *
 * Enforces strict per-platform domain isolation:
 * - Instagram cookies cannot be assigned to X
 * - X cookies cannot be assigned to Instagram / Threads
 */
object PlatformCookieParser {

    private val INSTAGRAM_ALLOWED_DOMAINS = setOf(
        "instagram.com", ".instagram.com",
        "i.instagram.com", ".i.instagram.com",
        "www.instagram.com", ".www.instagram.com"
    )

    private val THREADS_ALLOWED_DOMAINS = setOf(
        "threads.net", ".threads.net",
        "www.threads.net", ".www.threads.net",
        "threads.com", ".threads.com",
        "www.threads.com", ".www.threads.com"
    )

    private val X_ALLOWED_DOMAINS = setOf(
        "x.com", ".x.com",
        "twitter.com", ".twitter.com",
        "api.x.com", ".api.x.com",
        "api.twitter.com", ".api.twitter.com"
    )

    fun getAllowedDomains(platform: Platform): Set<String> = when (platform) {
        Platform.INSTAGRAM -> INSTAGRAM_ALLOWED_DOMAINS
        Platform.THREADS -> THREADS_ALLOWED_DOMAINS
        Platform.X -> X_ALLOWED_DOMAINS
        else -> emptySet()
    }

    fun getDefaultDomain(platform: Platform): String = when (platform) {
        Platform.INSTAGRAM -> "instagram.com"
        Platform.THREADS -> "threads.net"
        Platform.X -> "x.com"
        else -> "unknown"
    }

    /**
     * Parses raw input into a list of OkHttp [Cookie] objects scoped strictly to [platform].
     * Throws [IllegalArgumentException] if input cannot be parsed or contains no valid cookies.
     */
    fun parse(rawInput: String, platform: Platform): List<Cookie> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("輸入的 Cookie 內容為空")
        }

        val parsedCookies = when {
            trimmed.startsWith("[") && trimmed.endsWith("]") -> parseJson(trimmed, platform)
            trimmed.lines().any { it.contains("\t") } -> parseNetscape(trimmed, platform)
            else -> parseHeaderOrLines(trimmed, platform)
        }

        if (parsedCookies.isEmpty()) {
            throw IllegalArgumentException("未能在輸入內容中解析出符合 ${platform.displayName} 的有效 Cookie")
        }

        // Deduplicate cookies by name+domain (latest wins)
        val deduplicated = LinkedHashMap<String, Cookie>()
        for (cookie in parsedCookies) {
            val key = "${cookie.domain.removePrefix(".")}:${cookie.name}"
            deduplicated[key] = cookie
        }

        return deduplicated.values.toList()
    }

    private fun isDomainAllowed(domain: String, platform: Platform): Boolean {
        val clean = domain.removePrefix(".").lowercase()
        return when (platform) {
            Platform.INSTAGRAM -> clean == "instagram.com" || clean.endsWith(".instagram.com")
            Platform.THREADS -> clean == "threads.net" || clean.endsWith(".threads.net") ||
                    clean == "threads.com" || clean.endsWith(".threads.com")
            Platform.X -> clean == "x.com" || clean.endsWith(".x.com") ||
                    clean == "twitter.com" || clean.endsWith(".twitter.com")
            else -> false
        }
    }

    private fun parseHeaderOrLines(input: String, platform: Platform): List<Cookie> {
        val defaultDomain = getDefaultDomain(platform)
        val result = mutableListOf<Cookie>()

        // Split on semicolons or newlines
        val pairs = input.split(Regex("[;\r\n]+")).map { it.trim() }.filter { it.isNotBlank() }

        for (pair in pairs) {
            if (pair.startsWith("#")) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue

            val name = pair.substring(0, eqIdx).trim()
            val value = pair.substring(eqIdx + 1).trim()
            if (name.isBlank() || value.isBlank()) continue

            // Build OkHttp Cookie
            try {
                val cookie = Cookie.Builder()
                    .domain(defaultDomain)
                    .path("/")
                    .name(name)
                    .value(value)
                    .secure()
                    .build()
                result.add(cookie)
            } catch (_: Exception) {
                // Ignore invalid cookie name/value syntax
            }
        }
        return result
    }

    private fun parseNetscape(input: String, platform: Platform): List<Cookie> {
        val result = mutableListOf<Cookie>()
        val defaultDomain = getDefaultDomain(platform)

        for (line in input.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isBlank()) continue

            val isHttpOnly = trimmedLine.startsWith("#HttpOnly_", ignoreCase = true)
            val effectiveLine = if (isHttpOnly) {
                trimmedLine.substring("#HttpOnly_".length).trim()
            } else {
                trimmedLine
            }

            if (!isHttpOnly && effectiveLine.startsWith("#")) continue

            val parts = effectiveLine.split("\t").map { it.trim() }
            if (parts.size >= 7) {
                val rawDomain = parts[0]
                val path = parts[2].ifBlank { "/" }
                val secure = parts[3].equals("TRUE", ignoreCase = true)
                val expiresAtSeconds = parts[4].toLongOrNull() ?: 0L
                val name = parts[5]
                val value = parts[6]

                if (name.isBlank()) continue

                val cleanDomain = rawDomain.removePrefix(".")
                if (!isDomainAllowed(cleanDomain, platform)) {
                    // Skip cookies not belonging to target platform to maintain strict domain isolation
                    continue
                }

                try {
                    val builder = Cookie.Builder()
                        .domain(cleanDomain)
                        .path(path)
                        .name(name)
                        .value(value)

                    if (secure) builder.secure()
                    if (isHttpOnly) builder.httpOnly()
                    if (expiresAtSeconds > 0) {
                        builder.expiresAt(expiresAtSeconds * 1000L)
                    }

                    result.add(builder.build())
                } catch (_: Exception) {}
            } else if (parts.size == 1 && effectiveLine.contains("=")) {
                // Fallback for single line without tabs
                result.addAll(parseHeaderOrLines(effectiveLine, platform))
            }
        }
        return result
    }

    private fun parseJson(input: String, platform: Platform): List<Cookie> {
        val result = mutableListOf<Cookie>()
        val defaultDomain = getDefaultDomain(platform)

        try {
            val array = JSONArray(input)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val value = obj.optString("value")
                if (name.isBlank() || value.isBlank()) continue

                var domain = obj.optString("domain").ifBlank { defaultDomain }
                val cleanDomain = domain.removePrefix(".")

                if (!isDomainAllowed(cleanDomain, platform)) {
                    // Strict domain isolation: skip cookies belonging to other sites
                    continue
                }

                val path = obj.optString("path").ifBlank { "/" }
                val secure = obj.optBoolean("secure", true)
                val expirationDate = obj.optDouble("expirationDate", 0.0)

                try {
                    val builder = Cookie.Builder()
                        .domain(cleanDomain)
                        .path(path)
                        .name(name)
                        .value(value)

                    if (secure) builder.secure()
                    if (expirationDate > 0) {
                        builder.expiresAt((expirationDate * 1000).toLong())
                    }

                    result.add(builder.build())
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON 格式解析失敗: ${e.message}", e)
        }

        return result
    }
}
