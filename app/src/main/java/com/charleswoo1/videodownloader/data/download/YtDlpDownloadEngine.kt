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
import com.yausername.youtubedl_android.mapper.VideoInfo
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
        val ver = getRuntimeVersion() ?: "2026.08.30.232658"
        Log.i(TAG, "[Diagnostics] Packaged deterministic runtime active: $ver (automatic remote update disabled)")
        Result.success(ver)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(url)

        try {
            if (!isInitialized()) {
                return@withContext Result.failure(
                    IllegalStateException("yt-dlp 引擎尚未初始化，請重新開啟應用程式")
                )
            }

            if (platform == Platform.THREADS) {
                return@withContext extractThreadsMediaInfo(url)
            }

            val runtimeVer = getRuntimeVersion() ?: "bundled"
            Log.d(TAG, "[Diagnostics] Analyzing $url with yt-dlp runtime version: $runtimeVer")

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")

            val videoInfo = YoutubeDL.getInstance().getInfo(request)
            val options = buildQualityOptions(videoInfo)

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
            Result.failure(mapYoutubeDLError(e, platform))
        } catch (e: Exception) {
            Log.e(TAG, "extractMediaInfo unexpected error", e)
            Result.failure(e)
        }
    }

    private suspend fun extractThreadsMediaInfo(url: String): Result<MediaInfo> {
        val pluginInstall = YtDlpPluginManager.ensureInstalled(context)
        if (pluginInstall.isFailure) {
            Log.w(TAG, "[Threads] Plugin installation failed, falling back to Kotlin ThreadsResolver", pluginInstall.exceptionOrNull())
            return threadsResolver.extractMediaInfo(url)
        }

        val pluginDir = pluginInstall.getOrThrow()
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--plugin-dirs", pluginDir.absolutePath)

            Log.d(TAG, "[Diagnostics] Analyzing Threads URL via yt-dlp with plugin-dirs: ${pluginDir.name}")
            val videoInfo = YoutubeDL.getInstance().getInfo(request)

            val options = buildQualityOptions(videoInfo)
            val mediaInfo = MediaInfo(
                sourceUrl = url,
                title = videoInfo.title ?: "未命名影片",
                platform = Platform.THREADS,
                extractor = videoInfo.extractor ?: "Threads",
                thumbnailUrl = videoInfo.thumbnail,
                durationSeconds = videoInfo.duration?.toLong(),
                qualityOptions = options
            )
            return Result.success(mediaInfo)
        } catch (e: YoutubeDLException) {
            val errorMsg = e.message ?: ""
            Log.w(TAG, "[Threads] yt-dlp extractMediaInfo failed: $errorMsg")

            // Conservative fallback check:
            // ONLY fallback if plugin is missing, failed to load, or yt-dlp reports Unsupported URL
            if (shouldFallbackToKotlinResolver(errorMsg)) {
                Log.i(TAG, "[Threads] Unsupported URL or plugin load failure detected; falling back to Kotlin ThreadsResolver")
                return threadsResolver.extractMediaInfo(url)
            }

            // Do NOT fallback for expected extractor errors (not found, private, deleted, no video, login-gated)
            return Result.failure(mapYoutubeDLError(e, Platform.THREADS))
        } catch (e: Exception) {
            Log.e(TAG, "[Threads] Unexpected exception during extractMediaInfo", e)
            return Result.failure(e)
        }
    }

    private fun shouldFallbackToKotlinResolver(errorMessage: String): Boolean {
        val lower = errorMessage.lowercase()
        // Fallback when extractor plugin was clearly not recognized or loaded
        if (lower.contains("unsupported url")) {
            return true
        }
        if (lower.contains("no module named") || lower.contains("plugin-dirs") || lower.contains("cannot import")) {
            return true
        }
        // Under all other conditions (private, deleted, not found in page data, no video, carousel no video, login-gated), DO NOT FALLBACK
        return false
    }

    private fun buildQualityOptions(videoInfo: VideoInfo): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        options.add(
            QualityOption(
                id = "best",
                label = "最佳畫質 (推薦)",
                formatSelector = "bestvideo+bestaudio/best"
            )
        )

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

        options.add(
            QualityOption(
                id = "audio_only",
                label = "僅音訊 (MP3/M4A)",
                formatSelector = "bestaudio/best",
                isAudioOnly = true
            )
        )

        return options
    }

    override suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(request.url)
        if (platform == Platform.THREADS) {
            val formatSelector = request.qualityOption.formatSelector
            // If the formatSelector came from Kotlin ThreadsResolver fallback (direct URL or piped URLs), delegate to threadsResolver
            if (formatSelector.startsWith("http://") || formatSelector.startsWith("https://") || formatSelector.contains("|")) {
                Log.d(TAG, "[Threads] Direct stream URL detected; delegating to Kotlin ThreadsResolver download fallback")
                return@withContext threadsResolver.download(request, destDir, onProgress, onStatus)
            }
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

            if (platform == Platform.THREADS) {
                val pluginDir = YtDlpPluginManager.getPluginDir(context)
                if (pluginDir.exists()) {
                    ytRequest.addOption("--plugin-dirs", pluginDir.absolutePath)
                }
            }

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
                Result.failure(mapYoutubeDLError(e, platform))
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

    private fun mapYoutubeDLError(e: YoutubeDLException, platform: Platform? = null): Exception {
        val parsed = YtDlpErrorParser.parse(e.message, platform)
        if (parsed.warnings.isNotEmpty()) {
            Log.d(TAG, "[Diagnostics] yt-dlp warnings during execution: ${parsed.warnings}")
        }
        return YtDlpExtractionException(parsed, cause = e)
    }
}
