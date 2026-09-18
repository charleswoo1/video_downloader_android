package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
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
import java.net.URLDecoder
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

        private val POST_ID_PATTERN = Pattern.compile("""/(?:post|t)/([A-Za-z0-9_-]+)""")
        private val SCRIPT_JSON_PATTERN = Pattern.compile(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val CANONICAL_LINK_PATTERN = Pattern.compile(
            """<link[^>]*rel=["']canonical["'][^>]*href=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val OG_URL_PATTERN = Pattern.compile(
            """<meta[^>]*property=["']og:url["'][^>]*content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val DASH_BASE_URL_PATTERN = Pattern.compile(
            """<BaseURL\b[^>]*>(.*?)</BaseURL\s*>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val DASH_ADAPTATION_PATTERN = Pattern.compile(
            """<AdaptationSet\b(?<attrs>[^>]*)>(?<body>.*?)</AdaptationSet\s*>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        fun normalizeCdnUrl(rawUrl: String): String {
            val unescapedSlashes = rawUrl.replace("\\/", "/")
            val sb = StringBuilder()
            var i = 0
            while (i < unescapedSlashes.length) {
                if (unescapedSlashes[i] == '\\' && i + 5 < unescapedSlashes.length && unescapedSlashes[i + 1] == 'u') {
                    val hex = unescapedSlashes.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                sb.append(unescapedSlashes[i])
                i++
            }
            val unicodeDecoded = sb.toString()
            return try {
                URLDecoder.decode(unicodeDecoded, "UTF-8")
            } catch (_: Exception) {
                unicodeDecoded
            }
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

    fun parseThreadsPage(html: String, targetShortcode: String, canonicalUrl: String): MetaExtractionResult {
        // 1. Check for deleted or private post
        if (html.contains("Post \"$targetShortcode\" was not found in the page data", ignoreCase = true) ||
            html.contains("deleted, private, login-gated", ignoreCase = true) ||
            html.contains("Sorry, this page isn't available.", ignoreCase = true)
        ) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Restricted(
                    RestrictionReason.DELETED_OR_PRIVATE,
                    "Threads 貼文不存在、設為私人內容或需要登入帳號驗證"
                )
            )
        }

        // 2. Scan script tags for JSON payload
        val candidates = mutableListOf<JSONObject>()
        val matcher = SCRIPT_JSON_PATTERN.matcher(html)
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

        // 3. Find target post strictly matching code == targetShortcode
        var targetPost: JSONObject? = null
        for (candidate in candidates) {
            targetPost = findPostByCode(candidate, targetShortcode)
            if (targetPost != null) break
        }

        // Target post isolation: If target post was not found, STRICTLY REFUSE to use unrelated feed posts
        if (targetPost == null) {
            return MetaExtractionResult.Failure(
                MetaExtractionError.Technical(
                    "Threads 已找到貼文連結，但目前頁面未提供可解析的目標媒體資料。"
                )
            )
        }

        return extractMediaFromPost(targetPost, targetShortcode, canonicalUrl)
    }

    private fun findPostByCode(root: Any?, targetCode: String): JSONObject? {
        if (root == null) return null
        if (root is JSONObject) {
            val code = root.optString("code")
            if (code == targetCode) {
                return root
            }

            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = root.opt(key)
                val found = findPostByCode(child, targetCode)
                if (found != null) return found
            }
        } else if (root is JSONArray) {
            for (i in 0 until root.length()) {
                val found = findPostByCode(root.opt(i), targetCode)
                if (found != null) return found
            }
        }
        return null
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

    private fun parseDashManifest(manifestXml: String): Pair<String?, String?> {
        var videoUrl: String? = null
        var audioUrl: String? = null

        val adaptationMatcher = DASH_ADAPTATION_PATTERN.matcher(manifestXml)
        while (adaptationMatcher.find()) {
            val attrs = adaptationMatcher.group(1) ?: ""
            val body = adaptationMatcher.group(2) ?: ""

            val isVideo = attrs.contains("contentType=\"video\"") || attrs.contains("mimeType=\"video/")
            val isAudio = attrs.contains("contentType=\"audio\"") || attrs.contains("mimeType=\"audio/")

            val baseMatcher = DASH_BASE_URL_PATTERN.matcher(body)
            if (baseMatcher.find()) {
                val url = baseMatcher.group(1)?.trim() ?: continue
                if (url.startsWith("http")) {
                    val normalized = normalizeCdnUrl(url)
                    if (isVideo && videoUrl == null) {
                        videoUrl = normalized
                    } else if (isAudio && audioUrl == null) {
                        audioUrl = normalized
                    }
                }
            }
        }
        return Pair(videoUrl, audioUrl)
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
            return MetaExtractionResult.Failure(
                MetaExtractionError.NoVideo("此 Threads 貼文未包含任何影片內容 (可能為純文字或純圖片貼文)")
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
        if (candidates != null && candidates.length() > 0) {
            val bestCandidate = candidates.optJSONObject(0)?.optString("url")
            if (!bestCandidate.isNullOrBlank()) return normalizeCdnUrl(bestCandidate)
        }
        return null
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveShareUrl(url)
        val shortcode = extractPostId(resolvedUrl)
            ?: return@withContext Result.failure(
                PlatformExtractionError.PageVariantUnsupported("無法從網址中解析 Threads 貼文代碼，請確認網址格式")
            )

        val canonicalUrl = normalizeUrl(resolvedUrl)
        val profileSteps = mutableListOf<String>()

        // Step 1: DESKTOP_NAVIGATION
        profileSteps.add("DESKTOP")
        val desktopResp = httpSession.fetch(canonicalUrl, RequestProfile.DESKTOP_NAVIGATION)
        val desktopHtml = desktopResp.getOrNull()?.body ?: ""

        var parseResult = parseThreadsPage(desktopHtml, shortcode, canonicalUrl)

        // Step 2: Escalation to MOBILE_NAVIGATION on technical failure
        if (parseResult is MetaExtractionResult.Failure && parseResult.error is MetaExtractionError.Technical) {
            profileSteps.add("MOBILE")
            val mobileResp = httpSession.fetch(canonicalUrl, RequestProfile.MOBILE_NAVIGATION)
            val mobileHtml = mobileResp.getOrNull()?.body ?: ""
            if (mobileHtml.isNotBlank()) {
                parseResult = parseThreadsPage(mobileHtml, shortcode, canonicalUrl)
            }
        }

        // Step 3: Escalation to CRAWLER_NAVIGATION if still technical failure
        if (parseResult is MetaExtractionResult.Failure && parseResult.error is MetaExtractionError.Technical) {
            profileSteps.add("CRAWLER")
            val crawlerResp = httpSession.fetch(canonicalUrl, RequestProfile.CRAWLER_NAVIGATION)
            val crawlerHtml = crawlerResp.getOrNull()?.body ?: ""
            if (crawlerHtml.isNotBlank()) {
                parseResult = parseThreadsPage(crawlerHtml, shortcode, canonicalUrl)
            }
        }

        lastProfileSequence = profileSteps

        when (parseResult) {
            is MetaExtractionResult.Success -> {
                val media = parseResult.media
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
            val videoOk = httpSession.downloadMediaStream(videoUrl, tempVideo, "https://www.threads.net/", onProgress, { isCancelled.get() })
            if (!videoOk || isCancelled.get()) {
                tempVideo.delete()
                tempAudio.delete()
                return@withContext Result.failure(InterruptedException("下載視訊已取消或失敗"))
            }

            if (audioUrl != null) {
                onStatus("正在下載 DASH 音訊串流...")
                val audioOk = httpSession.downloadMediaStream(audioUrl, tempAudio, "https://www.threads.net/", { _, _, _ -> }, { isCancelled.get() })
                if (!audioOk || isCancelled.get()) {
                    tempVideo.delete()
                    tempAudio.delete()
                    return@withContext Result.failure(InterruptedException("下載音訊已取消或失敗"))
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
                tempVideo.renameTo(destinationFile)
                return@withContext Result.success(destinationFile)
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
