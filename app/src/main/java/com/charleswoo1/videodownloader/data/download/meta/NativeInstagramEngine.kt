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
            if (root is JSONObject) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isExcludedContainerKey(key)) continue
                    val value = root.opt(key)
                    if (value is String) {
                        val trimmed = value.trim()
                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            try {
                                val nestedObj = JSONObject(trimmed)
                                candidates.add(nestedObj)
                                collectNestedJsonStrings(nestedObj, candidates, currentDepth + 1, maxDepth)
                            } catch (_: Exception) {}
                        } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            try {
                                val nestedArr = JSONArray(trimmed)
                                for (i in 0 until nestedArr.length()) {
                                    val item = nestedArr.optJSONObject(i)
                                    if (item != null) {
                                        candidates.add(item)
                                        collectNestedJsonStrings(item, candidates, currentDepth + 1, maxDepth)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    } else if (value is JSONObject) {
                        collectNestedJsonStrings(value, candidates, currentDepth, maxDepth)
                    } else if (value is JSONArray) {
                        for (i in 0 until value.length()) {
                            collectNestedJsonStrings(value.opt(i), candidates, currentDepth, maxDepth)
                        }
                    }
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

        fun shortcodeToId(shortcode: String): Long {
            var id = 0L
            for (char in shortcode) {
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

    fun parseInstagramPage(html: String, targetShortcode: String, canonicalUrl: String): MetaExtractionResult {
        // 1. Check for audience/content restriction
        if (html.contains("This content isn't available to everyone", ignoreCase = true) ||
            html.contains("It can't be seen by certain audiences", ignoreCase = true)
        ) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.AUDIENCE_RESTRICTED,
                    "此 Instagram 內容限制部分使用者觀看，匿名模式無法存取。"
                )
            )
        }

        // 2. Check for deleted/private post
        if (html.contains("Sorry, this page isn't available.", ignoreCase = true) ||
            html.contains("The link you followed may be broken, or the page may have been removed.", ignoreCase = true)
        ) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.DELETED_OR_PRIVATE,
                    "Instagram 貼文不存在、設為私人內容或已被刪除"
                )
            )
        }

        // 3. Scan script tags for JSON payloads (application/json, _sharedData, data-sjs, etc.)
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

        val targetId = shortcodeToId(targetShortcode)

        // 4. Primary: inspect xdt_api__v1__media__shortcode__web_info.items[0]
        for (candidate in candidates) {
            val xdtMedia = findXdtMediaItem(candidate, targetShortcode, targetId)
            if (xdtMedia != null) {
                val extracted = extractFromMediaObject(xdtMedia, targetShortcode, canonicalUrl)
                if (extracted != null) return extracted
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
            val extracted = extractFromMediaObject(matchedMediaNode, targetShortcode, canonicalUrl)
            if (extracted != null) return extracted

            // Only return terminal NoVideo if target media node is positively verified to have no video
            if (isValidImageOnlyNode(matchedMediaNode)) {
                return MetaExtractionResult.Failure(
                    MetaExtractionError.NoVideo("此 Instagram 貼文未包含任何影片 (可能為純圖片貼文)")
                )
            }

            return MetaExtractionResult.Failure(
                MetaExtractionError.Technical(
                    "Target shortcode $targetShortcode media node found but lacks recognized video structure or explicit photo type"
                )
            )
        }

        // If target wrapper was found but no validated media node resolved, do NOT return NoVideo
        if (targetWrapperSeen) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Technical(
                    "Target shortcode $targetShortcode wrapper found in page data, but no validated media node resolved"
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
                            return MetaExtractionResult.Success(
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
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 7. Check if login-gated without target media
        if (html.contains("/accounts/login/") || html.contains("<title>Login • Instagram</title>", ignoreCase = true)) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.LOGIN_REQUIRED,
                    "來源網站需要登入帳號驗證，目前版本不支援登入下載"
                )
            )
        }

        // 8. Technical failure: target shortcode missing from page data
        return MetaExtractionResult.Failure(
            MetaExtractionError.Technical(
                "Target shortcode $targetShortcode not found in page data"
            )
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
            val xdt = root.optJSONObject("xdt_api__v1__media__shortcode__web_info")
            if (xdt != null) {
                val items = xdt.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
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
            val code = root.optString("shortcode").ifBlank { root.optString("code") }
            val id = root.optString("id").ifBlank { root.optString("pk") }
            val isCodeMatch = code.isNotBlank() && code == shortcode
            val isIdMatch = targetId > 0 && id.isNotBlank() && (id == targetId.toString() || id.startsWith(targetId.toString()))

            if (isCodeMatch || isIdMatch) {
                wrapperSeen = true
                val gated = root.optJSONObject("if_not_gated_logged_out")
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

                if (hasDirectVideoOrCarousel(root)) {
                    return MediaSearchResult(mediaNode = root, wrapperSeen = true)
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

                if (isValidImageOnlyNode(root)) {
                    return MediaSearchResult(mediaNode = root, wrapperSeen = true)
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

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(url)
            ?: return@withContext Result.failure(
                PlatformExtractionError.PageVariantUnsupported("無法從網址中解析 Instagram 貼文代碼，請確認網址格式")
            )

        val canonicalUrl = normalizeUrl(url)
        val fetchUrl = buildFetchUrl(url)
        val profileSteps = mutableListOf<String>()
        val profileErrors = mutableListOf<MetaExtractionError>()
        val profiles = listOf(
            "DESKTOP" to RequestProfile.DESKTOP_NAVIGATION,
            "MOBILE" to RequestProfile.MOBILE_NAVIGATION,
            "CRAWLER" to RequestProfile.CRAWLER_NAVIGATION
        )

        var successfulMedia: ExtractedMetaMedia? = null

        safeLog("[Resolver] platform=INSTAGRAM")

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
            val html = resp.getOrNull()?.body ?: ""

            val parseResult = parseInstagramPage(html, shortcode, canonicalUrl)
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
                    "來源網站需要登入帳號驗證，目前版本不支援登入下載"
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
