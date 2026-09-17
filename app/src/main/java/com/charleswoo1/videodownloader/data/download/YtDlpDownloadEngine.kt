package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import com.charleswoo1.videodownloader.domain.url.PlatformDetector
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class YtDlpDownloadEngine(private val context: Context) : DownloadEngine {

    companion object {
        private const val TAG = "YtDlpDownloadEngine"
    }

    private val threadsResolver = ThreadsResolver(context)
    private var activeProcessId: String? = null
    private val cancelledProcessIds = ConcurrentHashMap.newKeySet<String>()

    override fun isInitialized(): Boolean {
        return DownloadRepository.isInitialized
    }

    override fun getRuntimeVersion(): String? {
        return try {
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun updateRuntime(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            val ver = getRuntimeVersion() ?: "unknown"
            Log.i(TAG, "[Diagnostics] yt-dlp runtime update status: $status, active version: $ver")
            Result.success(ver)
        } catch (e: Exception) {
            val ver = getRuntimeVersion() ?: "bundled"
            Log.w(TAG, "[Diagnostics] yt-dlp update to latest stable skipped/failed, keeping version: $ver", e)
            Result.failure(e)
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val platform = PlatformDetector.detect(url)
            if (platform == Platform.THREADS) {
                return@withContext threadsResolver.extractMediaInfo(url)
            }

            if (!isInitialized()) {
                return@withContext Result.failure(
                    IllegalStateException("yt-dlp 引擎尚未初始化，請重新開啟應用程式")
                )
            }

            val runtimeVer = getRuntimeVersion() ?: "bundled"
            Log.d(TAG, "[Diagnostics] Analyzing $url with yt-dlp runtime version: $runtimeVer")

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")

            val videoInfo = YoutubeDL.getInstance().getInfo(request)

            val options = mutableListOf<QualityOption>()
            options.add(
                QualityOption(
                    id = "best",
                    label = "最佳畫質 (推薦)",
                    formatSelector = "bestvideo+bestaudio/best"
                )
            )

            // Extract available heights from formats
            val availableHeights = videoInfo.formats
                ?.mapNotNull { it.height }
                ?.filter { it > 0 }
                ?.toSet()
                ?: emptySet()

            if (availableHeights.any { it >= 1080 }) {
                options.add(
                    QualityOption(
                        id = "1080p",
                        label = "1080p Full HD",
                        formatSelector = "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
                    )
                )
            }
            if (availableHeights.any { it in 720..1079 }) {
                options.add(
                    QualityOption(
                        id = "720p",
                        label = "720p HD",
                        formatSelector = "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
                    )
                )
            }
            if (availableHeights.any { it in 480..719 }) {
                options.add(
                    QualityOption(
                        id = "480p",
                        label = "480p 標清",
                        formatSelector = "bestvideo[height<=480]+bestaudio/best[height<=480]/best"
                    )
                )
            }
            if (availableHeights.any { it in 360..479 }) {
                options.add(
                    QualityOption(
                        id = "360p",
                        label = "360p 流暢",
                        formatSelector = "bestvideo[height<=360]+bestaudio/best[height<=360]/best"
                    )
                )
            }

            // Audio-only option
            options.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅音訊 (MP3/M4A)",
                    formatSelector = "bestaudio/best",
                    isAudioOnly = true
                )
            )

            val mediaInfo = MediaInfo(
                sourceUrl = url,
                title = videoInfo.title ?: "未命名影片",
                platform = platform,
                extractor = videoInfo.extractor ?: platform.displayName,
                thumbnailUrl = videoInfo.thumbnail,
                durationSeconds = videoInfo.duration?.toLong(),
                qualityOptions = options
            )

            Result.success(mediaInfo)
        } catch (e: YoutubeDLException) {
            Log.w(TAG, "extractMediaInfo failed: ${e.message}")
            Result.failure(mapYoutubeDLError(e))
        } catch (e: Exception) {
            Log.e(TAG, "extractMediaInfo unexpected error", e)
            Result.failure(e)
        }
    }

    override suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(request.url)
        if (platform == Platform.THREADS) {
            return@withContext threadsResolver.download(request, destDir, onProgress, onStatus)
        }

        val processId = UUID.randomUUID().toString()
        activeProcessId = processId

        try {
            if (cancelledProcessIds.contains(processId) || !currentCoroutineContext().isActive) {
                return@withContext Result.failure(InterruptedException("下載已取消"))
            }

            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val startTime = System.currentTimeMillis()
            val ytRequest = YoutubeDLRequest(request.url)
            ytRequest.addOption("--no-playlist")
            ytRequest.addOption("--no-mtime")

            val outTemplate = "${destDir.absolutePath}/%(title).120B-%(id)s.%(ext)s"
            ytRequest.addOption("-o", outTemplate)

            if (request.qualityOption.isAudioOnly) {
                ytRequest.addOption("-x")
                ytRequest.addOption("--audio-format", "mp3")
                ytRequest.addOption("-f", "bestaudio/best")
            } else {
                ytRequest.addOption("-f", request.qualityOption.formatSelector)
                ytRequest.addOption("--merge-output-format", "mp4")
            }

            onStatus("正在開始下載…")

            if (cancelledProcessIds.contains(processId) || !currentCoroutineContext().isActive) {
                return@withContext Result.failure(InterruptedException("下載已取消"))
            }

            val response = YoutubeDL.getInstance().execute(ytRequest, processId) { progress, etaInSeconds, line ->
                if (cancelledProcessIds.contains(processId)) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(processId)
                    } catch (_: Exception) {}
                    return@execute
                }
                val speed = extractSpeed(line)
                if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) {
                    onStatus("正在合併音視訊與後製處理…")
                }
                onProgress(progress, etaInSeconds, speed)
            }

            if (cancelledProcessIds.contains(processId) || !currentCoroutineContext().isActive) {
                return@withContext Result.failure(InterruptedException("下載已取消"))
            }

            Log.d(TAG, "Download finished. Exit code: ${response.exitCode}")

            // Locate the generated file in destDir
            val downloadedFile = destDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.lastModified() >= (startTime - 5000) }
                ?.maxByOrNull { it.lastModified() }

            if (downloadedFile != null && downloadedFile.exists()) {
                Result.success(downloadedFile)
            } else {
                Result.failure(IllegalStateException("找不到已下載的媒體檔案"))
            }
        } catch (e: YoutubeDLException) {
            if (cancelledProcessIds.contains(processId) || !currentCoroutineContext().isActive) {
                Log.d(TAG, "Process was cancelled during download: $processId")
                Result.failure(InterruptedException("下載已取消"))
            } else {
                Log.w(TAG, "download failed: ${e.message}")
                Result.failure(mapYoutubeDLError(e))
            }
        } catch (e: Exception) {
            if (cancelledProcessIds.contains(processId) || e is InterruptedException || !currentCoroutineContext().isActive) {
                Result.failure(InterruptedException("下載已取消"))
            } else {
                Log.e(TAG, "download error", e)
                Result.failure(e)
            }
        } finally {
            cancelledProcessIds.remove(processId)
            if (activeProcessId == processId) {
                activeProcessId = null
            }
        }
    }

    override fun cancelDownload() {
        threadsResolver.cancel()
        val processId = activeProcessId
        if (processId != null) {
            cancelledProcessIds.add(processId)
            try {
                YoutubeDL.getInstance().destroyProcessById(processId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy process $processId", e)
            }
            activeProcessId = null
        }
    }

    private fun extractSpeed(line: String?): String? {
        if (line == null) return null
        val match = Regex("""at\s+([0-9.]+[a-zA-Z]+/s)""").find(line)
        return match?.groupValues?.getOrNull(1)
    }

    private fun mapYoutubeDLError(e: YoutubeDLException): Exception {
        val msg = e.message ?: ""
        val isInstagram = msg.contains("instagram", ignoreCase = true)
        return when {
            msg.contains("Private video", ignoreCase = true) || msg.contains("This video is private", ignoreCase = true) ->
                Exception("此影片設為私人內容，無法存取")
            msg.contains("checkpoint_required", ignoreCase = true) ->
                Exception("Instagram 要求安全驗證 (checkpoint)，無法直接下載")
            msg.contains("login_required", ignoreCase = true) ||
                    msg.contains("Sign in to confirm you’re not a bot", ignoreCase = true) ||
                    msg.contains("Sign in to view", ignoreCase = true) ->
                Exception("來源網站需要登入帳號驗證，目前版本不支援登入下載")
            isInstagram && (msg.contains("Please wait a few minutes", ignoreCase = true) || msg.contains("rate-limit", ignoreCase = true)) ->
                Exception("Instagram 存取頻率受限 (Rate Limited)，請稍候幾分鐘再試")
            isInstagram && (msg.contains("Unable to extract", ignoreCase = true) || msg.contains("empty", ignoreCase = true)) -> {
                val sanitized = msg.lines().firstOrNull { it.isNotBlank() && !it.contains("cookie", ignoreCase = true) } ?: "無法解析貼文內容"
                Exception("Instagram 頁面解析失敗：$sanitized")
            }
            msg.contains("Unsupported URL", ignoreCase = true) ->
                Exception("不支援的網址或尚未支援該網站之解析")
            msg.contains("DRM", ignoreCase = true) ->
                Exception("此內容受 DRM 保護，本工具無法下載")
            msg.contains("HTTP Error 404", ignoreCase = true) ->
                Exception("找不到目標影片 (HTTP 404)")
            msg.contains("Unable to download webpage", ignoreCase = true) ->
                Exception("無法連線至目標網頁，請檢查網路連線")
            else -> {
                val sanitized = msg.lines().firstOrNull { it.isNotBlank() && !it.contains("cookie", ignoreCase = true) } ?: "未知錯誤"
                Exception("解析或下載發生錯誤：$sanitized")
            }
        }
    }
}
