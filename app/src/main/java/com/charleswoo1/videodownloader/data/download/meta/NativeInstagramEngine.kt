package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.PlatformMediaEngine
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Native Instagram extraction and download engine V2.
 *
 * Implements reference-driven extraction ported from 2Xsave/insave (MIT):
 * - Coherent desktop/mobile/crawler request profile escalation
 * - Robust URL normalization (escaped slashes, unicode escapes, percent decoding)
 * - Meta CDN video path acceptance without requiring .mp4 suffix
 * - Support for xdt_api__v1__media__shortcode__web_info and data.media
 * - Strict target-media isolation via shortcode and numeric media ID
 */
class NativeInstagramEngine(
    private val context: Context? = null,
    private val httpSession: PlatformHttpSession = PlatformHttpSession()
) : PlatformMediaEngine {

    companion object {
        private const val TAG = "NativeInstagramEngine"
        const val ENGINE_NAME = "NativeInstagramEngine"

        private val INSTAGRAM_URL_PATTERN = Pattern.compile("""/(p|reel|reels|tv|share/p)/([A-Za-z0-9_-]+)""")
        private val SCRIPT_JSON_PATTERN = Pattern.compile(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val SHARED_DATA_PATTERN = Pattern.compile(
            """<script[^>]*>(?:window\._sharedData|window\.__additionalDataLoaded)\s*=\s*(.*?);</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val LD_JSON_PATTERN = Pattern.compile(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
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

        val INSTAGRAM_MARKERS = listOf(
            "xdt_shortcode_media",
            "xdt_api__v1__media__shortcode__web_info",
            "xdt_api__v1__clips__clips__web_info",
            "xdt_api__v1__clips_home__web_info",
            "if_not_gated_logged_out",
            "video_versions",
            "video_url",
            "carousel_media",
            "image_versions2",
            "media_type",
            "is_video"
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
                "relatedPosts", "related_posts", "replies",
                "replyThreads", "reply_threads", "parentPost", "parent_post", "suggested_users",
                "edge_media_to_comment", "edge_related_profiles", "edge_owner_to_timeline_media"
            )
        }

        fun collectJsonFromScript(
            raw: String,
            candidates: MutableList<JSONObject>,
            maxDepth: Int = 3,
            targetMarker: String? = null
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
            extractBalancedJsonPayloads(trimmed, candidates, maxDepth, targetMarker)
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
            targetMarker: String? = null
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
                                    if (targetMarker == null || candidateStr.contains(targetMarker)) {
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

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        const val POLARIS_DOC_ID = "27130156389949648" // Upstream yt-dlp PolarisLoggedOutDesktopWWWPostRootContentQuery doc_id
        const val INSTAGRAM_APP_ID = "936619743392459"

        fun shortcodeToMediaId(shortcode: String): String {
            val clean = if (shortcode.length > 28) shortcode.dropLast(28) else shortcode
            var pk = java.math.BigInteger.ZERO
            val base = java.math.BigInteger.valueOf(64)
            for (char in clean) {
                val index = ALPHABET.indexOf(char)
                if (index == -1) continue
                pk = pk.multiply(base).add(java.math.BigInteger.valueOf(index.toLong()))
            }
            return pk.toString()
        }

        fun shortcodeToId(shortcode: String): Long {
            val clean = if (shortcode.length > 28) shortcode.dropLast(28) else shortcode
            var id = 0L
            for (char in clean) {
                val index = ALPHABET.indexOf(char)
                if (index == -1) continue
                id = (id shl 6) or index.toLong()
            }
            return id
        }

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

        /**
         * Checks whether a URL represents a valid Meta video stream,
         * without strictly requiring an explicit .mp4 extension.
         */
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

    var cachedLsdToken: String? = null

    override fun supports(platform: Platform): Boolean = platform == Platform.INSTAGRAM

    override fun cancelDownload() {
        isCancelled.set(true)
    }

    fun extractShortcode(url: String): String? {
        val matcher = INSTAGRAM_URL_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(2) else null
    }

    fun extractContentType(url: String): String? {
        val matcher = INSTAGRAM_URL_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun normalizeUrl(rawUrl: String): String {
        val clean = rawUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        val matcher = INSTAGRAM_URL_PATTERN.matcher(clean)
        if (matcher.find()) {
            val rawType = matcher.group(1)
            val shortcode = matcher.group(2)
            val normalizedType = when (rawType) {
                "p", "share/p" -> "p"
                "tv" -> "tv"
                "reel", "reels" -> "reel"
                else -> "reel"
            }
            return "https://www.instagram.com/$normalizedType/$shortcode/"
        }
        return clean
    }

    /**
     * Builds the fetch URL preserving essential query parameters (such as signed security tokens like `stkn`),
     * while normalizing the host and path structure.
     */
    fun buildFetchUrl(rawUrl: String): String {
        val canonical = normalizeUrl(rawUrl)
        val query = rawUrl.substringAfter('?', missingDelimiterValue = "").substringBefore('#').trim()
        return if (query.isNotBlank()) "$canonical?$query" else canonical
    }

    data class InstagramDiagnostics(
        val appJsonCount: Int = 0,
        val dataSjsCount: Int = 0,
        val genericScriptCount: Int = 0,
        val rawShortcodeSeen: Boolean = false,
        val decodedShortcodeSeen: Boolean = false,
        val targetWrapperSeen: Boolean = false,
        val validatedMediaNodeFound: Boolean = false,
        val matchedKeys: List<String> = emptyList(),
        val presentMarkers: List<String> = emptyList(),
        val restrictionPhrase: String = "NONE",
        val stage: String = "INIT"
    )

    data class InstagramParseOutcome(
        val result: MetaExtractionResult,
        val diagnostics: InstagramDiagnostics
    )

    fun parseInstagramPage(html: String, targetShortcode: String, canonicalUrl: String): MetaExtractionResult {
        return parseInstagramPageWithDiagnostics(html, targetShortcode, canonicalUrl).result
    }

    fun parseInstagramPageWithDiagnostics(
        html: String,
        targetShortcode: String,
        canonicalUrl: String
    ): InstagramParseOutcome {
        val rawShortcodeSeen = html.contains(targetShortcode)
        val presentMarkers = INSTAGRAM_MARKERS.filter { html.contains(it) }

        fun countMatches(p: Pattern): Int {
            val m = p.matcher(html)
            var c = 0
            while (m.find()) c++
            return c
        }
        val appJsonCount = countMatches(SCRIPT_JSON_PATTERN)
        val dataSjsCount = countMatches(DATA_SJS_PATTERN)
        val genericScriptCount = countMatches(GENERIC_SCRIPT_PATTERN)

        val baseDiag = InstagramDiagnostics(
            appJsonCount = appJsonCount,
            dataSjsCount = dataSjsCount,
            genericScriptCount = genericScriptCount,
            rawShortcodeSeen = rawShortcodeSeen,
            presentMarkers = presentMarkers
        )

        // 1. Check for audience/content restriction
        if (html.contains("This content isn't available to everyone", ignoreCase = true) ||
            html.contains("It can't be seen by certain audiences", ignoreCase = true)
        ) {
            return InstagramParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Restricted(
                        RestrictionReason.AUDIENCE_RESTRICTED,
                        "此 Instagram 內容限制部分使用者觀看，匿名模式無法存取。"
                    )
                ),
                baseDiag.copy(restrictionPhrase = "AUDIENCE_RESTRICTED", stage = "RESTRICTION_CHECK")
            )
        }

        // 2. Check for deleted/private post
        if (html.contains("Sorry, this page isn't available.", ignoreCase = true) ||
            html.contains("The link you followed may be broken, or the page may have been removed.", ignoreCase = true)
        ) {
            return InstagramParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Restricted(
                        RestrictionReason.DELETED_OR_PRIVATE,
                        "Instagram 貼文不存在、設為私人內容或已被刪除"
                    )
                ),
                baseDiag.copy(restrictionPhrase = "DELETED_OR_PRIVATE", stage = "DELETED_CHECK")
            )
        }

        // 3. Scan script tags for JSON payloads (application/json, _sharedData, data-sjs, generic scripts)
        val candidates = mutableListOf<JSONObject>()

        fun scanScriptPatterns(pattern: Pattern, targetMarker: String? = null) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val raw = matcher.group(1)?.trim() ?: continue
                collectJsonFromScript(raw, candidates, maxDepth = 3, targetMarker = targetMarker)
            }
        }

        scanScriptPatterns(SCRIPT_JSON_PATTERN)
        scanScriptPatterns(SHARED_DATA_PATTERN)
        scanScriptPatterns(DATA_SJS_PATTERN, targetMarker = targetShortcode)
        scanScriptPatterns(GENERIC_SCRIPT_PATTERN, targetMarker = targetShortcode)

        val decodedShortcodeSeen = candidates.any { it.toString().contains(targetShortcode) }
        val diagWithDecode = baseDiag.copy(decodedShortcodeSeen = decodedShortcodeSeen)

        val targetId = shortcodeToId(targetShortcode)

        // 4. Primary: inspect xdt endpoints (media shortcode web info, clips web info, shortcode media)
        for (candidate in candidates) {
            val xdtMedia = findXdtMediaItem(candidate, targetShortcode, targetId)
            if (xdtMedia != null) {
                val keys = xdtMedia.keys().asSequence().toList().sorted()
                val extracted = extractFromMediaObject(xdtMedia, targetShortcode, canonicalUrl)
                if (extracted != null) {
                    return InstagramParseOutcome(
                        extracted,
                        diagWithDecode.copy(
                            targetWrapperSeen = true,
                            validatedMediaNodeFound = true,
                            matchedKeys = keys,
                            stage = "XDT_MEDIA"
                        )
                    )
                }
            }
        }

        // 5. Secondary: deep inspect data.media or shortcode-matching objects
        var matchedMediaNode: JSONObject? = null
        var targetWrapperSeen = false
        for (candidate in candidates) {
            val search = findMediaWithShortcode(candidate, targetShortcode, targetId)
            if (search.wrapperSeen) targetWrapperSeen = true
            if (search.mediaNode != null) {
                matchedMediaNode = search.mediaNode
                break
            }
        }

        if (matchedMediaNode != null) {
            val keys = matchedMediaNode.keys().asSequence().toList().sorted()
            val extracted = extractFromMediaObject(matchedMediaNode, targetShortcode, canonicalUrl)
            if (extracted != null) {
                return InstagramParseOutcome(
                    extracted,
                    diagWithDecode.copy(
                        targetWrapperSeen = true,
                        validatedMediaNodeFound = true,
                        matchedKeys = keys,
                        stage = "SHORTCODE_SEARCH"
                    )
                )
            }

            // Only return terminal NoVideo if target media node is positively verified to have no video
            if (isValidImageOnlyNode(matchedMediaNode)) {
                return InstagramParseOutcome(
                    MetaExtractionResult.Failure(
                        MetaExtractionError.NoVideo("此 Instagram 貼文未包含任何影片 (可能為純圖片貼文)")
                    ),
                    diagWithDecode.copy(
                        targetWrapperSeen = true,
                        validatedMediaNodeFound = true,
                        matchedKeys = keys,
                        stage = "NO_VIDEO_VALIDATED"
                    )
                )
            }

            return InstagramParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Technical(
                        "Target shortcode $targetShortcode media node found but lacks recognized video structure or explicit photo type"
                    )
                ),
                diagWithDecode.copy(
                    targetWrapperSeen = true,
                    validatedMediaNodeFound = false,
                    matchedKeys = keys,
                    stage = "MEDIA_NODE_LACKS_VIDEO"
                )
            )
        }

        // If target wrapper was found but no validated media node resolved, do NOT return NoVideo
        if (targetWrapperSeen) {
            return InstagramParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Technical(
                        "Target shortcode $targetShortcode wrapper found in page data, but no validated media node resolved"
                    )
                ),
                diagWithDecode.copy(
                    targetWrapperSeen = true,
                    validatedMediaNodeFound = false,
                    stage = "WRAPPER_SEEN_NO_MEDIA"
                )
            )
        }

        // 6. Schema.org VideoObject fallback
        val ldMatcher = LD_JSON_PATTERN.matcher(html)
        while (ldMatcher.find()) {
            val raw = ldMatcher.group(1)?.trim() ?: continue
            try {
                if (raw.startsWith("{")) {
                    val ld = JSONObject(raw)
                    if (ld.optString("@type") == "VideoObject") {
                        val candidateIdentifiers = listOf(
                            ld.optString("url"),
                            ld.optString("contentUrl"),
                            ld.optString("embedUrl"),
                            ld.optString("@id")
                        )
                        val belongsToTarget = candidateIdentifiers.any { identifier ->
                            identifier.isNotBlank() && (
                                identifier.contains(targetShortcode) ||
                                (targetId > 0 && identifier.contains(targetId.toString()))
                            )
                        }
                        if (!belongsToTarget) {
                            continue
                        }

                        val contentUrl = normalizeCdnUrl(ld.optString("contentUrl"))
                        if (contentUrl.isNotBlank() && isMetaVideoUrl(contentUrl)) {
                            val title = ld.optString("name").ifBlank {
                                ld.optString("description").ifBlank { "Instagram 影片 ($targetShortcode)" }
                            }
                            val thumb = ld.optString("thumbnailUrl").ifBlank { null }?.let { normalizeCdnUrl(it) }
                            val res = MetaExtractionResult.Success(
                                ExtractedMetaMedia(
                                    postId = targetShortcode,
                                    canonicalUrl = canonicalUrl,
                                    title = title,
                                    uploader = "Instagram",
                                    thumbnailUrl = thumb,
                                    progressiveVideoUrls = listOf(contentUrl),
                                    renditions = listOf(NativeMediaRendition(url = contentUrl, width = 0, height = 1080)),
                                    heights = listOf(1080)
                                )
                            )
                            return InstagramParseOutcome(
                                res,
                                diagWithDecode.copy(
                                    targetWrapperSeen = true,
                                    validatedMediaNodeFound = true,
                                    stage = "LD_JSON_VIDEO"
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 7. Check if login-gated without target media
        if (html.contains("/accounts/login/") || html.contains("<title>Login • Instagram</title>", ignoreCase = true)) {
            return InstagramParseOutcome(
                MetaExtractionResult.Failure(
                    MetaExtractionError.Restricted(
                        RestrictionReason.LOGIN_REQUIRED,
                        "來源網站需要登入帳號驗證，目前版本不支援登入下載"
                    )
                ),
                diagWithDecode.copy(
                    restrictionPhrase = "LOGIN_REQUIRED",
                    stage = "LOGIN_GATED"
                )
            )
        }

        // 8. Technical failure: target shortcode missing from page data
        return InstagramParseOutcome(
            MetaExtractionResult.Failure(
                MetaExtractionError.Technical(
                    "Target shortcode $targetShortcode not found in page data"
                )
            ),
            diagWithDecode.copy(stage = "TARGET_NOT_IN_PAGE")
        )
    }

    data class MediaSearchResult(
        val mediaNode: JSONObject? = null,
        val wrapperSeen: Boolean = false
    )

    private fun hasDirectVideoOrCarousel(obj: JSONObject): Boolean {
        val versions = obj.optJSONArray("video_versions")
        if (versions != null && versions.length() > 0) return true
        if (obj.optString("video_url").isNotBlank()) return true
        val carousel = obj.optJSONArray("carousel_media")
            ?: obj.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (carousel != null && carousel.length() > 0) return true
        return false
    }

    private fun isValidImageOnlyNode(obj: JSONObject): Boolean {
        val isVideo = obj.optBoolean("is_video", false) ||
                obj.optInt("media_type", 0) == 2 ||
                obj.has("video_versions") ||
                obj.has("video_url")
        if (isVideo) return false

        val typename = obj.optString("__typename")
        if (typename == "GraphImage" || typename == "XDTGraphImage") return true

        val mediaType = obj.optInt("media_type", 0)
        if (mediaType == 1) return true

        if (obj.has("is_video") && !obj.optBoolean("is_video", true) && obj.has("display_url")) {
            return true
        }

        return false
    }

    private fun findXdtMediaItem(root: Any?, shortcode: String, targetId: Long): JSONObject? {
        if (root == null) return null
        if (root is JSONObject) {
            val xdtWebInfo = root.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?: root.optJSONObject("xdt_api__v1__clips__clips__web_info")
                ?: root.optJSONObject("xdt_api__v1__clips__home__web_info")
                ?: root.optJSONObject("xdt_api__v1__clips_home__web_info")
                ?: root.optJSONObject("data")?.optJSONObject("xdt_api__v1__clips__clips__web_info")
                ?: root.optJSONObject("data")?.optJSONObject("xdt_api__v1__clips__home__web_info")
                ?: root.optJSONObject("data")?.optJSONObject("xdt_api__v1__clips_home__web_info")
                ?: root.optJSONObject("data")?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
            if (xdtWebInfo != null) {
                val items = xdtWebInfo.optJSONArray("items")
                    ?: xdtWebInfo.optJSONArray("clips_items")
                    ?: xdtWebInfo.optJSONArray("edges")
                if (items != null && items.length() > 0) {
                    for (i in 0 until items.length()) {
                        val rawItem = items.optJSONObject(i) ?: continue
                        val item = rawItem.optJSONObject("media")
                            ?: rawItem.optJSONObject("node")
                            ?: rawItem
                        val code = item.optString("code").ifBlank { item.optString("shortcode") }
                        val id = item.optString("id").ifBlank { item.optString("pk") }
                        val isCodeMatch = code.isNotBlank() && code == shortcode
                        val isIdMatch = targetId > 0 && id.isNotBlank() && (id == targetId.toString() || id.startsWith(targetId.toString()))
                        if (isCodeMatch || isIdMatch) {
                            val gated = item.optJSONObject("if_not_gated_logged_out")
                            if (gated != null && hasDirectVideoOrCarousel(gated)) {
                                return gated
                            }
                            return item
                        }
                    }
                }
            }

            // Check xdt_shortcode_media or shortcode_media directly
            val shortcodeMedia = root.optJSONObject("xdt_shortcode_media")
                ?: root.optJSONObject("shortcode_media")
                ?: root.optJSONObject("data")?.optJSONObject("xdt_shortcode_media")
                ?: root.optJSONObject("data")?.optJSONObject("shortcode_media")
            if (shortcodeMedia != null) {
                val code = shortcodeMedia.optString("shortcode").ifBlank { shortcodeMedia.optString("code") }
                val id = shortcodeMedia.optString("id").ifBlank { shortcodeMedia.optString("pk") }
                val isCodeMatch = code.isNotBlank() && code == shortcode
                val isIdMatch = targetId > 0 && id.isNotBlank() && (id == targetId.toString() || id.startsWith(targetId.toString()))
                if (isCodeMatch || isIdMatch) {
                    val gated = shortcodeMedia.optJSONObject("if_not_gated_logged_out")
                    if (gated != null && hasDirectVideoOrCarousel(gated)) {
                        return gated
                    }
                    return shortcodeMedia
                }
            }

            val polarisMedia = root.optJSONObject("xig_polaris_media")
                ?: root.optJSONObject("data")?.optJSONObject("xig_polaris_media")
                ?: root.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("xig_polaris_media")
            if (polarisMedia != null) {
                val gated = polarisMedia.optJSONObject("if_not_gated_logged_out") ?: polarisMedia
                val code = gated.optString("code").ifBlank { gated.optString("shortcode") }
                val id = gated.optString("id").ifBlank { gated.optString("pk") }
                val isCodeMatch = code.isBlank() || code == shortcode
                val isIdMatch = targetId <= 0 || id.isBlank() || id == targetId.toString() || id.startsWith(targetId.toString())
                if (isCodeMatch || isIdMatch) {
                    return gated
                }
            }

            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (isExcludedContainerKey(key)) continue
                val found = findXdtMediaItem(root.opt(key), shortcode, targetId)
                if (found != null) return found
            }
        } else if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val found = findXdtMediaItem(root.opt(i), shortcode, targetId)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findMediaWithShortcode(root: Any?, shortcode: String, targetId: Long): MediaSearchResult {
        if (root == null) return MediaSearchResult()
        var wrapperSeen = false
        if (root is JSONObject) {
            val targetObj = root.optJSONObject("media") ?: root
            val code = targetObj.optString("shortcode").ifBlank { targetObj.optString("code") }
            val id = targetObj.optString("id").ifBlank { targetObj.optString("pk") }
            val isCodeMatch = code.isNotBlank() && code == shortcode
            val isIdMatch = targetId > 0 && id.isNotBlank() && (id == targetId.toString() || id.startsWith(targetId.toString()))

            if (isCodeMatch || isIdMatch) {
                wrapperSeen = true
                val gated = targetObj.optJSONObject("if_not_gated_logged_out")
                if (gated != null) {
                    val gatedCode = gated.optString("shortcode").ifBlank { gated.optString("code") }
                    val gatedId = gated.optString("id").ifBlank { gated.optString("pk") }
                    val gatedCodeMatch = gatedCode.isBlank() || gatedCode == shortcode
                    val gatedIdMatch = targetId <= 0 || gatedId.isBlank() || gatedId == targetId.toString() || gatedId.startsWith(targetId.toString())
                    if (gatedCodeMatch && gatedIdMatch) {
                        val gatedSearch = findMediaWithShortcode(gated, shortcode, targetId)
                        if (gatedSearch.mediaNode != null) return gatedSearch
                        if (hasDirectVideoOrCarousel(gated) || isValidImageOnlyNode(gated)) {
                            return MediaSearchResult(mediaNode = gated, wrapperSeen = true)
                        }
                    }
                }

                if (hasDirectVideoOrCarousel(targetObj)) {
                    return MediaSearchResult(mediaNode = targetObj, wrapperSeen = true)
                }

                val keys = targetObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isExcludedContainerKey(key)) continue
                    val child = targetObj.opt(key)
                    val childResult = findMediaWithShortcode(child, shortcode, targetId)
                    if (childResult.wrapperSeen) wrapperSeen = true
                    if (childResult.mediaNode != null) return childResult
                }

                if (isValidImageOnlyNode(targetObj)) {
                    return MediaSearchResult(mediaNode = targetObj, wrapperSeen = true)
                }

                return MediaSearchResult(mediaNode = null, wrapperSeen = true)
            }

            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (isExcludedContainerKey(key)) continue
                val child = root.opt(key)
                val childResult = findMediaWithShortcode(child, shortcode, targetId)
                if (childResult.wrapperSeen) wrapperSeen = true
                if (childResult.mediaNode != null) return childResult
            }
        } else if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val itemResult = findMediaWithShortcode(root.opt(i), shortcode, targetId)
                if (itemResult.wrapperSeen) wrapperSeen = true
                if (itemResult.mediaNode != null) return itemResult
            }
        }
        return MediaSearchResult(mediaNode = null, wrapperSeen = wrapperSeen)
    }

    private fun isExcludedContainerKey(key: String): Boolean {
        return key in listOf(
            "relatedPosts", "related_posts", "replies",
            "replyThreads", "reply_threads", "parentPost", "parent_post", "suggested_users",
            "edge_media_to_comment", "edge_related_profiles", "edge_owner_to_timeline_media"
        )
    }

    private fun extractFromMediaObject(
        mediaObj: JSONObject,
        targetShortcode: String,
        canonicalUrl: String,
        parentObj: JSONObject? = null
    ): MetaExtractionResult? {
        val isVideo = mediaObj.optBoolean("is_video", false) ||
                mediaObj.optInt("media_type", 0) == 2 ||
                mediaObj.has("video_versions") ||
                mediaObj.has("video_url")

        if (isVideo) {
            return extractDirectVideo(mediaObj, targetShortcode, canonicalUrl, parentObj)
        }

        // Check carousel_media / edge_sidecar_to_children
        val carouselMedia = mediaObj.optJSONArray("carousel_media")
            ?: mediaObj.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")

        if (carouselMedia != null && carouselMedia.length() > 0) {
            var anyChildVideo = false
            for (i in 0 until carouselMedia.length()) {
                var child = carouselMedia.optJSONObject(i)
                if (child?.has("node") == true) {
                    child = child.optJSONObject("node")
                }
                if (child != null) {
                    val childIsVideo = child.optBoolean("is_video", false) ||
                            child.optInt("media_type", 0) == 2 ||
                            child.has("video_versions") ||
                            child.has("video_url")
                    if (childIsVideo) {
                        anyChildVideo = true
                        val childResult = extractDirectVideo(child, targetShortcode, canonicalUrl, mediaObj)
                        if (childResult is MetaExtractionResult.Success) {
                            return childResult
                        }
                    }
                }
            }
            if (anyChildVideo) {
                return MetaExtractionResult.Failure(
                    MetaExtractionError.Technical("此 Instagram 貼文包含輪播影片，但未解析出相容的下載串流格式")
                )
            }
        }

        return null
    }

    private fun extractDirectVideo(
        videoObj: JSONObject,
        targetShortcode: String,
        canonicalUrl: String,
        parentObj: JSONObject? = null
    ): MetaExtractionResult {
        val renditions = mutableListOf<NativeMediaRendition>()

        val versions = videoObj.optJSONArray("video_versions")
        if (versions != null) {
            for (i in 0 until versions.length()) {
                val ver = versions.optJSONObject(i) ?: continue
                val rawUrl = ver.optString("url")
                val w = ver.optInt("width", 0)
                val h = ver.optInt("height", 0)
                if (rawUrl.isNotBlank()) {
                    val normalized = normalizeCdnUrl(rawUrl)
                    if (isMetaVideoUrl(normalized)) {
                        renditions.add(NativeMediaRendition(url = normalized, width = w, height = h))
                    }
                }
            }
        }

        val directVideoUrl = videoObj.optString("video_url")
        if (directVideoUrl.isNotBlank()) {
            val normalized = normalizeCdnUrl(directVideoUrl)
            if (isMetaVideoUrl(normalized) && renditions.none { it.url == normalized }) {
                renditions.add(0, NativeMediaRendition(url = normalized, width = 0, height = 0))
            }
        }

        if (renditions.isEmpty()) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Technical("此 Instagram 貼文已識別為影片，但未解析出相容的下載串流格式")
            )
        }

        val sortedRenditions = renditions.sortedByDescending { it.resolution }
        val urls = sortedRenditions.map { it.url }.distinct()
        val heights = sortedRenditions.map { it.resolution }.filter { it > 0 }.distinct()

        val rawThumb = videoObj.optString("display_url").ifBlank {
            parentObj?.optString("display_url") ?: ""
        }
        val thumb = if (rawThumb.isNotBlank()) normalizeCdnUrl(rawThumb) else null

        val owner = videoObj.optJSONObject("owner")
            ?: videoObj.optJSONObject("user")
            ?: parentObj?.optJSONObject("owner")
            ?: parentObj?.optJSONObject("user")
        val uploader = owner?.optString("username")?.ifBlank { "Instagram" } ?: "Instagram"

        val captionEdges = (videoObj.optJSONObject("edge_media_to_caption") ?: parentObj?.optJSONObject("edge_media_to_caption"))
            ?.optJSONArray("edges")
        val caption = captionEdges?.optJSONObject(0)?.optJSONObject("node")?.optString("text")?.take(100)

        val title = if (!caption.isNullOrBlank()) caption else "Instagram 影片 ($targetShortcode)"
        val duration = videoObj.optDouble("video_duration", 0.0)

        return MetaExtractionResult.Success(
            ExtractedMetaMedia(
                postId = targetShortcode,
                canonicalUrl = canonicalUrl,
                title = title,
                uploader = uploader,
                thumbnailUrl = thumb,
                progressiveVideoUrls = urls,
                renditions = sortedRenditions,
                heights = heights,
                durationSeconds = if (duration > 0) duration.toLong() else null
            )
        )
    }

    suspend fun fetchMediaInfoAuthenticated(shortcode: String, mediaId: Long, canonicalUrl: String): Result<ExtractedMetaMedia> = withContext(Dispatchers.IO) {
        val cookies = httpSession.sessionProvider.cookiesFor(Platform.INSTAGRAM)
        val sessionid = cookies.firstOrNull { it.name.equals("sessionid", ignoreCase = true) }?.value
        if (sessionid.isNullOrBlank()) {
            return@withContext Result.failure(PlatformExtractionError.LoginRequired("Instagram Session 缺少 sessionid"))
        }

        httpSession.syncSessionCookies(Platform.INSTAGRAM)

        val cookieHeader = buildString {
            append("sessionid=").append(sessionid)
            for (c in cookies) {
                if (!c.name.equals("sessionid", ignoreCase = true)) {
                    append("; ").append(c.name).append("=").append(c.value)
                }
            }
        }

        val apiUrl = "https://i.instagram.com/api/v1/media/$mediaId/info/"
        val headers = mutableMapOf(
            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
            "X-IG-App-ID" to "936619743392459",
            "X-ASBD-ID" to "198387",
            "Accept" to "*/*",
            "Origin" to "https://www.instagram.com",
            "Referer" to "https://www.instagram.com/p/$shortcode/",
            "Cookie" to cookieHeader
        )

        val respResult = httpSession.fetch(
            url = apiUrl,
            profile = RequestProfile.API,
            origin = "https://www.instagram.com",
            referer = "https://www.instagram.com/p/$shortcode/",
            customHeaders = headers
        )

        val resp = respResult.getOrElse {
            return@withContext Result.failure(it)
        }

        if (resp.code == 401 || resp.code == 403) {
            httpSession.sessionProvider.markExpired(Platform.INSTAGRAM, "HTTP ${resp.code}")
            return@withContext Result.failure(
                PlatformExtractionError.SessionExpired(
                    "Instagram 登入狀態已失效（HTTP ${resp.code}），請至設定重新匯入 Session",
                    internalReason = "API_AUTH_EXPIRED:${resp.code}"
                )
            )
        }

        if (resp.code !in 200..299) {
            return@withContext Result.failure(
                PlatformExtractionError.ApiError(resp.code, "Instagram API 回傳錯誤碼 ${resp.code}", internalReason = "API_ERROR:${resp.code}")
            )
        }

        try {
            val json = JSONObject(resp.body)
            val items = json.optJSONArray("items")
            if (items == null || items.length() == 0) {
                val msg = json.optString("message")
                if (msg.contains("checkpoint_required", ignoreCase = true) || msg.contains("login_required", ignoreCase = true)) {
                    httpSession.sessionProvider.markExpired(Platform.INSTAGRAM, msg)
                    return@withContext Result.failure(
                        PlatformExtractionError.SessionExpired(
                            "Instagram 登入驗證過期或需要安全檢查 ($msg)",
                            internalReason = "API_CHECKPOINT_REQUIRED"
                        )
                    )
                }
                return@withContext Result.failure(
                    PlatformExtractionError.TargetNotInPageData("Instagram API 回應中未找到貼文項目", internalReason = "API_EMPTY_ITEMS")
                )
            }

            val item = items.optJSONObject(0)
                ?: return@withContext Result.failure(
                    PlatformExtractionError.ParseError("無法解析 Instagram 媒體資訊物件", internalReason = "API_NULL_ITEM")
                )

            val parseRes = extractFromMediaObject(item, shortcode, canonicalUrl)
            if (parseRes is MetaExtractionResult.Success) {
                Result.success(parseRes.media)
            } else if (isValidImageOnlyNode(item)) {
                Result.failure(PlatformExtractionError.NoVideo("此 Instagram 貼文未包含可下載的影片內容（可能為純圖片）", internalReason = "API_IMAGE_ONLY"))
            } else {
                Result.failure(PlatformExtractionError.MediaUrlUnsupported("未找到相容的影片串流格式", internalReason = "API_NO_COMPATIBLE_VIDEO"))
            }
        } catch (e: Exception) {
            Result.failure(PlatformExtractionError.ParseError("解析 Instagram API 回應失敗", cause = e))
        }
    }

    suspend fun fetchPolarisLoggedOutGraphQL(shortcode: String, canonicalUrl: String): Result<ExtractedMetaMedia> = withContext(Dispatchers.IO) {
        val mediaId = shortcodeToMediaId(shortcode)
        val targetPageUrl = "https://www.instagram.com/p/$shortcode/"

        // 1. Content-ruling preflight check (yt-dlp alignment)
        val rulingUrl = "https://www.instagram.com/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id=$mediaId"
        val rulingHeaders = mutableMapOf(
            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
            "X-IG-App-ID" to INSTAGRAM_APP_ID,
            "X-ASBD-ID" to "129477",
            "X-IG-WWW-Claim" to "0",
            "Origin" to "https://www.instagram.com",
            "Referer" to targetPageUrl,
            "Accept" to "*/*"
        )

        val rulingResp = httpSession.fetch(
            url = rulingUrl,
            profile = RequestProfile.API,
            origin = "https://www.instagram.com",
            referer = targetPageUrl,
            customHeaders = rulingHeaders
        )

        var csrfToken: String? = null
        val rulingObj = rulingResp.getOrNull()
        if (rulingObj != null) {
            if (rulingObj.code == 429) {
                return@withContext Result.failure(
                    PlatformExtractionError.RateLimited("Instagram 存取頻率受限 (HTTP 429)，請稍候再試")
                )
            }
            if (rulingObj.code in 200..299) {
                val rulingBody = rulingObj.body
                try {
                    val rulingJson = JSONObject(rulingBody)
                    val title = rulingJson.optString("title")
                    val description = rulingJson.optString("description")
                    val isRestricted = title.contains("Restricted Video", ignoreCase = true) ||
                            description.contains("Restricted Video", ignoreCase = true) ||
                            rulingBody.contains("login_required", ignoreCase = true)

                    if (isRestricted) {
                        return@withContext Result.failure(
                            PlatformExtractionError.LoginRequired(
                                "此 Instagram 內容為受限影片，需要登入帳號後方可存取。請至設定匯入 Instagram Session。",
                                internalReason = "RULING_RESTRICTED_VIDEO"
                            )
                        )
                    }
                } catch (_: Exception) {}
            } else {
                safeLog("[Instagram] ruling preflight returned non-2xx (${rulingObj.code}), continuing as non-fatal advisory")
            }

            // Extract CSRF token from ruling response headers / cookies
            val setCookieHeader = rulingObj.headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
            if (setCookieHeader != null) {
                val csrfMatch = Regex("""csrftoken=([a-zA-Z0-9_-]+)""").find(setCookieHeader)
                if (csrfMatch != null) csrfToken = csrfMatch.groupValues[1]
            }
        } else {
            safeLog("[Instagram] ruling preflight transport failure (${rulingResp.exceptionOrNull()?.message}), continuing as non-fatal advisory")
        }

        if (csrfToken.isNullOrBlank()) {
            csrfToken = httpSession.cookieJar.getCookieValue("instagram.com", "csrftoken")
        }

        // 2. Obtain LSD token context (yt-dlp alignment: from page or cookie jar)
        var lsdToken = cachedLsdToken ?: httpSession.cookieJar.getCookieValue("instagram.com", "lsd")
        if (lsdToken.isNullOrBlank()) {
            val pageResp = httpSession.fetch(
                url = targetPageUrl,
                profile = RequestProfile.API,
                origin = "https://www.instagram.com",
                referer = "https://www.instagram.com/"
            )
            val pageHtml = pageResp.getOrNull()?.body ?: ""
            if (pageHtml.isNotBlank()) {
                val lsdRegex = Regex("""\["LSD",\[\],\{"token":"([^"]+)"""")
                val lsdMatch = lsdRegex.find(pageHtml) ?: Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(pageHtml)
                if (lsdMatch != null) {
                    lsdToken = lsdMatch.groupValues[1]
                    cachedLsdToken = lsdToken
                }
                if (csrfToken.isNullOrBlank()) {
                    val csrfPageMatch = Regex("""\["CSRF",\[\],\{"token":"([^"]+)"""").find(pageHtml)
                    if (csrfPageMatch != null) csrfToken = csrfPageMatch.groupValues[1]
                }
            }
        }

        if (lsdToken.isNullOrBlank()) {
            return@withContext Result.failure(
                PlatformExtractionError.TargetNotInPageData(
                    "無法從 Instagram 取得有效的 LSD 權杖，跳過 GraphQL 查詢",
                    internalReason = "POLARIS_LSD_MISSING"
                )
            )
        }

        val effectiveLsd = lsdToken

        // 3. Polaris GraphQL Request
        val endpoint = "https://www.instagram.com/api/graphql"
        val variables = JSONObject().apply {
            put("media_id", mediaId)
        }.toString()

        val postData = "lsd=${URLEncoder.encode(effectiveLsd, "UTF-8")}&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery&server_timestamps=true&variables=${URLEncoder.encode(variables, "UTF-8")}&doc_id=$POLARIS_DOC_ID"

        val headers = mutableMapOf(
            "User-Agent" to BrowserIdentity.DESKTOP.userAgent,
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-IG-App-ID" to INSTAGRAM_APP_ID,
            "X-ASBD-ID" to "129477",
            "X-IG-WWW-Claim" to "0",
            "X-FB-Friendly-Name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
            "X-FB-LSD" to effectiveLsd,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to "https://www.instagram.com",
            "Referer" to targetPageUrl,
            "Accept" to "*/*"
        )
        if (!csrfToken.isNullOrBlank()) {
            headers["X-CSRFToken"] = csrfToken
        }

        val respResult = httpSession.fetch(
            url = endpoint,
            profile = RequestProfile.API,
            origin = "https://www.instagram.com",
            referer = targetPageUrl,
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
                PlatformExtractionError.RateLimited("Instagram 存取頻率受限 (HTTP 429)，請稍候再試")
            )
        }

        if (resp.code !in 200..299) {
            return@withContext Result.failure(
                PlatformExtractionError.ApiError(resp.code, "Polaris GraphQL 回傳錯誤碼 ${resp.code}", internalReason = "POLARIS_HTTP_${resp.code}")
            )
        }

        try {
            val json = JSONObject(resp.body)
            val data = json.optJSONObject("data")
            val polarisMedia = data?.optJSONObject("xig_polaris_media")
                ?: return@withContext Result.failure(
                    PlatformExtractionError.TargetNotInPageData("Polaris GraphQL 回應遺漏 xig_polaris_media", internalReason = "POLARIS_MISSING_MEDIA")
                )

            val gated = polarisMedia.optJSONObject("if_not_gated_logged_out")
            if (gated == null) {
                if (shortcode.length > 28) {
                    return@withContext Result.failure(
                        PlatformExtractionError.LoginRequired(
                            "此內容僅限追蹤該帳號的已註冊用戶存取",
                            internalReason = "POLARIS_PRIVATE_LONG_SHORTCODE"
                        )
                    )
                }
                return@withContext Result.failure(
                    PlatformExtractionError.LoginRequired(
                        "此 Instagram 內容在未登入狀態下受限，需要登入帳號後方可存取。請至設定匯入 Instagram Session。",
                        internalReason = "POLARIS_GATED_LOGGED_OUT"
                    )
                )
            }

            val parseRes = extractFromMediaObject(gated, shortcode, canonicalUrl)
            if (parseRes is MetaExtractionResult.Success) {
                Result.success(parseRes.media)
            } else if (isValidImageOnlyNode(gated)) {
                Result.failure(PlatformExtractionError.NoVideo("此 Instagram 貼文未包含可下載的影片內容（可能為純圖片）", internalReason = "POLARIS_IMAGE_ONLY"))
            } else {
                Result.failure(PlatformExtractionError.MediaUrlUnsupported("未找到相容的影片串流格式", internalReason = "POLARIS_NO_COMPATIBLE_VIDEO"))
            }
        } catch (e: Exception) {
            Result.failure(PlatformExtractionError.ParseError("無法解析 Polaris GraphQL 回應", cause = e))
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        lastDiagnosticFingerprint = null
        lastProfileSequence = emptyList()

        val shortcode = extractShortcode(url)
            ?: return@withContext Result.failure(
                PlatformExtractionError.PageVariantUnsupported("無法從網址中解析 Instagram 貼文代碼，請確認網址格式")
            )

        val canonicalUrl = normalizeUrl(url)
        val targetId = shortcodeToId(shortcode)
        val fetchUrl = buildFetchUrl(url)
        val profileSteps = mutableListOf<String>()
        val profileErrors = mutableListOf<PlatformExtractionError>()
        val diagnosticHistory = mutableListOf<String>()

        var successfulMedia: ExtractedMetaMedia? = null

        safeLog("[Resolver] platform=INSTAGRAM")

        // 1. Authenticated API if active session exists
        if (httpSession.sessionProvider.hasAuthenticatedSession(Platform.INSTAGRAM)) {
            profileSteps.add("API_AUTHENTICATED")
            safeLog("[Instagram] authenticated session available, attempting /api/v1/media/$targetId/info/")
            val authResult = fetchMediaInfoAuthenticated(shortcode, targetId, canonicalUrl)
            if (authResult.isSuccess) {
                safeLog("[Instagram] authenticated API success")
                successfulMedia = authResult.getOrThrow()
                diagnosticHistory.add("[profile=API_AUTHENTICATED http_status=200 stage=SUCCESS error=NONE]")
                lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
            } else {
                val authErr = authResult.exceptionOrNull() as? PlatformExtractionError
                safeLog("[Instagram] authenticated API failed: ${authErr?.message}")
                diagnosticHistory.add("[profile=API_AUTHENTICATED stage=FAILURE error=${authErr?.javaClass?.simpleName}]")
                lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
                if (authErr is PlatformExtractionError.SessionExpired || authErr is PlatformExtractionError.NoVideo) {
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(authErr)
                }
            }
        }

        // 2. Primary anonymous path: Polaris Logged-Out GraphQL
        if (successfulMedia == null) {
            profileSteps.add("POLARIS_GRAPHQL")
            safeLog("[Instagram] attempting Polaris logged-out GraphQL query")
            val polarisResult = fetchPolarisLoggedOutGraphQL(shortcode, canonicalUrl)
            if (polarisResult.isSuccess) {
                safeLog("[Instagram] Polaris GraphQL success")
                successfulMedia = polarisResult.getOrThrow()
                diagnosticHistory.add("[profile=POLARIS_GRAPHQL http_status=200 stage=SUCCESS error=NONE]")
                lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
            } else {
                val polarisErr = polarisResult.exceptionOrNull() as? PlatformExtractionError
                safeLog("[Instagram] Polaris GraphQL failed: ${polarisErr?.message}")
                diagnosticHistory.add("[profile=POLARIS_GRAPHQL stage=FAILURE error=${polarisErr?.javaClass?.simpleName}]")
                lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")
                if (polarisErr is PlatformExtractionError.RateLimited) {
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(polarisErr)
                } else if (polarisErr is PlatformExtractionError.LoginRequired) {
                    if (!httpSession.sessionProvider.hasAuthenticatedSession(Platform.INSTAGRAM)) {
                        lastProfileSequence = profileSteps
                        return@withContext Result.failure(polarisErr)
                    }
                } else if (polarisErr is PlatformExtractionError.NoVideo) {
                    lastProfileSequence = profileSteps
                    return@withContext Result.failure(polarisErr)
                }
            }
        }

        // 3. Fallback: Desktop / Mobile / Crawler HTML navigation profiles
        if (successfulMedia == null) {
            val profiles = listOf(
                "DESKTOP" to RequestProfile.DESKTOP_NAVIGATION,
                "MOBILE" to RequestProfile.MOBILE_NAVIGATION,
                "CRAWLER" to RequestProfile.CRAWLER_NAVIGATION
            )

            for ((name, profile) in profiles) {
                profileSteps.add(name)
                val requestUrl = if (profile == RequestProfile.CRAWLER_NAVIGATION) {
                val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#').trim()
                val crawlerBase = "https://www.instagram.com/p/$shortcode/"
                val crawlerUrl = if (query.isNotBlank()) "$crawlerBase?$query" else crawlerBase
                safeLog("[Instagram] profile=CRAWLER alternate_path=/p/$shortcode has_query=${query.isNotBlank()}")
                crawlerUrl
            } else {
                fetchUrl
            }

            val resp = httpSession.fetch(requestUrl, profile)
            val respObj = resp.getOrNull()
            val html = respObj?.body ?: ""
            val httpCode = respObj?.code ?: 0
            if (httpCode == 429) {
                lastProfileSequence = profileSteps
                return@withContext Result.failure(
                    PlatformExtractionError.RateLimited("Instagram 存取頻率受限 (HTTP 429)，請稍候再試")
                )
            }
            val contentType = respObj?.getHeader("content-type") ?: (if (resp.isFailure) "none" else "text/html")
            val bodyBytes = html.toByteArray().size
            val sizeBucket = formatSizeBucket(bodyBytes)
            val finalUrl = respObj?.finalUrl ?: requestUrl
            val cleanPath = cleanHostAndPath(finalUrl)
            val redirect = if (respObj?.finalUrl != null && respObj.finalUrl != requestUrl) "yes" else "no"

            if (resp.isFailure || html.isBlank()) {
                val stage = if (resp.isFailure) "FETCH_FAILED" else "EMPTY_BODY"
                val errorClassName = if (resp.isFailure) {
                    resp.exceptionOrNull()?.javaClass?.simpleName ?: "IOException"
                } else {
                    "EMPTY_BODY"
                }
                val failureMessage = if (resp.isFailure) {
                    "HTTP fetch failed for profile $name: ${resp.exceptionOrNull()?.message}"
                } else {
                    "Empty response body for profile $name (HTTP $httpCode)"
                }
                val stepError = MetaExtractionError.Technical(failureMessage, internalReason = stage)
                profileErrors.add(stepError)

                val profileFp = """
                    [profile=$name http_status=$httpCode content_type=$contentType body_size=$sizeBucket host_and_path=$cleanPath redirect=$redirect stage=$stage error=$errorClassName]
                    scripts: app_json=0 data_sjs=0 generic=0
                    target: raw=false decoded=false wrapper=false media_node=false
                    keys: none
                    markers: none
                    restriction: NONE
                """.trimIndent()
                diagnosticHistory.add(profileFp)
                lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")

                safeLog("[Instagram] profile=$name fetch_failed=${resp.isFailure} empty_body=${html.isBlank()} stage=$stage")
                continue
            }

            val parseOutcome = parseInstagramPageWithDiagnostics(html, shortcode, canonicalUrl)
            val parseResult = parseOutcome.result
            val diag = parseOutcome.diagnostics

            val errorClassName = when (parseResult) {
                is MetaExtractionResult.Success -> "NONE"
                is MetaExtractionResult.Failure -> parseResult.error.javaClass.simpleName
            }

            val profileFp = """
                [profile=$name http_status=$httpCode content_type=$contentType body_size=$sizeBucket host_and_path=$cleanPath redirect=$redirect stage=${diag.stage} error=$errorClassName]
                scripts: app_json=${diag.appJsonCount} data_sjs=${diag.dataSjsCount} generic=${diag.genericScriptCount}
                target: raw=${diag.rawShortcodeSeen} decoded=${diag.decodedShortcodeSeen} wrapper=${diag.targetWrapperSeen} media_node=${diag.validatedMediaNodeFound}
                keys: ${if (diag.matchedKeys.isNotEmpty()) diag.matchedKeys.joinToString(",") else "none"}
                markers: ${if (diag.presentMarkers.isNotEmpty()) diag.presentMarkers.joinToString(",") else "none"}
                restriction: ${diag.restrictionPhrase}
            """.trimIndent()
            diagnosticHistory.add(profileFp)
            lastDiagnosticFingerprint = diagnosticHistory.joinToString("\n---\n")

            when (parseResult) {
                is MetaExtractionResult.Success -> {
                    safeLog("[Instagram] profile=$name target_id_match=true actual_media=true")
                    successfulMedia = parseResult.media
                    break
                }
                is MetaExtractionResult.Failure -> {
                    val error = parseResult.error
                    profileErrors.add(error)
                    when (error) {
                        is MetaExtractionError.Restricted -> {
                            if (error.reason == RestrictionReason.LOGIN_REQUIRED) {
                                safeLog("[Instagram] profile=$name login_gated=true")
                            } else {
                                safeLog("[Instagram] profile=$name restriction=${error.reason}")
                                lastProfileSequence = profileSteps
                                safeLog("[Instagram] final=${error.code.name}")
                                return@withContext Result.failure(error)
                            }
                        }
                        is MetaExtractionError.NoVideo -> {
                            safeLog("[Instagram] profile=$name target_id_match=true actual_media=true no_video=true")
                            lastProfileSequence = profileSteps
                            safeLog("[Instagram] final=NO_VIDEO")
                            return@withContext Result.failure(error)
                        }
                        else -> {
                            val detail = (error as? MetaExtractionError.Technical)?.detail ?: error.message ?: ""
                            val targetMatch = detail.contains("wrapper found")
                            safeLog("[Instagram] profile=$name target_id_match=$targetMatch actual_media=false technical_error=$detail")
                        }
                    }
                }
            }
        }
    }

        lastProfileSequence = profileSteps

        if (successfulMedia != null) {
            safeLog("[Instagram] final=SUCCESS")
            val options = buildQualityOptions(successfulMedia)
            val info = MediaInfo(
                sourceUrl = fetchUrl,
                title = successfulMedia.title,
                platform = Platform.INSTAGRAM,
                extractor = ENGINE_NAME,
                thumbnailUrl = successfulMedia.thumbnailUrl,
                durationSeconds = successfulMedia.durationSeconds,
                qualityOptions = options
            )
            return@withContext Result.success(info)
        }

        val allProfilesLoginGated = profileErrors.isNotEmpty() && profileErrors.all {
            it is MetaExtractionError.Restricted && it.reason == RestrictionReason.LOGIN_REQUIRED
        }

        if (allProfilesLoginGated) {
            safeLog("[Instagram] final=LOGIN_REQUIRED")
            return@withContext Result.failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.LOGIN_REQUIRED,
                    "此 Instagram 貼文需要登入帳號驗證，請至設定匯入 Instagram Session"
                )
            )
        }

        safeLog("[Instagram] final=TECHNICAL")
        val technicalError = profileErrors.filterIsInstance<MetaExtractionError.Technical>().lastOrNull()
            ?: MetaExtractionError.Technical("All anonymous profiles failed to extract media for $shortcode")
        Result.failure(technicalError)
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
        val bestRendition = renditions.firstOrNull()
            ?: media.progressiveVideoUrls.firstOrNull()?.let { NativeMediaRendition(it) }

        if (bestRendition != null) {
            options.add(
                QualityOption(
                    id = "best",
                    label = "最佳畫質 (推薦)",
                    formatSelector = bestRendition.url
                )
            )
        }

        val r1080 = renditions.firstOrNull { it.resolution >= 1080 }
        if (r1080 != null) {
            options.add(QualityOption("1080p", "1080p Full HD", r1080.url))
        }
        val r720 = renditions.firstOrNull { it.resolution in 720..1079 }
        if (r720 != null) {
            options.add(QualityOption("720p", "720p HD", r720.url))
        }
        val r480 = renditions.firstOrNull { it.resolution in 480..719 }
        if (r480 != null) {
            options.add(QualityOption("480p", "480p 標清", r480.url))
        }
        val r360 = renditions.firstOrNull { it.resolution in 360..479 }
        if (r360 != null) {
            options.add(QualityOption("360p", "360p 流暢", r360.url))
        }

        val distinctOptions = options.distinctBy { it.id }.toMutableList()

        if (bestRendition != null) {
            distinctOptions.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅下載音訊 (MP3)",
                    formatSelector = "audio:${bestRendition.url}",
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
        val directStreamUrl = format.removePrefix("audio:")

        val sanitizedTitle = request.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        val finalFileName = if (isAudioOnly) "$sanitizedTitle.mp3" else "$sanitizedTitle.mp4"
        val destinationFile = File(destDir, finalFileName)

        onStatus("正在透過原生 Instagram 引擎下載串流...")

        val downloadTarget = if (isAudioOnly) {
            File(destDir, "${sanitizedTitle}_temp_${UUID.randomUUID().toString().take(6)}.mp4")
        } else {
            destinationFile
        }

        val success = httpSession.downloadMediaStream(
            streamUrl = directStreamUrl,
            destination = downloadTarget,
            referer = "https://www.instagram.com/",
            origin = "https://www.instagram.com",
            onProgress = onProgress,
            isCancelled = { isCancelled.get() }
        )

        if (!success) {
            if (isCancelled.get()) {
                downloadTarget.delete()
                return@withContext Result.failure(InterruptedException("下載已被使用者取消"))
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

        Result.success(destinationFile)
    }
}
