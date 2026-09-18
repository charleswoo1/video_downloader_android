package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.meta.MetaExtractionError
import com.charleswoo1.videodownloader.data.download.meta.MetaWebClient
import com.charleswoo1.videodownloader.data.download.meta.NativeInstagramEngine
import com.charleswoo1.videodownloader.data.download.meta.NativeThreadsEngine
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.url.PlatformDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diagnostic log for A/B engine routing comparisons.
 */
data class EngineRoutingLog(
    val platform: Platform,
    val primaryEngine: String,
    val primaryResultCategory: String,
    val fallbackAttempted: Boolean,
    val fallbackEngine: String? = null,
    val finalEngine: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Router managing platform engines and fallback logic.
 *
 * Routes Meta platforms (Instagram and Threads) to dedicated native extraction engines
 * and preserves yt-dlp as generic engine and secondary fallback for technical failures only.
 * Content and access restrictions (audience restricted, login required, deleted/private,
 * and no video) are strictly prohibited from entering a fallback loop.
 */
class PlatformEngineRouter(
    private val context: Context? = null,
    private val nativeInstagramEngine: PlatformMediaEngine = NativeInstagramEngine(context, MetaWebClient()),
    private val nativeThreadsEngine: PlatformMediaEngine = NativeThreadsEngine(context, MetaWebClient()),
    private val ytDlpEngine: DownloadEngine = if (context != null) YtDlpDownloadEngine(context) else StubDownloadEngine
) : DownloadEngine {

    private object StubDownloadEngine : DownloadEngine {
        override fun isInitialized(): Boolean = false
        override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = Result.failure(UnsupportedOperationException("Stub"))
        override suspend fun download(request: DownloadRequest, destDir: File, onProgress: (Float, Long?, String?) -> Unit, onStatus: (String) -> Unit): Result<File> = Result.failure(UnsupportedOperationException("Stub"))
        override fun cancelDownload() {}
    }

    companion object {
        private const val TAG = "PlatformEngineRouter"
    }

    var lastRoutingLog: EngineRoutingLog? = null
        private set

    override fun isInitialized(): Boolean = ytDlpEngine.isInitialized()

    override fun getRuntimeVersion(): String? = ytDlpEngine.getRuntimeVersion()

    override suspend fun updateRuntime(): Result<String> = ytDlpEngine.updateRuntime()

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(url)

        when (platform) {
            Platform.INSTAGRAM -> routeInstagram(url)
            Platform.THREADS -> routeThreads(url)
            else -> routeGeneric(url, platform)
        }
    }

    private suspend fun routeInstagram(url: String): Result<MediaInfo> {
        val primaryEngineName = nativeInstagramEngine.name
        val result = nativeInstagramEngine.extractMediaInfo(url)

        if (result.isSuccess) {
            recordLog(
                EngineRoutingLog(
                    platform = Platform.INSTAGRAM,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = "SUCCESS",
                    fallbackAttempted = false,
                    finalEngine = primaryEngineName
                )
            )
            return result
        }

        val error = result.exceptionOrNull()
        if (error is MetaExtractionError && !error.canFallback) {
            // Restriction or no-video: STRICTLY DO NOT FALL BACK
            val category = error::class.java.simpleName
            recordLog(
                EngineRoutingLog(
                    platform = Platform.INSTAGRAM,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = category,
                    fallbackAttempted = false,
                    finalEngine = "$primaryEngineName (TERMINATED)"
                )
            )
            return Result.failure(error)
        }

        // Technical failure: attempt yt-dlp fallback
        recordLog(
            EngineRoutingLog(
                platform = Platform.INSTAGRAM,
                primaryEngine = primaryEngineName,
                primaryResultCategory = "TECHNICAL_FAILURE",
                fallbackAttempted = true,
                fallbackEngine = "YtDlpDownloadEngine",
                finalEngine = "YtDlpDownloadEngine"
            )
        )
        safeLog("Instagram native extraction encountered technical failure; falling back to yt-dlp baseline")
        return ytDlpEngine.extractMediaInfo(url)
    }

    private suspend fun routeThreads(url: String): Result<MediaInfo> {
        val primaryEngineName = nativeThreadsEngine.name
        val result = nativeThreadsEngine.extractMediaInfo(url)

        if (result.isSuccess) {
            recordLog(
                EngineRoutingLog(
                    platform = Platform.THREADS,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = "SUCCESS",
                    fallbackAttempted = false,
                    finalEngine = primaryEngineName
                )
            )
            return result
        }

        val error = result.exceptionOrNull()
        if (error is MetaExtractionError && !error.canFallback) {
            // Content restriction, deleted/private, or no-video: STRICTLY DO NOT FALL BACK
            val category = error::class.java.simpleName
            recordLog(
                EngineRoutingLog(
                    platform = Platform.THREADS,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = category,
                    fallbackAttempted = false,
                    finalEngine = "$primaryEngineName (TERMINATED)"
                )
            )
            return Result.failure(error)
        }

        // Technical failure: fallback to yt-dlp + Threads plugin
        recordLog(
            EngineRoutingLog(
                platform = Platform.THREADS,
                primaryEngine = primaryEngineName,
                primaryResultCategory = "TECHNICAL_FAILURE",
                fallbackAttempted = true,
                fallbackEngine = "YtDlpDownloadEngine",
                finalEngine = "YtDlpDownloadEngine"
            )
        )
        safeLog("Threads native extraction encountered technical failure; falling back to yt-dlp + plugin")
        return ytDlpEngine.extractMediaInfo(url)
    }

    private suspend fun routeGeneric(url: String, platform: Platform): Result<MediaInfo> {
        recordLog(
            EngineRoutingLog(
                platform = platform,
                primaryEngine = "YtDlpDownloadEngine",
                primaryResultCategory = "DIRECT",
                fallbackAttempted = false,
                finalEngine = "YtDlpDownloadEngine"
            )
        )
        return ytDlpEngine.extractMediaInfo(url)
    }

    override suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(request.url)
        val formatSelector = request.qualityOption.formatSelector

        val isDirectMediaUrl = formatSelector.startsWith("http://") ||
                formatSelector.startsWith("https://") ||
                formatSelector.contains("|")

        when {
            platform == Platform.INSTAGRAM && isDirectMediaUrl -> {
                val res = nativeInstagramEngine.download(request, destDir, onProgress, onStatus)
                if (res.isSuccess || res.exceptionOrNull() is InterruptedException) {
                    res
                } else {
                    safeLog("Native Instagram download failed (${res.exceptionOrNull()?.message}); attempting yt-dlp fallback")
                    val fallbackRequest = createYtDlpFallbackRequest(request)
                    ytDlpEngine.download(fallbackRequest, destDir, onProgress, onStatus)
                }
            }
            platform == Platform.THREADS && isDirectMediaUrl -> {
                val res = nativeThreadsEngine.download(request, destDir, onProgress, onStatus)
                if (res.isSuccess || res.exceptionOrNull() is InterruptedException) {
                    res
                } else {
                    safeLog("Native Threads download failed (${res.exceptionOrNull()?.message}); attempting yt-dlp fallback")
                    val fallbackRequest = createYtDlpFallbackRequest(request)
                    ytDlpEngine.download(fallbackRequest, destDir, onProgress, onStatus)
                }
            }
            else -> {
                ytDlpEngine.download(request, destDir, onProgress, onStatus)
            }
        }
    }

    private fun createYtDlpFallbackRequest(request: DownloadRequest): DownloadRequest {
        val fallbackSelector = if (request.qualityOption.isAudioOnly) {
            "bestaudio/best"
        } else {
            "bestvideo+bestaudio/best"
        }
        return request.copy(
            qualityOption = request.qualityOption.copy(formatSelector = fallbackSelector)
        )
    }

    override fun cancelDownload() {
        nativeInstagramEngine.cancelDownload()
        nativeThreadsEngine.cancelDownload()
        ytDlpEngine.cancelDownload()
    }

    private fun recordLog(log: EngineRoutingLog) {
        lastRoutingLog = log
        safeLog(
            "Platform: ${log.platform}, Primary: ${log.primaryEngine}, " +
            "Result: ${log.primaryResultCategory}, FallbackAttempted: ${log.fallbackAttempted}, " +
            "FallbackEngine: ${log.fallbackEngine}, Final: ${log.finalEngine}"
        )
    }

    private fun safeLog(msg: String) {
        try {
            Log.d(TAG, "[A/B Diagnostics] $msg")
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
