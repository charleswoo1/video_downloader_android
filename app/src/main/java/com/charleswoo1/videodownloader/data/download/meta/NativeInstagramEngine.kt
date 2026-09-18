package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.PlatformMediaEngine
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
 * Native Instagram extraction and download engine for public reels and videos.
 *
 * Implements strict target matching using shortcode and derived numeric media ID
 * to prevent extracting unrelated recommended videos. Correctly classifies content/audience
 * restrictions to avoid invalid fallback loops.
 */
class NativeInstagramEngine(
    private val context: Context? = null,
    private val webClient: MetaWebClient = MetaWebClient()
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
    }

    override val name: String = ENGINE_NAME

    private val isCancelled = AtomicBoolean(false)

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

        val targetId = shortcodeToId(targetShortcode)

        // 3. Scan script tags for JSON payload
        val candidates = mutableListOf<JSONObject>()

        fun scanJson(pattern: Pattern) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val raw = matcher.group(1)?.trim() ?: continue
                try {
                    if (raw.startsWith("{") && raw.endsWith("}")) {
                        candidates.add(JSONObject(raw))
                    } else if (raw.startsWith("[") && raw.endsWith("]")) {
                        val arr = JSONArray(raw)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i)
                            if (item != null) candidates.add(item)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        scanJson(SCRIPT_JSON_PATTERN)
        scanJson(SHARED_DATA_PATTERN)

        var matchedPost: JSONObject? = null
        for (candidate in candidates) {
            matchedPost = findMediaWithShortcode(candidate, targetShortcode, targetId)
            if (matchedPost != null) break
        }

        if (matchedPost != null) {
            val isVideo = matchedPost.optBoolean("is_video", false)
            if (isVideo) {
                return extractDirectVideo(matchedPost, targetShortcode, canonicalUrl)
            }

            // Check carousel media
            val carouselMedia = matchedPost.optJSONArray("carousel_media")
                ?: matchedPost.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")

            if (carouselMedia != null && carouselMedia.length() > 0) {
                for (i in 0 until carouselMedia.length()) {
                    var child = carouselMedia.optJSONObject(i)
                    if (child?.has("node") == true) {
                        child = child.optJSONObject("node")
                    }
                    if (child != null && child.optBoolean("is_video", false)) {
                        return extractDirectVideo(child, targetShortcode, canonicalUrl, matchedPost)
                    }
                }
            }

            // Post exists and is confirmed to have no video (image post)
            return MetaExtractionResult.Failure(
                MetaExtractionError.NoVideo("此 Instagram 貼文未包含任何影片 (不支援純圖片下載)")
            )
        }

        // 4. Schema.org VideoObject fallback
        val ldMatcher = LD_JSON_PATTERN.matcher(html)
        while (ldMatcher.find()) {
            val raw = ldMatcher.group(1)?.trim() ?: continue
            try {
                if (raw.startsWith("{")) {
                    val ld = JSONObject(raw)
                    if (ld.optString("@type") == "VideoObject") {
                        val contentUrl = ld.optString("contentUrl")
                        if (contentUrl.isNotBlank()) {
                            val title = ld.optString("name").ifBlank {
                                ld.optString("description").ifBlank { "Instagram 影片 ($targetShortcode)" }
                            }
                            val thumb = ld.optString("thumbnailUrl").ifBlank { null }
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

        // 5. Check if login-gated without target media
        if (html.contains("/accounts/login/") || html.contains("<title>Login • Instagram</title>", ignoreCase = true)) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.LOGIN_REQUIRED,
                    "來源網站需要登入帳號驗證，目前版本不支援登入下載"
                )
            )
        }

        // 6. Schema changed or technical failure
        return MetaExtractionResult.Failure(
            MetaExtractionError.Technical(
                "Target shortcode $targetShortcode not found in page data"
            )
        )
    }

    private fun findMediaWithShortcode(root: Any?, shortcode: String, targetId: Long): JSONObject? {
        if (root == null) return null
        if (root is JSONObject) {
            val code = root.optString("shortcode").ifBlank { root.optString("code") }
            val id = root.optString("id")
            if (code == shortcode || (targetId > 0 && id.startsWith(targetId.toString()))) {
                return root
            }

            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = root.opt(key)
                val found = findMediaWithShortcode(child, shortcode, targetId)
                if (found != null) return found
            }
        } else if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val found = findMediaWithShortcode(root.opt(i), shortcode, targetId)
                if (found != null) return found
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
                val u = ver.optString("url")
                val w = ver.optInt("width", 0)
                val h = ver.optInt("height", 0)
                if (u.isNotBlank()) {
                    renditions.add(NativeMediaRendition(url = u, width = w, height = h))
                }
            }
        }

        val directVideoUrl = videoObj.optString("video_url")
        if (directVideoUrl.isNotBlank() && renditions.none { it.url == directVideoUrl }) {
            renditions.add(0, NativeMediaRendition(url = directVideoUrl, width = 0, height = 0))
        }

        if (renditions.isEmpty()) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.NoVideo("此 Instagram 貼文未包含可下載的影片串流")
            )
        }

        val sortedRenditions = renditions.sortedByDescending { it.resolution }
        val urls = sortedRenditions.map { it.url }.distinct()
        val heights = sortedRenditions.map { it.resolution }.filter { it > 0 }.distinct()

        val thumb = videoObj.optString("display_url").ifBlank {
            parentObj?.optString("display_url")
        }

        val owner = videoObj.optJSONObject("owner") ?: parentObj?.optJSONObject("owner")
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
                MetaExtractionError.InvalidUrl("無法從網址中解析 Instagram 貼文代碼，請確認網址是否正確")
            )

        val canonicalUrl = normalizeUrl(url)

        val browserResponse = webClient.fetch(canonicalUrl, MetaWebClient.RequestProfile.BROWSER)
        val html = browserResponse.getOrNull()?.body ?: ""

        var parseResult = parseInstagramPage(html, shortcode, canonicalUrl)

        // If technical failure with browser profile (even if html is non-empty), retry with crawler profile
        if (parseResult is MetaExtractionResult.Failure && parseResult.error is MetaExtractionError.Technical) {
            val crawlerResponse = webClient.fetch(canonicalUrl, MetaWebClient.RequestProfile.CRAWLER)
            val crawlerHtml = crawlerResponse.getOrNull()?.body ?: ""
            if (crawlerHtml.isNotBlank()) {
                parseResult = parseInstagramPage(crawlerHtml, shortcode, canonicalUrl)
            }
        }

        when (parseResult) {
            is MetaExtractionResult.Success -> {
                val media = parseResult.media
                val options = buildQualityOptions(media)
                val info = MediaInfo(
                    sourceUrl = canonicalUrl,
                    title = media.title,
                    platform = Platform.INSTAGRAM,
                    extractor = "NativeInstagram",
                    thumbnailUrl = media.thumbnailUrl,
                    durationSeconds = media.durationSeconds,
                    qualityOptions = options
                )
                Result.success(info)
            }
            is MetaExtractionResult.Failure -> {
                Result.failure(parseResult.error)
            }
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

        val audioUrl = bestRendition?.url ?: media.progressiveVideoUrls.firstOrNull()
        if (audioUrl != null) {
            distinctOptions.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅音訊 (MP3/M4A)",
                    formatSelector = audioUrl,
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
        try {
            if (!destDir.exists()) destDir.mkdirs()

            val cleanTitle = request.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
            val streamUrl = request.qualityOption.formatSelector

            if (request.qualityOption.isAudioOnly) {
                val tempSource = File(destDir, "${UUID.randomUUID()}.source.mp4")
                val finalAudio = File(destDir, "$cleanTitle-${System.currentTimeMillis() % 10000}.mp3")
                try {
                    onStatus("正在下載 Instagram 音訊來源…")
                    val downloadOk = webClient.downloadMediaStream(
                        streamUrl = streamUrl,
                        destination = tempSource,
                        referer = "https://www.instagram.com/",
                        onProgress = onProgress,
                        isCancelled = { isCancelled.get() }
                    )

                    if (!downloadOk) {
                        if (isCancelled.get()) return@withContext Result.failure(InterruptedException("下載已取消"))
                        return@withContext Result.failure(IllegalStateException("下載 Instagram 音訊來源失敗"))
                    }

                    onStatus("正在轉檔為純音訊…")
                    val extracted = MetaFfmpegHelper.extractAudio(context, tempSource, finalAudio)
                    if (extracted && finalAudio.exists() && finalAudio.length() > 0L) {
                        return@withContext Result.success(finalAudio)
                    } else {
                        if (finalAudio.exists()) finalAudio.delete()
                        return@withContext Result.failure(IllegalStateException("FFmpeg 音訊轉檔失敗"))
                    }
                } finally {
                    if (tempSource.exists()) tempSource.delete()
                }
            } else {
                val outputFile = File(destDir, "$cleanTitle-${System.currentTimeMillis() % 10000}.mp4")
                onStatus("正在下載 Instagram 媒體…")
                val downloadOk = webClient.downloadMediaStream(
                    streamUrl = streamUrl,
                    destination = outputFile,
                    referer = "https://www.instagram.com/",
                    onProgress = onProgress,
                    isCancelled = { isCancelled.get() }
                )

                if (!downloadOk) {
                    if (isCancelled.get()) return@withContext Result.failure(InterruptedException("下載已取消"))
                    return@withContext Result.failure(IllegalStateException("下載 Instagram 串流失敗"))
                }

                Result.success(outputFile)
            }
        } catch (e: Exception) {
            if (isCancelled.get() || e is InterruptedException) {
                Result.failure(InterruptedException("下載已取消"))
            } else {
                safeLog("Instagram download failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private fun safeLog(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
