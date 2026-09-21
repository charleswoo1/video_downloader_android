package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.PlatformMediaEngine
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession.HttpResponse
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Native Threads extraction and download engine V2.
 *
 * Implements reference-driven extraction ported from 2Xsave/trsave (MIT) and yt-dlp-threads:
 * - Coherent desktop/mobile request profile escalation
 * - Strict target-post isolation preventing extraction of unrelated feed/recommended videos
 * - Robust Meta CDN URL normalization and non-.mp4 path acceptance
 * - Progressive streams and DASH video+audio manifest extraction
 * - Direct media streaming using dedicated MEDIA headers
 */
class NativeThreadsEngine(
    private val context: Context? = null,
    private val httpSession: PlatformHttpSession = PlatformHttpSession()
) : PlatformMediaEngine {

    companion object {
        private const val TAG = "NativeThreadsEngine"
        const val ENGINE_NAME = "NativeThreadsEngine"

        const val BARCELONA_DOC_ID = "25460088156920903"
        const val THREADS_APP_ID = "238260118697367"
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun shortcodeToPk(shortcode: String): String {
            var id = BigInteger.ZERO
            val base = BigInteger.valueOf(64L)
            for (char in shortcode) {
                val index = ALPHABET.indexOf(char)
                if (index == -1) continue
                id = id.multiply(base).add(BigInteger.valueOf(index.toLong()))
            }
            return id.toString()
        }

        fun pkToShortcode(pk: String): String {
            var num = try {
                BigInteger(pk)
            } catch (_: Exception) {
                return ""
            }
            if (num == BigInteger.ZERO) return "A"
            val base = BigInteger.valueOf(64L)
            val sb = StringBuilder()
            while (num > BigInteger.ZERO) {
                val rem = num.mod(base).toInt()
                sb.append(ALPHABET[rem])
                num = num.divide(base)
            }
            return sb.reverse().toString()
        }

        fun findTargetPostInGraphQL(root: Any?, targetCode: String, targetPk: String): JSONObject? {
            if (root == null) return null
            when (root) {
                is JSONObject -> {
                    val hasPostShape = root.has("code") || root.has("pk") || root.has("video_versions") || root.has("carousel_media")
                    if (hasPostShape) {
                        val code = root.optString("code")
                        val pk = root.optString("pk").ifBlank {
                            val pkLong = root.optLong("pk", 0L)
                            if (pkLong > 0L) pkLong.toString() else ""
                        }
                        if ((code.isNotBlank() && code == targetCode) || (pk.isNotBlank() && pk == targetPk)) {
                            return root
                        }
                    }
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (isExcludedContainerKey(key)) continue
                        val child = root.opt(key)
                        val found = findTargetPostInGraphQL(child, targetCode, targetPk)
                        if (found != null) return found
                    }
                }
                is JSONArray -> {
                    for (i in 0 until root.length()) {
                        val found = findTargetPostInGraphQL(root.opt(i), targetCode, targetPk)
                        if (found != null) return found
                    }
                }
            }
            return null
        }

        private val POST_ID_PATTERN = Pattern.compile("""/(?:post|t)/([A-Za-z0-9_-]+)""")
        private val SCRIPT_JSON_PATTERN = Pattern.compile(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val DATA_SJS_PATTERN = Pattern.compile(
            """<script\b(?=[^>]*\bdata-sjs\b)[^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val GENERIC_SCRIPT_PATTERN = Pattern.compile(
            """<script\b[^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        val THREADS_MARKERS = listOf(
            "video_versions",
            "video_dash_manifest",
            "carousel_media",
            "linked_inline_media",
            "link_preview_response",
            "quoted_post",
            "quoted_attachment_post",
            "text_post_app_info",
            "reposted_post"
        )

        fun formatSizeBucket(bytes: Int): String = when {
            bytes < 10 * 1024 -> "<10KB"
            bytes <= 100 * 1024 -> "10-100KB"
            else -> ">100KB"
        }

        fun cleanHostAndPath(urlStr: String): String {
            return try {
                val uri = java.net.URI(urlStr)
                val host = uri.host ?: ""
                val path = uri.path ?: ""
                "$host$path"
            } catch (_: Exception) {
                urlStr.substringBefore('?').substringBefore('#')
            }
        }

        fun isExcludedContainerKey(key: String): Boolean {
            return key in listOf(
                "relatedPosts", "related_posts", "feed_units", "feedUnits",
                "parentPost", "parent_post", "suggested_users", "suggestedUsers"
            )
        }

        fun collectJsonFromScript(
            raw: String,
            candidates: MutableList<JSONObject>,
            maxDepth: Int = 3,
            targetMarker: String? = null,
            targetMarkerAlt: String? = null
        ) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val obj = JSONObject(trimmed)
                    candidates.add(obj)
                    collectNestedJsonStrings(obj, candidates, 1, maxDepth)
                    return
                } catch (_: Exception) {}
            } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                try {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        if (item != null) {
                            candidates.add(item)
                            collectNestedJsonStrings(item, candidates, 1, maxDepth)
                        }
                    }
                    return
                } catch (_: Exception) {}
            }
            extractBalancedJsonPayloads(trimmed, candidates, maxDepth, targetMarker, targetMarkerAlt)
        }

        fun collectNestedJsonStrings(
            root: Any?,
            candidates: MutableList<JSONObject>,
            currentDepth: Int,
            maxDepth: Int
        ) {
            if (root == null || currentDepth > maxDepth) return
            if (root is String) {
                val trimmed = root.trim()
                val start = minOf(
                    trimmed.indexOf('{').takeIf { it >= 0 } ?: Int.MAX_VALUE,
                    trimmed.indexOf('[').takeIf { it >= 0 } ?: Int.MAX_VALUE
                )
                if (start != Int.MAX_VALUE) {
                    val isObj = trimmed[start] == '{'
                    val end = if (isObj) trimmed.lastIndexOf('}') else trimmed.lastIndexOf(']')
                    if (end > start) {
                        val sub = trimmed.substring(start, end + 1)
                        try {
                            if (isObj) {
                                val obj = JSONObject(sub)
                                candidates.add(obj)
                                collectNestedJsonStrings(obj, candidates, currentDepth + 1, maxDepth)
                            } else {
                                val arr = JSONArray(sub)
                                for (i in 0 until arr.length()) {
                                    collectNestedJsonStrings(arr.opt(i), candidates, currentDepth + 1, maxDepth)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                return
            }
            if (root is JSONObject) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isExcludedContainerKey(key)) continue
                    val value = root.opt(key)
                    if (value is String || value is JSONObject || value is JSONArray) {
                        collectNestedJsonStrings(value, candidates, currentDepth, maxDepth)
                    }
                }
            } else if (root is JSONArray) {
                for (i in 0 until root.length()) {
                    collectNestedJsonStrings(root.opt(i), candidates, currentDepth, maxDepth)
                }
            }
        }

        fun extractBalancedJsonPayloads(
            text: String,
            candidates: MutableList<JSONObject>,
            maxDepth: Int,
            targetMarker: String? = null,
            targetMarkerAlt: String? = null
        ) {
            var i = 0
            val n = text.length
            while (i < n) {
                if (text[i] == '{') {
                    val start = i
                    var depth = 0
                    var inString = false
                    var escape = false
                    var j = i
                    while (j < n) {
                        val c = text[j]
                        if (escape) {
                            escape = false
                        } else if (c == '\\' && inString) {
                            escape = true
                        } else if (c == '"') {
                            inString = !inString
                        } else if (!inString) {
                            if (c == '{') depth++
                            else if (c == '}') {
                                depth--
                                if (depth == 0) {
                                    val candidateStr = text.substring(start, j + 1)
                                    var parsed = false
                                    val matchesMarker = targetMarker == null ||
                                        candidateStr.contains(targetMarker) ||
                                        (!targetMarkerAlt.isNullOrBlank() && candidateStr.contains(targetMarkerAlt))
                                    if (matchesMarker) {
                                        try {
                                            val obj = JSONObject(candidateStr)
                                            candidates.add(obj)
                                            collectNestedJsonStrings(obj, candidates, 1, maxDepth)
                                            parsed = true
                                        } catch (_: Exception) {}
                                    }
                                    if (parsed) {
                                        i = j
                                    }
                                    break
                                }
                            }
                        }
                        j++
                    }
                }
                i++
            }
        }
        private val CANONICAL_LINK_PATTERN = Pattern.compile(
            """<link\b(?=[^>]*\brel=["']canonical["'])(?=[^>]*\bhref=["']([^"']+)["'])[^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        private val OG_URL_PATTERN = Pattern.compile(
            """<meta\b(?=[^>]*\b(?:property|name)=["']og:url["'])(?=[^>]*\bcontent=["']([^"']+)["'])[^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        private val DASH_BASE_URL_PATTERN = Pattern.compile(
            """<BaseURL\b[^>]*>(.*?)</BaseURL\s*>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val DASH_ADAPTATION_PATTERN = Pattern.compile(
            """<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet\s*>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val DASH_REPRESENTATION_PATTERN = Pattern.compile(
            """<Representation\b([^>]*)>(.*?)</Representation\s*>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        /**
         * Decodes XML entities like &amp;, &quot;, &lt;, &gt;, &apos;.
         */
        fun unescapeXml(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
        }

        /**
         * RFC 3986 compliant percent-decoder:
         * Decodes %XX sequences as UTF-8 bytes while strictly preserving literal '+' characters
         * to avoid corrupting signed Meta CDN security query parameters (e.g. stkn, sig).
         */
        fun percentDecode(input: String): String {
            val out = StringBuilder(input.length)
            val byteBuf = java.io.ByteArrayOutputStream()
            var i = 0
            val n = input.length
            while (i < n) {
                val c = input[i]
                if (c == '%' && i + 2 < n) {
                    val d1 = Character.digit(input[i + 1], 16)
                    val d2 = Character.digit(input[i + 2], 16)
                    if (d1 != -1 && d2 != -1) {
                        byteBuf.write((d1 shl 4) or d2)
                        i += 3
                        continue
                    }
                }
                if (byteBuf.size() > 0) {
                    out.append(byteBuf.toString("UTF-8"))
                    byteBuf.reset()
                }
                out.append(c)
                i++
            }
            if (byteBuf.size() > 0) {
                out.append(byteBuf.toString("UTF-8"))
                byteBuf.reset()
            }
            return out.toString()
        }

        /**
         * Normalizes Meta CDN URLs per 2Xsave/insave:
         * 1. Unescapes slashes (\/ -> /)
         * 2. Unescapes XML entities (&amp; -> &)
         * 3. Decodes \uXXXX unicode escapes across entire URL
         * 4. Decodes percent-encoded components preserving literal '+'
         */
        fun normalizeCdnUrl(rawUrl: String): String {
            val unescapedSlashes = rawUrl.replace("\\/", "/")
            val xmlUnescaped = unescapeXml(unescapedSlashes)
            val sb = StringBuilder()
            var i = 0
            while (i < xmlUnescaped.length) {
                if (xmlUnescaped[i] == '\\' && i + 5 < xmlUnescaped.length && xmlUnescaped[i + 1] == 'u') {
                    val hex = xmlUnescaped.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                sb.append(xmlUnescaped[i])
                i++
            }
            val unicodeDecoded = sb.toString()
            return percentDecode(unicodeDecoded)
        }

        fun isMetaVideoUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith("http") && (
                lower.contains(".mp4") ||
                lower.contains("cdninstagram.com") ||
                lower.contains("o1/v/t2/f2/m") ||
                lower.contains("fbcdn.net") ||
                lower.contains("/v/")
            )
        }
    }

    override val name: String = ENGINE_NAME

    private val isCancelled = AtomicBoolean(false)

    var lastProfileSequence: List<String> = emptyList()
        private set

    override var lastDiagnosticFingerprint: String? = null
        private set

    private var lastBootstrapResp: PlatformHttpSession.HttpResponse? = null

    override fun supports(platform: Platform): Boolean = platform == Platform.THREADS

    override fun cancelDownload() {
        isCancelled.set(true)
    }

    fun normalizeUrl(rawUrl: String): String {
        var clean = rawUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        if (clean.endsWith("/media")) {
            clean = clean.substringBeforeLast("/media").trimEnd('/')
        }
        clean = clean.replace("://www.threads.net/", "://www.threads.com/")
            .replace("://threads.net/", "://www.threads.com/")
            .replace("://threads.com/", "://www.threads.com/")
        return clean
    }

    fun extractPostId(url: String): String? {
        val matcher = POST_ID_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun extractCanonicalFromHtml(html: String): String? {
        val canonicalMatch = CANONICAL_LINK_PATTERN.matcher(html)
        if (canonicalMatch.find()) {
            val c = canonicalMatch.group(1)
            if (!c.isNullOrBlank() && (c.contains("/post/") || c.contains("/t/"))) {
                return normalizeUrl(c)
            }
        }
        val ogMatch = OG_URL_PATTERN.matcher(html)
        if (ogMatch.find()) {
            val og = ogMatch.group(1)
            if (!og.isNullOrBlank() && (og.contains("/post/") || og.contains("/t/"))) {
                return normalizeUrl(og)
            }
        }
        return null
    }

    suspend fun resolveShareUrl(url: String): String = withContext(Dispatchers.IO) {
        if (!url.contains("/share/")) {
            return@withContext normalizeUrl(url)
        }

        val redirected = httpSession.resolveRedirectUrl(url, RequestProfile.DESKTOP_NAVIGATION)
        val targetUrl = redirected.getOrDefault(url)
        val normalized = normalizeUrl(targetUrl)
        if (normalized.contains("/post/") || normalized.contains("/t/")) {
            return@withContext normalized
        }

        // Fetch share page HTML to inspect canonical meta tags
        val shareHtml = httpSession.fetch(url, RequestProfile.DESKTOP_NAVIGATION).getOrNull()?.body ?: ""
        val canonical = extractCanonicalFromHtml(shareHtml)
        if (canonical != null) {
            return@withContext canonical
        }

        normalized
    }

    data class ThreadsDiagnostics(
        val resolvedShare: Boolean = false,
        val scriptCount: Int = 0,
        val scriptSource: String = "none",
        val rawCodeInHtml: Boolean = false,
        val decodedCodeInHtml: Boolean = false,
        val targetWrapperFound: Boolean = false,
        val mediaNodeFound: Boolean = false,
        val matchedContainerKeys: List<String> = emptyList(),
        val presentMarkers: List<String> = emptyList(),
        val restrictionPhrase: String = "NONE",
        val stage: String = "INIT"
    )

    data class ThreadsParseOutcome(
        val result: MetaExtractionResult,
        val diagnostics: ThreadsDiagnostics
    )

    fun parseThreadsPage(html: String, targetShortcode: String, canonicalUrl: String, resolvedShare: Boolean = false): MetaExtractionResult {
        return parseThreadsPageWithDiagnostics(html, targetShortcode, canonicalUrl, resolvedShare).result
    }

    fun parseThreadsPageWithDiagnostics(
        html: String,
        targetShortcode: String,
        canonicalUrl: String,
        resolvedShare: Boolean = false
    ): ThreadsParseOutcome {
        val targetPk = shortcodeToPk(targetShortcode)
        val rawCodeInHtml = html.contains(targetShortcode) || (targetPk.isNotBlank() && html.contains(targetPk))
        val presentMarkers = THREADS_MARKERS.filter { html.contains(it) }

        fun countMatches(p: Pattern): Int {
            val m = p.matcher(html)
            var c = 0
            while (m.find()) c++
            return c
        }
        val totalScriptCount = countMatches(GENERIC_SCRIPT_PATTERN)

        val baseDiag = ThreadsDiagnostics(
            resolvedShare = resolvedShare,
            scriptCount = totalScriptCount,
            rawCodeInHtml = rawCodeInHtml,
            presentMarkers = presentMarkers
        )

        // 1. Check for deleted or private post
        if (html.contains("Post \"$targetShortcode\" was not found in the page data", ignoreCase = true) ||
            html.contains("deleted, private, login-gated", ignoreCase = true) ||
            html.contains("Sorry, this page isn't available.", ignoreCase = true)
        ) {
            return ThreadsParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Restricted(
                        RestrictionReason.DELETED_OR_PRIVATE,
                        "Threads 貼文不存在、設為私人內容或需要登入帳號驗證"
                    )
                ),
                baseDiag.copy(restrictionPhrase = "DELETED_OR_PRIVATE", stage = "DELETED_CHECK")
            )
        }

        // 2. Scan script tags for JSON payload (application/json, data-sjs, generic containing shortcode or pk)
        val candidates = mutableListOf<JSONObject>()
        var detectedScriptSource = "none"

        fun scanScriptPatterns(pattern: Pattern, targetMarker: String? = null, targetMarkerAlt: String? = null, sourceName: String) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val raw = matcher.group(1)?.trim() ?: continue
                val beforeCount = candidates.size
                collectJsonFromScript(raw, candidates, maxDepth = 3, targetMarker = targetMarker, targetMarkerAlt = targetMarkerAlt)
                if (candidates.size > beforeCount && detectedScriptSource == "none") {
                    detectedScriptSource = sourceName
                }
            }
        }

        scanScriptPatterns(SCRIPT_JSON_PATTERN, sourceName = "application_json")
        scanScriptPatterns(DATA_SJS_PATTERN, targetMarker = targetShortcode, targetMarkerAlt = targetPk, sourceName = "data_sjs")
        scanScriptPatterns(GENERIC_SCRIPT_PATTERN, targetMarker = targetShortcode, targetMarkerAlt = targetPk, sourceName = "nested_json")

        val decodedCodeInHtml = candidates.any {
            val s = it.toString()
            s.contains(targetShortcode) || (targetPk.isNotBlank() && s.contains(targetPk))
        }
        val diagWithDecode = baseDiag.copy(
            scriptSource = if (detectedScriptSource == "none" && candidates.isNotEmpty()) "application_json" else detectedScriptSource,
            decodedCodeInHtml = decodedCodeInHtml
        )

        // 3. Find target post strictly matching code == targetShortcode OR pk == targetPk
        val searchResult = searchTargetPost(candidates, targetShortcode, targetPk, diagWithDecode.scriptSource)
        val targetPost = searchResult.postNode

        // Target post isolation: If target post was not found, STRICTLY REFUSE to use unrelated feed posts
        if (targetPost == null) {
            val failure = if (searchResult.wrapperSeen) {
                MetaExtractionResult.Failure(
                    MetaExtractionError.Technical(
                        "Threads 貼文代碼符合，但目前頁面資料未解析出有效媒體節點。"
                    )
                )
            } else {
                MetaExtractionResult.Failure(
                    MetaExtractionError.Technical(
                        "Threads 已找到貼文連結，但目前頁面未提供可解析的目標媒體資料。"
                    )
                )
            }
            return ThreadsParseOutcome(
                failure,
                diagWithDecode.copy(
                    targetWrapperFound = searchResult.wrapperSeen,
                    mediaNodeFound = false,
                    matchedContainerKeys = searchResult.matchedKeys,
                    stage = if (searchResult.wrapperSeen) "WRAPPER_SEEN_NO_POST" else "NO_TARGET_FOUND"
                )
            )
        }

        val outcome = extractMediaFromPost(targetPost, targetShortcode, canonicalUrl)
        val isSuccess = outcome is MetaExtractionResult.Success
        val matchedKeys = if (searchResult.matchedKeys.isNotEmpty()) {
            searchResult.matchedKeys
        } else {
            targetPost.keys().asSequence().toList().sorted()
        }

        return ThreadsParseOutcome(
            outcome,
            diagWithDecode.copy(
                targetWrapperFound = true,
                mediaNodeFound = isSuccess,
                matchedContainerKeys = matchedKeys,
                stage = if (isSuccess) "SUCCESS" else "NO_MEDIA"
            )
        )
    }

    data class ThreadsPostSearchResult(
        val postNode: JSONObject? = null,
        val wrapperSeen: Boolean = false,
        val scriptSource: String = "application_json",
        val matchedKeys: List<String> = emptyList()
    )

    private fun checkObjectHasMedia(obj: JSONObject?): Boolean {
        if (obj == null) return false
        val versions = obj.optJSONArray("video_versions")
        if (versions != null && versions.length() > 0) return true
        if (obj.optString("video_dash_manifest").isNotBlank()) return true
        return false
    }

    private fun hasThreadsMediaStructure(obj: JSONObject): Boolean {
        if (checkObjectHasMedia(obj)) return true
        if (checkObjectHasMedia(obj.optJSONObject("media"))) return true

        val textPostAppInfo = obj.optJSONObject("text_post_app_info")
        if (textPostAppInfo != null) {
            if (checkObjectHasMedia(textPostAppInfo.optJSONObject("media"))) return true
            val shareInfo = textPostAppInfo.optJSONObject("share_info")
            if (shareInfo != null) {
                if (checkObjectHasMedia(shareInfo.optJSONObject("media"))) return true
                if (checkObjectHasMedia(shareInfo.optJSONObject("quoted_post"))) return true
                if (checkObjectHasMedia(shareInfo.optJSONObject("quoted_attachment_post"))) return true
                val reposted = shareInfo.optJSONObject("reposted_post")
                if (reposted != null && hasThreadsMediaStructure(reposted)) return true
            }
            if (checkObjectHasMedia(textPostAppInfo.optJSONObject("quoted_post"))) return true
            if (checkObjectHasMedia(textPostAppInfo.optJSONObject("quoted_attachment_post"))) return true
            val repostedUnderApp = textPostAppInfo.optJSONObject("reposted_post")
            if (repostedUnderApp != null && hasThreadsMediaStructure(repostedUnderApp)) return true
            val linkedMedia = textPostAppInfo.optJSONObject("linked_inline_media")?.optJSONObject("media")
                ?: textPostAppInfo.optJSONObject("link_preview_response")?.optJSONObject("video")
                ?: textPostAppInfo.optJSONObject("linked_inline_media")
            if (checkObjectHasMedia(linkedMedia)) return true
        }

        val repost = obj.optJSONObject("repost_post") ?: obj.optJSONObject("reposted_post")
        if (repost != null && hasThreadsMediaStructure(repost)) return true

        val quoted = obj.optJSONObject("quoted_post")
            ?: obj.optJSONObject("quoted_attachment_post")
            ?: textPostAppInfo?.optJSONObject("share_info")?.optJSONObject("quoted_attachment_post")
            ?: textPostAppInfo?.optJSONObject("share_info")?.optJSONObject("quoted_post")
            ?: textPostAppInfo?.optJSONObject("quoted_attachment_post")
            ?: textPostAppInfo?.optJSONObject("quoted_post")
        if (quoted != null && hasThreadsMediaStructure(quoted)) return true

        val carousel = obj.optJSONArray("carousel_media")
            ?: obj.optJSONObject("media")?.optJSONArray("carousel_media")
            ?: repost?.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val item = carousel.optJSONObject(i) ?: continue
                if (checkObjectHasMedia(item)) return true
            }
        }
        return false
    }

    private fun isThreadsImageOrTextPost(obj: JSONObject): Boolean {
        val typename = obj.optString("__typename")
        if (typename == "XDTGraphImage" || typename == "GraphImage" || typename == "XDTTextPost" || typename == "TextPost") {
            return true
        }

        val mediaType = obj.optInt("media_type", 0)
        if (mediaType == 1) {
            return true
        }

        if (obj.optString("post_type") == "text" || obj.optBoolean("is_text_only", false)) {
            return true
        }

        if (obj.has("is_video") && !obj.optBoolean("is_video", true)) {
            val hasImages = obj.optJSONObject("image_versions2")?.optJSONArray("candidates")?.let { it.length() > 0 } ?: false
            val hasDisplayUrl = obj.optString("display_url").isNotBlank()
            if (hasImages || hasDisplayUrl) {
                return true
            }
        }

        return false
    }

    private fun searchTargetPost(
        candidates: List<JSONObject>,
        targetCode: String,
        targetPk: String,
        scriptSource: String
    ): ThreadsPostSearchResult {
        var wrapperSeen = false
        var fallbackPostNode: JSONObject? = null
        var matchedKeys = emptyList<String>()
        for (candidate in candidates) {
            val res = findPostByCodeOrPk(candidate, targetCode, targetPk)
            if (res.wrapperSeen) {
                wrapperSeen = true
                if (res.matchedKeys.isNotEmpty()) matchedKeys = res.matchedKeys
            }
            if (res.postNode != null) {
                if (hasThreadsMediaStructure(res.postNode)) {
                    return res.copy(scriptSource = scriptSource)
                } else if (fallbackPostNode == null) {
                    fallbackPostNode = res.postNode
                    matchedKeys = res.matchedKeys
                }
            }
        }
        return ThreadsPostSearchResult(
            postNode = fallbackPostNode,
            wrapperSeen = wrapperSeen,
            scriptSource = scriptSource,
            matchedKeys = matchedKeys
        )
    }

    private fun findPostByCodeOrPk(root: Any?, targetCode: String, targetPk: String): ThreadsPostSearchResult {
        if (root == null) return ThreadsPostSearchResult()
        var wrapperSeen = false
        if (root is JSONObject) {
            val code = root.optString("code")
            val pk = root.optString("pk").ifBlank {
                val pkLong = root.optLong("pk", 0L)
                if (pkLong > 0L) pkLong.toString() else ""
            }.ifBlank {
                root.optString("id").ifBlank {
                    val idLong = root.optLong("id", 0L)
                    if (idLong > 0L) idLong.toString() else ""
                }
            }
            val matchesCode = code.isNotBlank() && code == targetCode
            val matchesPk = pk.isNotBlank() && targetPk.isNotBlank() && pk == targetPk
            if (matchesCode || matchesPk) {
                wrapperSeen = true
                val keys = root.keys().asSequence().toList().sorted()
                if (hasThreadsMediaStructure(root)) {
                    return ThreadsPostSearchResult(postNode = root, wrapperSeen = true, matchedKeys = keys)
                }

                // Descend into children
                val childKeys = root.keys()
                var fallbackChildResult: ThreadsPostSearchResult? = null
                while (childKeys.hasNext()) {
                    val key = childKeys.next()
                    if (isExcludedContainerKey(key)) continue
                    val child = root.opt(key)
                    val childRes = findPostByCodeOrPk(child, targetCode, targetPk)
                    if (childRes.wrapperSeen) wrapperSeen = true
                    if (childRes.postNode != null) {
                        if (hasThreadsMediaStructure(childRes.postNode)) return childRes
                        if (fallbackChildResult == null) fallbackChildResult = childRes
                    }
                }
                if (fallbackChildResult != null) return fallbackChildResult

                if (isThreadsImageOrTextPost(root)) {
                    return ThreadsPostSearchResult(postNode = root, wrapperSeen = true, matchedKeys = keys)
                }

                return ThreadsPostSearchResult(postNode = null, wrapperSeen = true, matchedKeys = keys)
            }

            val keys = root.keys()
            var fallbackChildResult: ThreadsPostSearchResult? = null
            while (keys.hasNext()) {
                val key = keys.next()
                if (isExcludedContainerKey(key)) continue
                val child = root.opt(key)
                val childRes = findPostByCodeOrPk(child, targetCode, targetPk)
                if (childRes.wrapperSeen) wrapperSeen = true
                if (childRes.postNode != null) {
                    if (hasThreadsMediaStructure(childRes.postNode)) return childRes
                    if (fallbackChildResult == null) fallbackChildResult = childRes
                }
            }
            if (fallbackChildResult != null) return fallbackChildResult
        } else if (root is JSONArray) {
            var fallbackChildResult: ThreadsPostSearchResult? = null
            for (i in 0 until root.length()) {
                val childRes = findPostByCodeOrPk(root.opt(i), targetCode, targetPk)
                if (childRes.wrapperSeen) wrapperSeen = true
                if (childRes.postNode != null) {
                    if (hasThreadsMediaStructure(childRes.postNode)) return childRes
                    if (fallbackChildResult == null) fallbackChildResult = childRes
                }
            }
            if (fallbackChildResult != null) return fallbackChildResult
        } else if (root is String) {
            val trimmed = root.trim()
            if (trimmed.contains(targetCode) || (targetPk.isNotBlank() && trimmed.contains(targetPk))) {
                val start = minOf(
                    trimmed.indexOf('{').takeIf { it >= 0 } ?: Int.MAX_VALUE,
                    trimmed.indexOf('[').takeIf { it >= 0 } ?: Int.MAX_VALUE
                )
                if (start != Int.MAX_VALUE) {
                    val isObj = trimmed[start] == '{'
                    val end = if (isObj) trimmed.lastIndexOf('}') else trimmed.lastIndexOf(']')
                    if (end > start) {
                        val sub = trimmed.substring(start, end + 1)
                        try {
                            val parsed = if (isObj) JSONObject(sub) else JSONArray(sub)
                            val found = findPostByCodeOrPk(parsed, targetCode, targetPk)
                            if (found.wrapperSeen || found.postNode != null) return found
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        return ThreadsPostSearchResult(postNode = null, wrapperSeen = wrapperSeen)
    }

    private fun extractRenditions(videoVersions: JSONArray?): List<NativeMediaRendition> {
        if (videoVersions == null || videoVersions.length() == 0) return emptyList()
        val renditions = mutableListOf<NativeMediaRendition>()
        for (i in 0 until videoVersions.length()) {
            val version = videoVersions.optJSONObject(i) ?: continue
            val rawUrl = version.optString("url")
            val width = version.optInt("width", 0)
            val height = version.optInt("height", 0)
            if (rawUrl.isNotBlank()) {
                val normalized = normalizeCdnUrl(rawUrl)
                if (isMetaVideoUrl(normalized)) {
                    renditions.add(NativeMediaRendition(url = normalized, width = width, height = height))
                }
            }
        }
        return renditions.sortedByDescending { it.resolution }
    }

    private data class DashCandidate(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
        val bandwidth: Long = 0L
    )

    internal fun parseDashManifest(manifestXml: String): Pair<String?, String?> {
        val videoCandidates = mutableListOf<DashCandidate>()
        val audioCandidates = mutableListOf<DashCandidate>()

        val adaptationMatcher = DASH_ADAPTATION_PATTERN.matcher(manifestXml)
        while (adaptationMatcher.find()) {
            val setAttrs = adaptationMatcher.group(1) ?: ""
            val setBody = adaptationMatcher.group(2) ?: ""

            val setLowerAttrs = setAttrs.lowercase()
            val isSetVideo = setLowerAttrs.contains("contenttype=\"video\"") || setLowerAttrs.contains("mimetype=\"video/")
            val isSetAudio = setLowerAttrs.contains("contenttype=\"audio\"") || setLowerAttrs.contains("mimetype=\"audio/")

            var foundRep = false
            val repMatcher = DASH_REPRESENTATION_PATTERN.matcher(setBody)
            while (repMatcher.find()) {
                foundRep = true
                val repAttrs = repMatcher.group(1) ?: ""
                val repBody = repMatcher.group(2) ?: ""

                val repLowerAttrs = repAttrs.lowercase()
                val isRepVideo = isSetVideo || repLowerAttrs.contains("mimetype=\"video/") || repLowerAttrs.contains("contenttype=\"video\"")
                val isRepAudio = isSetAudio || repLowerAttrs.contains("mimetype=\"audio/") || repLowerAttrs.contains("contenttype=\"audio\"")

                val width = Regex("""\bwidth\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE).find(repAttrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val height = Regex("""\bheight\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE).find(repAttrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bandwidth = Regex("""\bbandwidth\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE).find(repAttrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                val baseMatcher = DASH_BASE_URL_PATTERN.matcher(repBody)
                if (baseMatcher.find()) {
                    val rawUrl = baseMatcher.group(1)?.trim() ?: ""
                    if (rawUrl.isNotBlank()) {
                        val normalized = normalizeCdnUrl(rawUrl)
                        if (normalized.startsWith("http")) {
                            val candidate = DashCandidate(normalized, width, height, bandwidth)
                            if (isRepVideo || (!isRepAudio && (width > 0 || height > 0))) {
                                videoCandidates.add(candidate)
                            } else if (isRepAudio) {
                                audioCandidates.add(candidate)
                            } else {
                                if (width > 0 || height > 0) videoCandidates.add(candidate)
                                else audioCandidates.add(candidate)
                            }
                        }
                    }
                }
            }

            if (!foundRep || (isSetVideo && videoCandidates.isEmpty()) || (isSetAudio && audioCandidates.isEmpty())) {
                val baseMatcher = DASH_BASE_URL_PATTERN.matcher(setBody)
                if (baseMatcher.find()) {
                    val rawUrl = baseMatcher.group(1)?.trim() ?: ""
                    if (rawUrl.isNotBlank()) {
                        val normalized = normalizeCdnUrl(rawUrl)
                        if (normalized.startsWith("http")) {
                            val candidate = DashCandidate(normalized)
                            if (isSetVideo && videoCandidates.isEmpty()) videoCandidates.add(candidate)
                            else if (isSetAudio && audioCandidates.isEmpty()) audioCandidates.add(candidate)
                        }
                    }
                }
            }
        }

        if (videoCandidates.isEmpty() && audioCandidates.isEmpty()) {
            val baseMatcher = DASH_BASE_URL_PATTERN.matcher(manifestXml)
            if (baseMatcher.find()) {
                val rawUrl = baseMatcher.group(1)?.trim() ?: ""
                if (rawUrl.isNotBlank()) {
                    val normalized = normalizeCdnUrl(rawUrl)
                    if (normalized.startsWith("http")) {
                        videoCandidates.add(DashCandidate(normalized))
                    }
                }
            }
        }

        val bestVideo = videoCandidates.maxWithOrNull(
            compareBy<DashCandidate> { it.width * it.height }
                .thenBy { it.bandwidth }
        )?.url

        val bestAudio = audioCandidates.maxWithOrNull(
            compareBy<DashCandidate> { it.bandwidth }
        )?.url

        return Pair(bestVideo, bestAudio)
    }

    private fun extractMediaFromPost(post: JSONObject, targetShortcode: String, canonicalUrl: String): MetaExtractionResult {
        var renditions = extractRenditions(post.optJSONArray("video_versions"))
        var dashVideoUrl: String? = null
        var dashAudioUrl: String? = null

        // Recursive extraction across nested structures
        if (renditions.isEmpty()) {
            val dashManifest = post.optString("video_dash_manifest")
            if (dashManifest.isNotBlank()) {
                val (v, a) = parseDashManifest(dashManifest)
                dashVideoUrl = v
                dashAudioUrl = a
            }
        }

        // Check direct post.optJSONObject("media")
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val postMedia = post.optJSONObject("media")
            if (postMedia != null) {
                renditions = extractRenditions(postMedia.optJSONArray("video_versions"))
                if (renditions.isEmpty()) {
                    val dashManifest = postMedia.optString("video_dash_manifest")
                    if (dashManifest.isNotBlank()) {
                        val (v, a) = parseDashManifest(dashManifest)
                        dashVideoUrl = v
                        dashAudioUrl = a
                    }
                }
            }
        }

        // Check text_post_app_info.media and text_post_app_info.share_info.media
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val textPostAppInfo = post.optJSONObject("text_post_app_info")
            if (textPostAppInfo != null) {
                val mediaObj = textPostAppInfo.optJSONObject("media")
                    ?: textPostAppInfo.optJSONObject("share_info")?.optJSONObject("media")
                if (mediaObj != null) {
                    renditions = extractRenditions(mediaObj.optJSONArray("video_versions"))
                    if (renditions.isEmpty()) {
                        val dashManifest = mediaObj.optString("video_dash_manifest")
                        if (dashManifest.isNotBlank()) {
                            val (v, a) = parseDashManifest(dashManifest)
                            dashVideoUrl = v
                            dashAudioUrl = a
                        }
                    }
                }
            }
        }

        // Check reposted_post (text_post_app_info.share_info.reposted_post or text_post_app_info.reposted_post)
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val reposted = post.optJSONObject("text_post_app_info")?.optJSONObject("share_info")?.optJSONObject("reposted_post")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("reposted_post")
                ?: post.optJSONObject("reposted_post")
            if (reposted != null) {
                renditions = extractRenditions(reposted.optJSONArray("video_versions"))
                if (renditions.isEmpty()) {
                    val repostedMedia = reposted.optJSONObject("media")
                        ?: reposted.optJSONObject("text_post_app_info")?.optJSONObject("media")
                    if (repostedMedia != null) {
                        renditions = extractRenditions(repostedMedia.optJSONArray("video_versions"))
                        if (renditions.isEmpty()) {
                            val dashManifest = repostedMedia.optString("video_dash_manifest")
                            if (dashManifest.isNotBlank()) {
                                val (v, a) = parseDashManifest(dashManifest)
                                dashVideoUrl = v
                                dashAudioUrl = a
                            }
                        }
                    }
                }
                if (renditions.isEmpty() && dashVideoUrl == null) {
                    val dashManifest = reposted.optString("video_dash_manifest")
                    if (dashManifest.isNotBlank()) {
                        val (v, a) = parseDashManifest(dashManifest)
                        dashVideoUrl = v
                        dashAudioUrl = a
                    }
                }
                if (renditions.isEmpty() && dashVideoUrl == null) {
                    val carousel = reposted.optJSONArray("carousel_media")
                        ?: reposted.optJSONObject("media")?.optJSONArray("carousel_media")
                    if (carousel != null && carousel.length() > 0) {
                        for (i in 0 until carousel.length()) {
                            val item = carousel.optJSONObject(i) ?: continue
                            val itemRenditions = extractRenditions(item.optJSONArray("video_versions"))
                            if (itemRenditions.isNotEmpty()) {
                                renditions = itemRenditions
                                break
                            }
                            val dash = item.optString("video_dash_manifest")
                            if (dash.isNotBlank()) {
                                val (v, a) = parseDashManifest(dash)
                                if (v != null) {
                                    dashVideoUrl = v
                                    dashAudioUrl = a
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        // Check repost_post
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val repost = post.optJSONObject("repost_post")
            if (repost != null) {
                renditions = extractRenditions(repost.optJSONArray("video_versions"))
                if (renditions.isEmpty()) {
                    val repostMedia = repost.optJSONObject("media")
                        ?: repost.optJSONObject("text_post_app_info")?.optJSONObject("media")
                    if (repostMedia != null) {
                        renditions = extractRenditions(repostMedia.optJSONArray("video_versions"))
                        if (renditions.isEmpty()) {
                            val dashManifest = repostMedia.optString("video_dash_manifest")
                            if (dashManifest.isNotBlank()) {
                                val (v, a) = parseDashManifest(dashManifest)
                                dashVideoUrl = v
                                dashAudioUrl = a
                            }
                        }
                    }
                }
                if (renditions.isEmpty() && dashVideoUrl == null) {
                    val dashManifest = repost.optString("video_dash_manifest")
                    if (dashManifest.isNotBlank()) {
                        val (v, a) = parseDashManifest(dashManifest)
                        dashVideoUrl = v
                        dashAudioUrl = a
                    }
                }
            }
        }

        // Quoted attachment post
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val quoted = post.optJSONObject("quoted_post")
                ?: post.optJSONObject("quoted_attachment_post")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("share_info")?.optJSONObject("quoted_attachment_post")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("share_info")?.optJSONObject("quoted_post")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("quoted_attachment_post")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("quoted_post")
            if (quoted != null) {
                renditions = extractRenditions(quoted.optJSONArray("video_versions"))
                if (renditions.isEmpty()) {
                    val quotedMedia = quoted.optJSONObject("media")
                    if (quotedMedia != null) {
                        renditions = extractRenditions(quotedMedia.optJSONArray("video_versions"))
                    }
                }
                if (renditions.isEmpty()) {
                    val dashManifest = quoted.optString("video_dash_manifest")
                    if (dashManifest.isNotBlank()) {
                        val (v, a) = parseDashManifest(dashManifest)
                        dashVideoUrl = v
                        dashAudioUrl = a
                    }
                }
            }
        }

        // Carousel media
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val carousel = post.optJSONArray("carousel_media")
                ?: post.optJSONObject("media")?.optJSONArray("carousel_media")
                ?: post.optJSONObject("repost_post")?.optJSONArray("carousel_media")
            if (carousel != null && carousel.length() > 0) {
                for (i in 0 until carousel.length()) {
                    val item = carousel.optJSONObject(i) ?: continue
                    val itemRenditions = extractRenditions(item.optJSONArray("video_versions"))
                    if (itemRenditions.isNotEmpty()) {
                        renditions = itemRenditions
                        break
                    }
                    val dash = item.optString("video_dash_manifest")
                    if (dash.isNotBlank()) {
                        val (v, a) = parseDashManifest(dash)
                        if (v != null) {
                            dashVideoUrl = v
                            dashAudioUrl = a
                            break
                        }
                    }
                }
            }
        }

        // Linked inline media
        if (renditions.isEmpty() && dashVideoUrl == null) {
            val linkedMedia = post.optJSONObject("text_post_app_info")?.optJSONObject("linked_inline_media")?.optJSONObject("media")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("link_preview_response")?.optJSONObject("video")
                ?: post.optJSONObject("text_post_app_info")?.optJSONObject("linked_inline_media")
            if (linkedMedia != null) {
                renditions = extractRenditions(linkedMedia.optJSONArray("video_versions"))
                if (renditions.isEmpty()) {
                    val dashManifest = linkedMedia.optString("video_dash_manifest")
                    if (dashManifest.isNotBlank()) {
                        val (v, a) = parseDashManifest(dashManifest)
                        dashVideoUrl = v
                        dashAudioUrl = a
                    }
                }
            }
        }

        val hasMedia = renditions.isNotEmpty() || (dashVideoUrl != null && dashAudioUrl != null) || (dashVideoUrl != null)
        if (!hasMedia) {
            if (isThreadsImageOrTextPost(post)) {
                return MetaExtractionResult.Failure(
                    MetaExtractionError.NoVideo("此 Threads 貼文未包含任何影片內容 (可能為純文字或純圖片貼文)")
                )
            }
            return MetaExtractionResult.Failure(
                MetaExtractionError.Technical("Threads 貼文結構未解析出有效媒體，可能為頁面版型變更")
            )
        }

        val uploader = post.optJSONObject("user")?.optString("username")?.ifBlank { "Threads User" } ?: "Threads User"
        val caption = post.optJSONObject("caption")?.optString("text")?.take(100)
        val title = if (!caption.isNullOrBlank()) caption else "Threads 影片 ($targetShortcode)"

        val thumb = extractThumbnail(post)
        val urls = renditions.map { it.url }
        val heights = renditions.map { it.resolution }.filter { it > 0 }.distinct()

        return MetaExtractionResult.Success(
            ExtractedMetaMedia(
                postId = targetShortcode,
                canonicalUrl = canonicalUrl,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumb,
                progressiveVideoUrls = urls,
                renditions = renditions,
                dashVideoUrl = dashVideoUrl,
                dashAudioUrl = dashAudioUrl,
                heights = heights
            )
        )
    }

    private fun extractThumbnail(post: JSONObject): String? {
        val candidates = post.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("media")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("text_post_app_info")?.optJSONObject("media")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("text_post_app_info")?.optJSONObject("share_info")?.optJSONObject("reposted_post")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("text_post_app_info")?.optJSONObject("share_info")?.optJSONObject("reposted_post")?.optJSONObject("media")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("text_post_app_info")?.optJSONObject("reposted_post")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("reposted_post")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
            ?: post.optJSONObject("repost_post")?.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val bestCandidate = candidates.optJSONObject(0)?.optString("url")
            if (!bestCandidate.isNullOrBlank()) return normalizeCdnUrl(bestCandidate)
        }
        return null
    }

    suspend fun fetchBarcelonaGraphQL(
        shortcode: String,
        pk: String,
        canonicalUrl: String
    ): Result<ExtractedMetaMedia> = withContext(Dispatchers.IO) {
        // 1. Page bootstrap: GET canonical target page to extract real LSD token and cookies in isolated anonymous context
        httpSession.resetAnonymousCookies()
        val bootstrapResp = httpSession.fetch(
            url = canonicalUrl,
            profile = RequestProfile.DESKTOP_NAVIGATION,
            origin = "https://www.threads.com",
            referer = "https://www.threads.com/",
            customHeaders = mapOf("X-Anonymous-Context" to "true")
        )

        val bootstrapObj = bootstrapResp.getOrNull()
        lastBootstrapResp = bootstrapObj
        val bootstrapHtml = bootstrapObj?.body ?: ""

        val lsdRegex = Regex("""\["LSD",\[\],\{"token":"([^"]+)"""")
        val lsdMatch = lsdRegex.find(bootstrapHtml) ?: Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(bootstrapHtml)
        val lsdToken = lsdMatch?.groupValues?.get(1)

        if (lsdToken.isNullOrBlank()) {
            return@withContext Result.failure(
                PlatformExtractionError.TargetNotInPageData(
                    "無法從 Threads 頁面取得必要之 LSD 憑證",
                    internalReason = "THREADS_LSD_NOT_FOUND"
                )
            )
        }

        // Check for csrf token in isolated anonymous cookie jar or bootstrap response
        var csrfToken: String? = null
        val setCookieHeader = bootstrapObj?.headers?.entries?.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        if (setCookieHeader != null) {
            val csrfMatch = Regex("""csrftoken=([a-zA-Z0-9_-]+)""").find(setCookieHeader)
            if (csrfMatch != null) csrfToken = csrfMatch.groupValues[1]
        }
        if (csrfToken.isNullOrBlank()) {
            csrfToken = httpSession.anonymousCookieJar.getCookieValue("threads.com", "csrftoken")
        }

        // 2. POST BarcelonaPostPageContentQuery GraphQL
        val endpoint = "https://www.threads.com/api/graphql"
        val variables = JSONObject().apply {
            put("postID", pk)
        }.toString()

        val postData = "av=0&__user=0&__a=1&__req=1&dpr=1&lsd=${URLEncoder.encode(lsdToken, "UTF-8")}&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=BarcelonaPostPageContentQuery&variables=${URLEncoder.encode(variables, "UTF-8")}&server_timestamps=true&doc_id=$BARCELONA_DOC_ID"

        val headers = mutableMapOf(
            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-IG-App-ID" to THREADS_APP_ID,
            "X-ASBD-ID" to "129477",
            "X-FB-LSD" to lsdToken,
            "X-FB-Friendly-Name" to "BarcelonaPostPageContentQuery",
            "Origin" to "https://www.threads.com",
            "Referer" to canonicalUrl,
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "X-Anonymous-Context" to "true"
        )
        if (!csrfToken.isNullOrBlank()) {
            headers["X-CSRFToken"] = csrfToken
        }

        val respResult = httpSession.fetch(
            url = endpoint,
            profile = RequestProfile.API,
            origin = "https://www.threads.com",
            referer = canonicalUrl,
            customHeaders = headers,
            body = postData.toByteArray(Charsets.UTF_8),
            contentType = "application/x-www-form-urlencoded",
            method = "POST"
        )

        val resp = respResult.getOrElse {
            return@withContext Result.failure(it)
        }

        if (resp.code == 429) {
            return@withContext Result.failure(
                PlatformExtractionError.RateLimited("Threads 存取頻率受限 (HTTP 429)，請稍候再試")
            )
        }

        if (resp.code !in 200..299) {
            return@withContext Result.failure(
                PlatformExtractionError.ApiError(resp.code, "Threads GraphQL 回傳錯誤碼 ${resp.code}", internalReason = "BARCELONA_HTTP_${resp.code}")
            )
        }

        try {
            val cleanBody = resp.body.trimStart().removePrefix("for (;;);").trimStart()
            val json = JSONObject(cleanBody)

            // Classification 1: Top-level error
            if (json.has("error") && !json.isNull("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message")?.ifBlank { null }
                    ?: json.optString("error").ifBlank { null }
                    ?: "Threads GraphQL 回傳錯誤"
                return@withContext Result.failure(
                    PlatformExtractionError.ApiError(
                        httpCode = resp.code,
                        detail = "Threads GraphQL API error: $errMsg",
                        internalReason = "BARCELONA_API_ERROR"
                    )
                )
            }

            // Classification 2: Reference-backed unavailable/restricted Relay response (response.data is null/missing)
            val isDataNull = !json.has("data") || json.isNull("data")
            if (isDataNull) {
                val errorsArr = json.optJSONArray("errors")
                val detailMsg = errorsArr?.optJSONObject(0)?.optString("message")?.ifBlank { null } ?: "no details"
                return@withContext Result.failure(
                    PlatformExtractionError.TargetNotInPageData(
                        detail = "Threads GraphQL 回應貼文受限或不存在 ($detailMsg)",
                        internalReason = "THREADS_AUTH_FALLBACK_ELIGIBLE"
                    )
                )
            }

            // Classification 4: Non-null data, search target post
            val matchingPost = findTargetPostInGraphQL(json, shortcode, pk)
                ?: return@withContext Result.failure(
                    PlatformExtractionError.TargetNotInPageData(
                        detail = "Threads GraphQL 回應中未找到目標貼文 ($shortcode / $pk)",
                        internalReason = "BARCELONA_TARGET_NOT_FOUND"
                    )
                )

            val parseRes = extractMediaFromPost(matchingPost, shortcode, canonicalUrl)
            if (parseRes is MetaExtractionResult.Success) {
                Result.success(parseRes.media)
            } else if (parseRes is MetaExtractionResult.Failure && parseRes.error is MetaExtractionError.NoVideo) {
                Result.failure(PlatformExtractionError.NoVideo(parseRes.error.userMessage, internalReason = "BARCELONA_NO_VIDEO"))
            } else {
                Result.failure(PlatformExtractionError.MediaUrlUnsupported("未找到相容的影片串流格式", internalReason = "BARCELONA_NO_COMPATIBLE_VIDEO"))
            }
        } catch (e: Exception) {
            Result.failure(PlatformExtractionError.ParseError("無法解析 Threads GraphQL 回應", cause = e))
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        lastDiagnosticFingerprint = null
        lastProfileSequence = emptyList()
        lastBootstrapResp = null

        val isShareInput = url.contains("/share/")
        val resolvedUrl = resolveShareUrl(url)
        val shareResolved = isShareInput && resolvedUrl != url && !resolvedUrl.contains("/share/")
        val shortcode = extractPostId(resolvedUrl)
            ?: return@withContext Result.failure(
                PlatformExtractionError.PageVariantUnsupported("無法從網址中解析 Threads 貼文代碼，請確認網址格式")
            )

        val canonicalUrl = normalizeUrl(resolvedUrl)
        val profileSteps = mutableListOf<String>()
        val diagnosticHistory = mutableListOf<String>()

        safeLog("[Resolver] platform=THREADS")
        safeLog("[Threads] is_share_input=$isShareInput share_resolved=$shareResolved")
        safeLog("[Threads] target_code=$shortcode")

        fun recordStepDiagnostics(
            stepName: String,
            resp: Result<HttpResponse>,
            html: String,
            outcome: ThreadsParseOutcome?
        ) {
            val respObj = resp.getOrNull()
            val httpCode = respObj?.code ?: 0
            val contentType = respObj?.getHeader("content-type") ?: "text/html"
            val bodyBytes = html.toByteArray().size
            val sizeBucket = formatSizeBucket(bodyBytes)
            val finalUrl = respObj?.finalUrl ?: canonicalUrl
            val cleanPath = cleanHostAndPath(finalUrl)
            val redirect = if (respObj?.finalUrl != null && respObj.finalUrl != canonicalUrl) "yes" else "no"

            val stage: String
            val errorClassName: String
            val scriptSource: String
            val scriptCount: Int
            val rawCode: Boolean
            val decodedCode: Boolean
            val targetWrapper: Boolean
            val mediaNode: Boolean
            val matchedKeysStr: String

            if (outcome != null) {
                val diag = outcome.diagnostics
                stage = diag.stage
                errorClassName = when (outcome.result) {
                    is MetaExtractionResult.Success -> "NONE"
                    is MetaExtractionResult.Failure -> outcome.result.error.javaClass.simpleName
                }
                scriptSource = diag.scriptSource
                scriptCount = diag.scriptCount
                rawCode = diag.rawCodeInHtml
                decodedCode = diag.decodedCodeInHtml
                targetWrapper = diag.targetWrapperFound
                mediaNode = diag.mediaNodeFound
                matchedKeysStr = if (diag.matchedContainerKeys.isNotEmpty()) diag.matchedContainerKeys.joinToString(",") else "none"
            } else {
                stage = if (resp.isFailure) "FETCH_FAILED" else "EMPTY_BODY"
                errorClassName = if (resp.isFailure) {
                    resp.exceptionOrNull()?.javaClass?.simpleName ?: "IOException"
                } else {
                    "EMPTY_BODY"
                }
                scriptSource = "none"
                scriptCount = 0
                rawCode = false
                decodedCode = false
                targetWrapper = false
                mediaNode = false
                matchedKeysStr = "none"
            }

            val stepFp = """
                [profile=$stepName http_status=$httpCode content_type=$contentType body_size=$sizeBucket host_and_path=$cleanPath redirect=$redirect stage=$stage error=$errorClassName]
                share: input=$isShareInput resolved=$shareResolved
                scripts: source=$scriptSource count=$scriptCount
                target: raw_code=$rawCode decoded_code=$decodedCode wrapper=$targetWrapper media_node=$mediaNode
                matched_keys: $matchedKeysStr
            """.trimIndent()
            diagnosticHistory.add(stepFp)
            lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
        }

        var successfulMedia: ExtractedMetaMedia? = null

        // 1. Primary anonymous path: BarcelonaPostPageContentQuery GraphQL
        val targetPk = shortcodeToPk(shortcode)
        profileSteps.add("BARCELONA_GRAPHQL")
        safeLog("[Threads] attempting BarcelonaPostPageContentQuery GraphQL for shortcode=$shortcode pk=$targetPk")
        val gqlResult = fetchBarcelonaGraphQL(shortcode, targetPk, canonicalUrl)
        if (gqlResult.isSuccess) {
            safeLog("[Threads] Barcelona GraphQL success")
            successfulMedia = gqlResult.getOrThrow()
            diagnosticHistory.add("[profile=BARCELONA_GRAPHQL http_status=200 stage=SUCCESS error=NONE]")
            lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
        } else {
            val gqlErr = gqlResult.exceptionOrNull() as? PlatformExtractionError
            safeLog("[Threads] Barcelona GraphQL failed: ${gqlErr?.message}")
            val reasonStr = gqlErr?.internalReason?.let { " reason=$it" } ?: ""
            diagnosticHistory.add("[profile=BARCELONA_GRAPHQL stage=FAILURE error=${gqlErr?.javaClass?.simpleName}$reasonStr]")
            lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
            if (gqlErr is PlatformExtractionError.RateLimited) {
                lastProfileSequence = profileSteps
                return@withContext Result.failure(gqlErr)
            } else if (gqlErr is PlatformExtractionError.NoVideo) {
                lastProfileSequence = profileSteps
                return@withContext Result.failure(gqlErr)
            }
        }

        val gqlErr = gqlResult.exceptionOrNull() as? PlatformExtractionError
        val isAuthFallbackEligible = (gqlErr?.internalReason == "THREADS_AUTH_FALLBACK_ELIGIBLE")
        val isTargetNotFound = (gqlErr?.internalReason == "BARCELONA_TARGET_NOT_FOUND")
        val canAttemptAuthRelay = (isAuthFallbackEligible || isTargetNotFound) && httpSession.sessionProvider.hasAuthenticatedSession(Platform.THREADS)

        // 2. Authenticated Relay fallback ONLY if auth-fallback eligible or target not found AND active session exists
        if (successfulMedia == null && canAttemptAuthRelay) {
            profileSteps.add("AUTHENTICATED_RELAY")
            safeLog("[Threads] authenticated session available and post is restricted/target not found in anonymous GraphQL, attempting authenticated page fetch fallback")
            httpSession.syncSessionCookies(Platform.THREADS)
            val authResp = httpSession.fetch(canonicalUrl, RequestProfile.DESKTOP_NAVIGATION)
            val authRespObj = authResp.getOrNull()
            val authCode = authRespObj?.code ?: 0
            if (authCode == 401 || authCode == 403) {
                httpSession.sessionProvider.markExpired(Platform.THREADS, "HTTP $authCode")
                lastProfileSequence = profileSteps
                return@withContext Result.failure(
                    PlatformExtractionError.SessionExpired(
                        "Threads 登入狀態已失效（HTTP $authCode），請至設定重新匯入 Session",
                        internalReason = "THREADS_AUTH_EXPIRED:$authCode"
                    )
                )
            }
            val authHtml = authRespObj?.body ?: ""
            if (authHtml.isNotBlank()) {
                val authOutcome = parseThreadsPageWithDiagnostics(authHtml, shortcode, canonicalUrl, resolvedShare = shareResolved)
                recordStepDiagnostics("AUTHENTICATED_RELAY", authResp, authHtml, authOutcome)
                if (authOutcome.result is MetaExtractionResult.Success) {
                    safeLog("[Threads] authenticated relay success")
                    successfulMedia = authOutcome.result.media
                } else {
                    val authErr = authOutcome.result as? MetaExtractionResult.Failure
                    if (authErr?.error is MetaExtractionError.NoVideo) {
                        lastProfileSequence = profileSteps
                        return@withContext Result.failure(authErr.error)
                    }
                }
            } else {
                recordStepDiagnostics("AUTHENTICATED_RELAY", authResp, authHtml, outcome = null)
            }
        }

        // 3. Fallback: Desktop / Mobile / Crawler HTML navigation profiles
        if (successfulMedia == null) {
            // Step 1: DESKTOP_NAVIGATION
            profileSteps.add("DESKTOP")
            val cachedResp = lastBootstrapResp
            lastBootstrapResp = null
            val desktopResp = if (cachedResp != null) {
                Result.success(cachedResp)
            } else {
                httpSession.fetch(canonicalUrl, RequestProfile.DESKTOP_NAVIGATION, customHeaders = mapOf("X-Anonymous-Context" to "true"))
            }
            val desktopHtml = desktopResp.getOrNull()?.body ?: ""

            var parseOutcome: ThreadsParseOutcome? = null
            if (desktopHtml.isNotBlank()) {
                parseOutcome = parseThreadsPageWithDiagnostics(desktopHtml, shortcode, canonicalUrl, resolvedShare = shareResolved)
                recordStepDiagnostics("DESKTOP", desktopResp, desktopHtml, parseOutcome)
            } else {
                recordStepDiagnostics("DESKTOP", desktopResp, desktopHtml, outcome = null)
            }

            // Step 2: Escalation to MOBILE_NAVIGATION on technical failure or empty body/fetch failure
            val needEscalateToMobile = parseOutcome == null ||
                (parseOutcome.result is MetaExtractionResult.Failure && parseOutcome.result.error is MetaExtractionError.Technical)

            if (needEscalateToMobile) {
                profileSteps.add("MOBILE")
                val mobileResp = httpSession.fetch(canonicalUrl, RequestProfile.MOBILE_NAVIGATION, customHeaders = mapOf("X-Anonymous-Context" to "true"))
                val mobileHtml = mobileResp.getOrNull()?.body ?: ""
                if (mobileHtml.isNotBlank()) {
                    val mobileOutcome = parseThreadsPageWithDiagnostics(mobileHtml, shortcode, canonicalUrl, resolvedShare = shareResolved)
                    parseOutcome = mobileOutcome
                    recordStepDiagnostics("MOBILE", mobileResp, mobileHtml, mobileOutcome)
                } else {
                    recordStepDiagnostics("MOBILE", mobileResp, mobileHtml, outcome = null)
                }
            }

            // Step 3: Escalation to CRAWLER_NAVIGATION if still technical failure or empty body/fetch failure
            val needEscalateToCrawler = parseOutcome == null ||
                (parseOutcome.result is MetaExtractionResult.Failure && parseOutcome.result.error is MetaExtractionError.Technical)

            if (needEscalateToCrawler) {
                profileSteps.add("CRAWLER")
                val crawlerResp = httpSession.fetch(canonicalUrl, RequestProfile.CRAWLER_NAVIGATION, customHeaders = mapOf("X-Anonymous-Context" to "true"))
                val crawlerHtml = crawlerResp.getOrNull()?.body ?: ""
                if (crawlerHtml.isNotBlank()) {
                    val crawlerOutcome = parseThreadsPageWithDiagnostics(crawlerHtml, shortcode, canonicalUrl, resolvedShare = shareResolved)
                    parseOutcome = crawlerOutcome
                    recordStepDiagnostics("CRAWLER", crawlerResp, crawlerHtml, crawlerOutcome)
                } else {
                    recordStepDiagnostics("CRAWLER", crawlerResp, crawlerHtml, outcome = null)
                }
            }

            if (parseOutcome != null && parseOutcome.result is MetaExtractionResult.Success) {
                successfulMedia = parseOutcome.result.media
            } else if (parseOutcome != null && parseOutcome.result is MetaExtractionResult.Failure) {
                val err = parseOutcome.result.error
                if (err is MetaExtractionError.NoVideo) {
                    safeLog("[Threads] final=NO_VIDEO")
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(err)
                }
                if (isAuthFallbackEligible) {
                    safeLog("[Threads] final=UNAVAILABLE_OR_RESTRICTED")
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(
                        MetaExtractionError.Restricted(
                            reason = RestrictionReason.DELETED_OR_PRIVATE,
                            userMessage = "Threads reports this post as unavailable; it may be deleted or require login. If it opens in your browser, import a Threads session. (Threads 回報此貼文無法存取，可能已刪除或需要登入；若在瀏覽器中可正常開啟，請至設定匯入 Threads Session)",
                            internalReason = "THREADS_UNAVAILABLE_OR_RESTRICTED"
                        )
                    )
                }
                val finalStatus = "PARSE_ERROR"
                safeLog("[Threads] final=$finalStatus")
                lastProfileSequence = profileSteps
                return@withContext Result.failure(err)
            } else {
                if (isAuthFallbackEligible) {
                    safeLog("[Threads] final=UNAVAILABLE_OR_RESTRICTED")
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(
                        MetaExtractionError.Restricted(
                            reason = RestrictionReason.DELETED_OR_PRIVATE,
                            userMessage = "Threads reports this post as unavailable; it may be deleted or require login. If it opens in your browser, import a Threads session. (Threads 回報此貼文無法存取，可能已刪除或需要登入；若在瀏覽器中可正常開啟，請至設定匯入 Threads Session)",
                            internalReason = "THREADS_UNAVAILABLE_OR_RESTRICTED"
                        )
                    )
                }
                safeLog("[Threads] final=PARSE_ERROR")
                lastProfileSequence = profileSteps
                return@withContext Result.failure(
                    MetaExtractionError.Technical("無法取得 Threads 頁面內容", internalReason = "EMPTY_BODY")
                )
            }
        }

        lastProfileSequence = profileSteps

        safeLog("[Threads] final=SUCCESS")
        val media = successfulMedia!!
        val options = buildQualityOptions(media)
        val info = MediaInfo(
            sourceUrl = canonicalUrl,
            title = media.title,
            platform = Platform.THREADS,
            extractor = ENGINE_NAME,
            thumbnailUrl = media.thumbnailUrl,
            durationSeconds = media.durationSeconds,
            qualityOptions = options
        )
        return@withContext Result.success(info)
    }

    private fun safeLog(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }

    private fun buildQualityOptions(media: ExtractedMetaMedia): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        val renditions = media.renditions.sortedByDescending { it.resolution }

        val bestProgressive = renditions.firstOrNull()
        if (bestProgressive != null) {
            options.add(
                QualityOption(
                    id = "best",
                    label = "最佳畫質 (推薦)",
                    formatSelector = bestProgressive.url
                )
            )
        } else if (media.dashVideoUrl != null) {
            val dashSelector = if (media.dashAudioUrl != null) {
                "${media.dashVideoUrl}|${media.dashAudioUrl}"
            } else {
                media.dashVideoUrl
            }
            options.add(
                QualityOption(
                    id = "best_dash",
                    label = "最佳畫質 (DASH)",
                    formatSelector = dashSelector
                )
            )
        }

        for (rendition in renditions) {
            val res = rendition.resolution
            if (res > 0) {
                val id = "${res}p"
                if (options.none { it.id == id }) {
                    options.add(QualityOption(id = id, label = "${res}p", formatSelector = rendition.url))
                }
            }
        }

        val distinctOptions = options.distinctBy { it.id }.toMutableList()

        // Audio-only option
        val audioSource = media.dashAudioUrl ?: bestProgressive?.url
        if (audioSource != null) {
            distinctOptions.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅下載音訊 (MP3)",
                    formatSelector = "audio:$audioSource",
                    isAudioOnly = true
                )
            )
        }

        return distinctOptions
    }

    override suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val format = request.qualityOption.formatSelector
        val isAudioOnly = request.qualityOption.isAudioOnly || format.startsWith("audio:")
        val cleanFormat = format.removePrefix("audio:")

        val sanitizedTitle = request.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        val finalFileName = if (isAudioOnly) "$sanitizedTitle.mp3" else "$sanitizedTitle.mp4"
        val destinationFile = File(destDir, finalFileName)

        onStatus("正在透過原生 Threads 引擎下載串流...")

        if (cleanFormat.contains("|")) {
            // DASH stream: video + audio
            val parts = cleanFormat.split("|")
            val videoUrl = parts[0]
            val audioUrl = parts.getOrNull(1)

            val tempVideo = File(destDir, "${sanitizedTitle}_v_${UUID.randomUUID().toString().take(6)}.mp4")
            val tempAudio = File(destDir, "${sanitizedTitle}_a_${UUID.randomUUID().toString().take(6)}.m4a")

            onStatus("正在下載 DASH 視訊串流...")
            val videoOk = httpSession.downloadMediaStream(
                streamUrl = videoUrl,
                destination = tempVideo,
                referer = "https://www.threads.net/",
                origin = "https://www.threads.net",
                onProgress = onProgress,
                isCancelled = { isCancelled.get() }
            )
            if (isCancelled.get()) {
                tempVideo.delete()
                tempAudio.delete()
                return@withContext Result.failure(InterruptedException("下載已取消"))
            }
            if (!videoOk) {
                tempVideo.delete()
                tempAudio.delete()
                return@withContext Result.failure(PlatformExtractionError.NetworkError("下載 DASH 視訊串流失敗"))
            }

            if (audioUrl != null) {
                onStatus("正在下載 DASH 音訊串流...")
                val audioOk = httpSession.downloadMediaStream(
                    streamUrl = audioUrl,
                    destination = tempAudio,
                    referer = "https://www.threads.net/",
                    origin = "https://www.threads.net",
                    onProgress = { _, _, _ -> },
                    isCancelled = { isCancelled.get() }
                )
                if (isCancelled.get()) {
                    tempVideo.delete()
                    tempAudio.delete()
                    return@withContext Result.failure(InterruptedException("下載已取消"))
                }
                if (!audioOk) {
                    tempVideo.delete()
                    tempAudio.delete()
                    return@withContext Result.failure(PlatformExtractionError.NetworkError("下載 DASH 音訊串流失敗"))
                }

                if (isAudioOnly) {
                    onStatus("正在轉檔音訊為 MP3...")
                    val converted = MetaFfmpegHelper.extractAudio(context, tempAudio, destinationFile)
                    tempVideo.delete()
                    tempAudio.delete()
                    if (!converted) return@withContext Result.failure(PlatformExtractionError.FfmpegError("FFmpeg 音訊轉檔失敗"))
                    return@withContext Result.success(destinationFile)
                }

                onStatus("正在合成視訊與音訊串流...")
                val merged = MetaFfmpegHelper.mergeVideoAndAudio(context, tempVideo, tempAudio, destinationFile)
                tempVideo.delete()
                tempAudio.delete()
                if (!merged) return@withContext Result.failure(PlatformExtractionError.FfmpegError("FFmpeg 串流合成失敗"))
                return@withContext Result.success(destinationFile)
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                val renamed = tempVideo.renameTo(destinationFile)
                if (renamed && destinationFile.exists()) {
                    return@withContext Result.success(destinationFile)
                } else {
                    val copied = try {
                        tempVideo.copyTo(destinationFile, overwrite = true)
                        tempVideo.delete()
                        destinationFile.exists()
                    } catch (_: Exception) {
                        false
                    }
                    if (copied) {
                        return@withContext Result.success(destinationFile)
                    } else {
                        tempVideo.delete()
                        return@withContext Result.failure(PlatformExtractionError.StorageError("無法將暫存視訊儲存至目的檔案"))
                    }
                }
            }
        } else {
            // Progressive stream
            val downloadTarget = if (isAudioOnly) {
                File(destDir, "$sanitizedTitle.source.mp4")
            } else {
                destinationFile
            }

            val success = httpSession.downloadMediaStream(
                streamUrl = cleanFormat,
                destination = downloadTarget,
                referer = "https://www.threads.net/",
                origin = "https://www.threads.net",
                onProgress = onProgress,
                isCancelled = { isCancelled.get() }
            )

            if (!success) {
                if (isCancelled.get()) {
                    downloadTarget.delete()
                    return@withContext Result.failure(InterruptedException("下載已取消"))
                }
                return@withContext Result.failure(PlatformExtractionError.NetworkError("原生串流下載失敗"))
            }

            if (isAudioOnly) {
                onStatus("正在轉檔音訊為 MP3...")
                val converted = MetaFfmpegHelper.extractAudio(context, downloadTarget, destinationFile)
                downloadTarget.delete()
                if (!converted) {
                    return@withContext Result.failure(PlatformExtractionError.FfmpegError("FFmpeg 音訊轉檔失敗"))
                }
            }

            return@withContext Result.success(destinationFile)
        }
    }
}
