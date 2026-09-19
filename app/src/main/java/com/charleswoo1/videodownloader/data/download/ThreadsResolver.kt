package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.meta.MetaFfmpegHelper
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Secondary fallback extractor and downloader for Threads.
 *
 * NOTE: The primary extractor pipeline uses yt-dlp with the bundled yt-dlp-threads plugin
 * (tribixbite/yt-dlp-threads). This resolver is preserved as a strictly guarded fallback
 * implementation only when the plugin cannot be loaded or extracted.
 */
open class ThreadsResolver(private val context: Context? = null) {

    companion object {
        private const val TAG = "ThreadsResolver"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val CRAWLER_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        private val POST_ID_PATTERN = Pattern.compile("""/(?:post|t)/([A-Za-z0-9_-]+)""")
        private val POST_IN_HTML_PATTERN = Pattern.compile("""/(?:post|t)/([A-Za-z0-9_-]+)""")
        private val SCRIPT_JSON_PATTERN = Pattern.compile(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
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

        fun unescapeXml(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
        }

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
    }

    private val isCancelled = AtomicBoolean(false)
    private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        isCancelled.set(true)
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {}
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

    fun extractPostUrlFromShareHtml(html: String): String? {
        val canonicalMatch = CANONICAL_LINK_PATTERN.matcher(html)
        if (canonicalMatch.find()) {
            val canonical = canonicalMatch.group(1)
            if (!canonical.isNullOrBlank() && (canonical.contains("/post/") || canonical.contains("/t/"))) {
                return normalizeUrl(canonical)
            }
        }
        val ogMatch = OG_URL_PATTERN.matcher(html)
        if (ogMatch.find()) {
            val og = ogMatch.group(1)
            if (!og.isNullOrBlank() && (og.contains("/post/") || og.contains("/t/"))) {
                return normalizeUrl(og)
            }
        }
        val postMatch = POST_IN_HTML_PATTERN.matcher(html)
        if (postMatch.find()) {
            val postCode = postMatch.group(1)
            if (!postCode.isNullOrBlank()) {
                return "https://www.threads.com/post/$postCode"
            }
        }
        return null
    }

    suspend fun resolveShareUrl(url: String): String = withContext(Dispatchers.IO) {
        if (!url.contains("/share/")) {
            return@withContext normalizeUrl(url)
        }

        var currentUrl = url
        var hops = 0
        val maxHops = 5

        while (hops < maxHops) {
            hops++
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", CRAWLER_UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                }

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("/")) {
                            "https://www.threads.com$location"
                        } else {
                            location
                        }
                        val normalized = normalizeUrl(currentUrl)
                        if (normalized.contains("/post/") || normalized.contains("/t/")) {
                            return@withContext normalized
                        }
                        continue
                    }
                } else if (code == 200) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val resolved = extractPostUrlFromShareHtml(html)
                    if (resolved != null) {
                        return@withContext resolved
                    }
                    break
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error following share redirect from $currentUrl", e)
                break
            } finally {
                conn?.disconnect()
            }
        }

        normalizeUrl(currentUrl)
    }

    data class ExtractedThreadsMedia(
        val postId: String,
        val title: String,
        val uploader: String,
        val thumbnailUrl: String?,
        val progressiveVideoUrls: List<String>,
        val dashVideoUrl: String?,
        val dashAudioUrl: String?,
        val heights: List<Int>
    )

    suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            isCancelled.set(false)
            val canonicalUrl = resolveShareUrl(url)
            val postId = extractPostId(canonicalUrl)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("無法從網址解析 Threads 貼文 ID，請確認是否為有效貼文網址")
                )

            val webpage = fetchWebpage(canonicalUrl)
            val mediaData = parseThreadsPage(webpage, postId, canonicalUrl)

            val options = mutableListOf<QualityOption>()
            val progressiveUrl = mediaData.progressiveVideoUrls.firstOrNull()
            val primaryStreamUrl = progressiveUrl ?: run {
                if (mediaData.dashVideoUrl != null && mediaData.dashAudioUrl != null) {
                    "${mediaData.dashVideoUrl}|${mediaData.dashAudioUrl}"
                } else {
                    mediaData.dashVideoUrl
                }
            }
            if (primaryStreamUrl.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("此 Threads 貼文未包含影片內容，或該影片需要登入帳號驗證")
                )
            }

            options.add(
                QualityOption(
                    id = "best",
                    label = "最佳畫質 (推薦)",
                    formatSelector = primaryStreamUrl,
                    isAudioOnly = false
                )
            )

            // Audio only option
            val audioSelector = mediaData.dashAudioUrl ?: (progressiveUrl ?: mediaData.dashVideoUrl ?: "")
            if (audioSelector.isNotBlank()) {
                options.add(
                    QualityOption(
                        id = "audio_only",
                        label = "僅音訊 (MP3/M4A)",
                        formatSelector = audioSelector,
                        isAudioOnly = true
                    )
                )
            }

            // Deduplicate options by id
            val distinctOptions = options.distinctBy { it.id }.toMutableList()

            val mediaInfo = MediaInfo(
                sourceUrl = canonicalUrl,
                title = mediaData.title,
                platform = Platform.THREADS,
                extractor = "Threads",
                thumbnailUrl = mediaData.thumbnailUrl,
                durationSeconds = null,
                qualityOptions = distinctOptions
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Log.e(TAG, "extractMediaInfo failed for $url", e)
            Result.failure(e)
        }
    }

    internal open fun fetchWebpage(targetUrl: String): String {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", CRAWLER_UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Sec-Fetch-Dest", "document")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Site", "none")
            setRequestProperty("Sec-Fetch-User", "?1")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
        }

        return try {
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException("無法連線至 Threads (HTTP $code)")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parseThreadsPage(html: String, postId: String, canonicalUrl: String): ExtractedThreadsMedia {
        val jsonMatcher = SCRIPT_JSON_PATTERN.matcher(html)
        val candidatePosts = mutableListOf<JSONObject>()

        while (jsonMatcher.find()) {
            val rawJson = jsonMatcher.group(1) ?: continue
            try {
                val trimmed = rawJson.trim()
                if (trimmed.startsWith("{")) {
                    collectPosts(JSONObject(trimmed), candidatePosts)
                } else if (trimmed.startsWith("[")) {
                    collectPosts(JSONArray(trimmed), candidatePosts)
                }
            } catch (_: Exception) {}
        }

        var targetPost = candidatePosts.firstOrNull { it.optString("code") == postId }
        if (targetPost == null) {
            // Fallback: search for quoted / nested post having code
            targetPost = candidatePosts.firstOrNull { hasPostId(it, postId) }
        }

        if (targetPost == null) {
            throw IllegalStateException("Threads 貼文 \"$postId\" 解析失敗，可能為私人內容、需要登入驗證或該貼文不存在")
        }

        val progressiveUrls = mutableListOf<String>()
        val heights = mutableSetOf<Int>()
        var dashManifest: String? = null
        var thumbnailUrl: String? = null

        collectMediaFromPost(targetPost, progressiveUrls, heights, { dashManifest = it }, { if (thumbnailUrl == null) thumbnailUrl = it })

        var dashVideoUrl: String? = null
        var dashAudioUrl: String? = null
        if (!dashManifest.isNullOrBlank()) {
            val dashStreams = parseDashManifest(dashManifest!!)
            dashVideoUrl = dashStreams.first
            dashAudioUrl = dashStreams.second
        }

        val userObj = targetPost.optJSONObject("user")
        val username = userObj?.optString("username")?.takeIf { it.isNotBlank() } ?: "threads_user"
        val captionObj = targetPost.optJSONObject("caption")
        val captionText = captionObj?.optString("text")?.takeIf { it.isNotBlank() }
            ?: targetPost.optString("accessibility_caption").takeIf { it.isNotBlank() }
            ?: "Threads 影片"

        val cleanCaption = captionText.replace(Regex("""[\r\n\t\\/:*?"<>|]+"""), " ").trim()
        val title = "Threads - @$username - ${cleanCaption.take(50)}"

        return ExtractedThreadsMedia(
            postId = postId,
            title = title,
            uploader = "@$username",
            thumbnailUrl = thumbnailUrl,
            progressiveVideoUrls = progressiveUrls,
            dashVideoUrl = dashVideoUrl,
            dashAudioUrl = dashAudioUrl,
            heights = heights.sortedDescending()
        )
    }

    private fun hasPostId(obj: JSONObject, targetId: String): Boolean {
        if (obj.optString("code") == targetId) return true
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = obj.opt(key)
            if (child is JSONObject && hasPostId(child, targetId)) return true
            if (child is JSONArray) {
                for (i in 0 until child.length()) {
                    val elem = child.opt(i)
                    if (elem is JSONObject && hasPostId(elem, targetId)) return true
                }
            }
        }
        return false
    }

    private fun collectPosts(obj: Any?, out: MutableList<JSONObject>) {
        if (obj == null) return
        when (obj) {
            is JSONObject -> {
                if (obj.has("code")) {
                    out.add(obj)
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectPosts(obj.opt(key), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    collectPosts(obj.opt(i), out)
                }
            }
        }
    }

    private fun collectMediaFromPost(
        obj: Any?,
        progressiveUrls: MutableList<String>,
        heights: MutableSet<Int>,
        onDashManifest: (String) -> Unit,
        onThumbnail: (String) -> Unit
    ) {
        if (obj == null) return
        when (obj) {
            is JSONObject -> {
                val dash = obj.optString("video_dash_manifest")
                if (dash.isNotBlank()) {
                    onDashManifest(dash)
                }

                val videoVersions = obj.optJSONArray("video_versions")
                if (videoVersions != null) {
                    for (i in 0 until videoVersions.length()) {
                        val v = videoVersions.optJSONObject(i) ?: continue
                        val vUrl = decodeUrl(v.optString("url"))
                        val height = v.optInt("height", 0)
                        if (vUrl.startsWith("http")) {
                            if (!progressiveUrls.contains(vUrl)) {
                                progressiveUrls.add(vUrl)
                            }
                            if (height > 0) heights.add(height)
                        }
                    }
                }

                val imageVersions = obj.optJSONObject("image_versions2")
                val candidates = imageVersions?.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstThumb = candidates.optJSONObject(0)?.optString("url")
                    if (!firstThumb.isNullOrBlank()) {
                        onThumbnail(decodeUrl(firstThumb))
                    }
                }

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectMediaFromPost(obj.opt(key), progressiveUrls, heights, onDashManifest, onThumbnail)
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    collectMediaFromPost(obj.opt(i), progressiveUrls, heights, onDashManifest, onThumbnail)
                }
            }
        }
    }

    private fun decodeUrl(value: String): String {
        return normalizeCdnUrl(value)
    }

    private data class DashCandidate(
        val url: String,
        val width: Int = 0,
        val height: Int = 0,
        val bandwidth: Long = 0L
    )

    fun parseDashManifest(manifest: String): Pair<String?, String?> {
        val videoCandidates = mutableListOf<DashCandidate>()
        val audioCandidates = mutableListOf<DashCandidate>()

        val adaptationMatcher = DASH_ADAPTATION_PATTERN.matcher(manifest)
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
            val baseMatcher = DASH_BASE_URL_PATTERN.matcher(manifest)
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

    suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val cleanTitle = request.title.replace(Regex("""[^\w\u4e00-\u9fa5\s.-]"""), "_").trim()
        val outputFile = File(destDir, "$cleanTitle.mp4")
        try {
            if (!destDir.exists()) destDir.mkdirs()

            val targetStreamUrl = request.qualityOption.formatSelector

            if (targetStreamUrl.contains("|") && !request.qualityOption.isAudioOnly) {
                val parts = targetStreamUrl.split("|", limit = 2)
                val videoUrl = parts[0]
                val audioUrl = parts[1]
                val tempVideo = File(destDir, "${cleanTitle}_video.part")
                val tempAudio = File(destDir, "${cleanTitle}_audio.part")
                try {
                    onStatus("正在下載 Threads 視訊串流…")
                    val vOk = downloadStream(videoUrl, tempVideo) { p, eta, spd ->
                        onProgress(p * 0.7f, eta, spd)
                    }
                    if (!vOk) {
                        return@withContext if (isCancelled.get()) Result.failure(InterruptedException("下載已取消"))
                        else Result.failure(IllegalStateException("下載 Threads 視訊失敗"))
                    }

                    onStatus("正在下載 Threads 音訊串流…")
                    val aOk = downloadStream(audioUrl, tempAudio) { p, eta, spd ->
                        onProgress(70f + p * 0.2f, eta, spd)
                    }
                    if (!aOk) {
                        return@withContext if (isCancelled.get()) Result.failure(InterruptedException("下載已取消"))
                        else Result.failure(IllegalStateException("下載 Threads 音訊失敗"))
                    }

                    onStatus("正在合併音視訊…")
                    val merged = mergeVideoAndAudioWithFFmpeg(tempVideo, tempAudio, outputFile)
                    if (merged && outputFile.exists()) {
                        onProgress(100f, 0L, null)
                        return@withContext Result.success(outputFile)
                    } else {
                        if (outputFile.exists()) outputFile.delete()
                        return@withContext Result.failure(IllegalStateException("FFmpeg 視訊與音訊合併失敗"))
                    }
                } finally {
                    tempVideo.delete()
                    tempAudio.delete()
                }
            } else {
                onStatus("正在開始下載 Threads 媒體…")
                val downloadSuccess = downloadStream(targetStreamUrl, outputFile, onProgress)
                if (!downloadSuccess) {
                    outputFile.delete()
                    if (isCancelled.get()) {
                        return@withContext Result.failure(InterruptedException("下載已取消"))
                    }
                    return@withContext Result.failure(IllegalStateException("下載 Threads 串流失敗"))
                }

                if (request.qualityOption.isAudioOnly) {
                    onStatus("正在轉檔為純音訊…")
                    val audioFile = File(destDir, "$cleanTitle.mp3")
                    val extracted = extractAudioWithFFmpeg(outputFile, audioFile)
                    outputFile.delete()
                    if (extracted && audioFile.exists()) {
                        return@withContext Result.success(audioFile)
                    } else {
                        if (audioFile.exists()) audioFile.delete()
                        return@withContext Result.failure(IllegalStateException("FFmpeg 音訊轉檔失敗"))
                    }
                }

                Result.success(outputFile)
            }
        } catch (e: Exception) {
            outputFile.delete()
            if (isCancelled.get() || e is InterruptedException) {
                Result.failure(InterruptedException("下載已取消"))
            } else {
                Log.e(TAG, "Threads download failed", e)
                Result.failure(e)
            }
        }
    }

    internal open fun downloadStream(
        streamUrl: String,
        destination: File,
        onProgress: (Float, Long?, String?) -> Unit
    ): Boolean {
        val partFile = File(destination.parentFile, "${destination.name}.part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Referer", "https://www.threads.net/")
                setRequestProperty("Origin", "https://www.threads.net")
            }
            activeConnection = conn

            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("伺服器回應錯誤 (HTTP $code)")
            }

            val totalBytes = conn.contentLengthLong
            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(partFile)

            val buffer = ByteArray(64 * 1024)
            var bytesDownloaded = 0L
            val startTime = System.currentTimeMillis()

            outputStream.use { out ->
                inputStream.use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled.get()) {
                            partFile.delete()
                            return false
                        }
                        out.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000f
                        val progress = if (totalBytes > 0) (bytesDownloaded * 100f / totalBytes) else 0f
                        val speedBytesPerSec = if (elapsedSeconds > 0) (bytesDownloaded / elapsedSeconds) else 0f
                        val speedText = formatSpeed(speedBytesPerSec)
                        val eta = if (speedBytesPerSec > 0 && totalBytes > bytesDownloaded) {
                            ((totalBytes - bytesDownloaded) / speedBytesPerSec).toLong()
                        } else null

                        onProgress(progress, eta, speedText)
                    }
                }
            }

            if (destination.exists()) destination.delete()
            return partFile.renameTo(destination)
        } catch (e: Exception) {
            partFile.delete()
            if (isCancelled.get()) return false
            throw e
        } finally {
            conn?.disconnect()
            activeConnection = null
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MiB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.1f KiB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }

    private fun extractAudioWithFFmpeg(source: File, target: File): Boolean {
        return MetaFfmpegHelper.extractAudio(context, source, target)
    }

    private fun mergeVideoAndAudioWithFFmpeg(video: File, audio: File, target: File): Boolean {
        return MetaFfmpegHelper.mergeVideoAndAudio(context, video, audio, target)
    }
}