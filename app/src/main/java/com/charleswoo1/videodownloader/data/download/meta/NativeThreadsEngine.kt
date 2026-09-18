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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Native Threads extraction and download engine.
 *
 * Enforces strict target-post isolation so that recommendation or feed posts
 * are never chosen when the target post is absent. Directly parses progressive
 * and DASH streams and merges with bundled FFmpeg when required.
 */
class NativeThreadsEngine(
    private val context: Context? = null,
    private val webClient: MetaWebClient = MetaWebClient()
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
    }

    override val name: String = ENGINE_NAME

    private val isCancelled = AtomicBoolean(false)

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

        val redirected = webClient.resolveRedirectUrl(url, MetaWebClient.RequestProfile.CRAWLER)
        val targetUrl = redirected.getOrDefault(url)
        val normalized = normalizeUrl(targetUrl)
        if (normalized.contains("/post/") || normalized.contains("/t/")) {
            return@withContext normalized
        }

        // If redirect did not yield canonical, fetch share page HTML to inspect canonical meta tags
        val shareHtml = webClient.fetch(url, MetaWebClient.RequestProfile.CRAWLER).getOrNull()?.body ?: ""
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

    private fun extractMediaFromPost(
        post: JSONObject,
        targetShortcode: String,
        canonicalUrl: String
    ): MetaExtractionResult {
        val user = post.optJSONObject("user")
        val uploader = user?.optString("username")?.ifBlank { "Threads" } ?: "Threads"

        val captionObj = post.optJSONObject("caption")
        val titleText = captionObj?.optString("text")?.take(100)
        val title = if (!titleText.isNullOrBlank()) titleText else "Threads 影片 ($targetShortcode)"

        val thumb = post.optJSONObject("image_versions2")
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("url")

        // Case A: Direct progressive video_versions in target post
        val videoVersions = post.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            val progressiveUrls = mutableListOf<String>()
            val heights = mutableListOf<Int>()
            for (i in 0 until videoVersions.length()) {
                val v = videoVersions.optJSONObject(i) ?: continue
                val u = v.optString("url")
                val w = v.optInt("width", 0)
                val h = v.optInt("height", 0)
                val res = if (w > 0 && h > 0) minOf(w, h) else if (h > 0) h else w
                if (u.isNotBlank()) {
                    progressiveUrls.add(u)
                    if (res > 0) heights.add(res)
                }
            }
            if (progressiveUrls.isNotEmpty()) {
                return MetaExtractionResult.Success(
                    ExtractedMetaMedia(
                        postId = targetShortcode,
                        canonicalUrl = canonicalUrl,
                        title = title,
                        uploader = uploader,
                        thumbnailUrl = thumb,
                        progressiveVideoUrls = progressiveUrls,
                        heights = heights.distinct().sortedDescending()
                    )
                )
            }
        }

        // Case B: Carousel media containing video
        val carousel = post.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val cItem = carousel.optJSONObject(i) ?: continue
                val cVersions = cItem.optJSONArray("video_versions")
                if (cVersions != null && cVersions.length() > 0) {
                    val progressiveUrls = mutableListOf<String>()
                    val heights = mutableListOf<Int>()
                    for (j in 0 until cVersions.length()) {
                        val v = cVersions.optJSONObject(j) ?: continue
                        val u = v.optString("url")
                        val w = v.optInt("width", 0)
                        val h = v.optInt("height", 0)
                        val res = if (w > 0 && h > 0) minOf(w, h) else if (h > 0) h else w
                        if (u.isNotBlank()) {
                            progressiveUrls.add(u)
                            if (res > 0) heights.add(res)
                        }
                    }
                    if (progressiveUrls.isNotEmpty()) {
                        return MetaExtractionResult.Success(
                            ExtractedMetaMedia(
                                postId = targetShortcode,
                                canonicalUrl = canonicalUrl,
                                title = title,
                                uploader = uploader,
                                thumbnailUrl = thumb,
                                progressiveVideoUrls = progressiveUrls,
                                heights = heights.distinct().sortedDescending()
                            )
                        )
                    }
                }
            }
        }

        // Case C: Quoted/reposted post wrapped inside target post
        val quotedPost = post.optJSONObject("text_post_app_info")
            ?.optJSONObject("share_info")
            ?.optJSONObject("quoted_post")
        if (quotedPost != null) {
            val qVersions = quotedPost.optJSONArray("video_versions")
            if (qVersions != null && qVersions.length() > 0) {
                val progressiveUrls = mutableListOf<String>()
                val heights = mutableListOf<Int>()
                for (i in 0 until qVersions.length()) {
                    val v = qVersions.optJSONObject(i) ?: continue
                    val u = v.optString("url")
                    val w = v.optInt("width", 0)
                    val h = v.optInt("height", 0)
                    val res = if (w > 0 && h > 0) minOf(w, h) else if (h > 0) h else w
                    if (u.isNotBlank()) {
                        progressiveUrls.add(u)
                        if (res > 0) heights.add(res)
                    }
                }
                if (progressiveUrls.isNotEmpty()) {
                    return MetaExtractionResult.Success(
                        ExtractedMetaMedia(
                            postId = targetShortcode,
                            canonicalUrl = canonicalUrl,
                            title = title,
                            uploader = uploader,
                            thumbnailUrl = thumb,
                            progressiveVideoUrls = progressiveUrls,
                            heights = heights.distinct().sortedDescending()
                        )
                    )
                }
            }
        }

        // Case D: DASH manifest
        val dashManifest = post.optString("video_dash_manifest")
        if (dashManifest.isNotBlank()) {
            val dashUrls = extractDashUrls(dashManifest)
            if (dashUrls != null) {
                return MetaExtractionResult.Success(
                    ExtractedMetaMedia(
                        postId = targetShortcode,
                        canonicalUrl = canonicalUrl,
                        title = title,
                        uploader = uploader,
                        thumbnailUrl = thumb,
                        progressiveVideoUrls = emptyList(),
                        dashVideoUrl = dashUrls.first,
                        dashAudioUrl = dashUrls.second,
                        heights = listOf(1080)
                    )
                )
            }
        }

        // Target post confirmed to contain no video
        return MetaExtractionResult.Failure(
            MetaExtractionError.NoVideo("此 Threads 貼文未包含任何影片 (可能為純文字或純圖片)")
        )
    }

    private fun extractDashUrls(dashXml: String): Pair<String, String>? {
        val adaptationMatcher = DASH_ADAPTATION_PATTERN.matcher(dashXml)
        var videoUrl: String? = null
        var audioUrl: String? = null

        while (adaptationMatcher.find()) {
            val attrs = adaptationMatcher.group("attrs") ?: ""
            val body = adaptationMatcher.group("body") ?: ""
            val baseMatcher = DASH_BASE_URL_PATTERN.matcher(body)
            if (baseMatcher.find()) {
                val url = baseMatcher.group(1)?.replace("&amp;", "&")?.trim() ?: continue
                if (attrs.contains("video", ignoreCase = true) && videoUrl == null) {
                    videoUrl = url
                } else if (attrs.contains("audio", ignoreCase = true) && audioUrl == null) {
                    audioUrl = url
                }
            }
        }

        if (videoUrl != null && audioUrl != null) {
            return Pair(videoUrl, audioUrl)
        }
        return null
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val canonicalUrl = resolveShareUrl(url)
        val postId = extractPostId(canonicalUrl)
            ?: return@withContext Result.failure(
                MetaExtractionError.InvalidUrl("無法從網址解析 Threads 貼文 ID，請確認是否為有效貼文網址")
            )

        val response = webClient.fetch(canonicalUrl, MetaWebClient.RequestProfile.BROWSER)
        var html = response.getOrNull()?.body ?: ""

        var parseResult = parseThreadsPage(html, postId, canonicalUrl)

        if (parseResult is MetaExtractionResult.Failure && parseResult.error.canFallback && html.isBlank()) {
            val crawlerResponse = webClient.fetch(canonicalUrl, MetaWebClient.RequestProfile.CRAWLER)
            val crawlerHtml = crawlerResponse.getOrNull()?.body ?: ""
            if (crawlerHtml.isNotBlank()) {
                parseResult = parseThreadsPage(crawlerHtml, postId, canonicalUrl)
            }
        }

        when (parseResult) {
            is MetaExtractionResult.Success -> {
                val media = parseResult.media
                val options = buildQualityOptions(media)
                val info = MediaInfo(
                    sourceUrl = canonicalUrl,
                    title = media.title,
                    platform = Platform.THREADS,
                    extractor = "NativeThreads",
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
        val primaryStreamUrl = media.progressiveVideoUrls.firstOrNull() ?: run {
            if (media.dashVideoUrl != null && media.dashAudioUrl != null) {
                "${media.dashVideoUrl}|${media.dashAudioUrl}"
            } else {
                media.dashVideoUrl ?: ""
            }
        }

        options.add(
            QualityOption(
                id = "best",
                label = "最佳畫質 (推薦)",
                formatSelector = primaryStreamUrl
            )
        )

        for (h in media.heights) {
            when {
                h >= 1080 -> options.add(QualityOption("1080p", "1080p Full HD", primaryStreamUrl))
                h in 720..1079 -> options.add(QualityOption("720p", "720p HD", primaryStreamUrl))
                h in 480..719 -> options.add(QualityOption("480p", "480p 標清", primaryStreamUrl))
                h in 360..479 -> options.add(QualityOption("360p", "360p 流暢", primaryStreamUrl))
            }
        }

        val distinctOptions = options.distinctBy { it.id }.toMutableList()

        val audioSelector = media.dashAudioUrl ?: (media.progressiveVideoUrls.firstOrNull() ?: media.dashVideoUrl ?: "")
        if (audioSelector.isNotBlank()) {
            distinctOptions.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅音訊 (MP3/M4A)",
                    formatSelector = audioSelector,
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
            val ext = if (request.qualityOption.isAudioOnly) "mp3" else "mp4"
            val outputFile = File(destDir, "$cleanTitle-${System.currentTimeMillis() % 10000}.$ext")

            val targetStreamUrl = request.qualityOption.formatSelector

            // DASH dual stream
            if (targetStreamUrl.contains("|") && !request.qualityOption.isAudioOnly) {
                val parts = targetStreamUrl.split("|", limit = 2)
                val videoUrl = parts[0]
                val audioUrl = parts[1]
                val tempVideo = File(destDir, "${cleanTitle}_video.part")
                val tempAudio = File(destDir, "${cleanTitle}_audio.part")

                try {
                    onStatus("正在下載 Threads 視訊串流…")
                    val vOk = webClient.downloadMediaStream(videoUrl, tempVideo, "https://www.threads.com/", { p, eta, spd ->
                        onProgress(p * 0.7f, eta, spd)
                    }, { isCancelled.get() })

                    if (!vOk) {
                        return@withContext if (isCancelled.get()) Result.failure(InterruptedException("下載已取消"))
                        else Result.failure(IllegalStateException("下載 Threads 視訊失敗"))
                    }

                    onStatus("正在下載 Threads 音訊串流…")
                    val aOk = webClient.downloadMediaStream(audioUrl, tempAudio, "https://www.threads.com/", { p, eta, spd ->
                        onProgress(70f + p * 0.2f, eta, spd)
                    }, { isCancelled.get() })

                    if (!aOk) {
                        return@withContext if (isCancelled.get()) Result.failure(InterruptedException("下載已取消"))
                        else Result.failure(IllegalStateException("下載 Threads 音訊失敗"))
                    }

                    onStatus("正在合併音視訊…")
                    val merged = MetaFfmpegHelper.mergeVideoAndAudio(context, tempVideo, tempAudio, outputFile)
                    if (merged && outputFile.exists()) {
                        onProgress(100f, 0L, null)
                        return@withContext Result.success(outputFile)
                    } else {
                        if (outputFile.exists()) outputFile.delete()
                        tempVideo.renameTo(outputFile)
                        return@withContext Result.success(outputFile)
                    }
                } finally {
                    tempVideo.delete()
                    tempAudio.delete()
                }
            } else {
                onStatus("正在開始下載 Threads 媒體…")
                val downloadSuccess = webClient.downloadMediaStream(
                    streamUrl = targetStreamUrl,
                    destination = outputFile,
                    referer = "https://www.threads.com/",
                    onProgress = onProgress,
                    isCancelled = { isCancelled.get() }
                )

                if (!downloadSuccess) {
                    if (isCancelled.get()) return@withContext Result.failure(InterruptedException("下載已取消"))
                    return@withContext Result.failure(IllegalStateException("下載 Threads 串流失敗"))
                }

                if (request.qualityOption.isAudioOnly) {
                    onStatus("正在轉檔為純音訊…")
                    val audioFile = File(destDir, "$cleanTitle-${System.currentTimeMillis() % 10000}.mp3")
                    val extracted = MetaFfmpegHelper.extractAudio(context, outputFile, audioFile)
                    if (extracted && audioFile.exists()) {
                        outputFile.delete()
                        return@withContext Result.success(audioFile)
                    }
                }

                Result.success(outputFile)
            }
        } catch (e: Exception) {
            if (isCancelled.get() || e is InterruptedException) {
                Result.failure(InterruptedException("下載已取消"))
            } else {
                safeLog("Threads download failed: ${e.message}")
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
