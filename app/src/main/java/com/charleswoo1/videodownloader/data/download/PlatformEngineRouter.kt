package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.meta.NativeInstagramEngine
import com.charleswoo1.videodownloader.data.download.meta.NativeThreadsEngine
import com.charleswoo1.videodownloader.data.download.x.NativeXEngine
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.url.PlatformDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Platform Multi-Engine Router V2.
 *
 * Routes Instagram, Threads, and X to high-fidelity native engines.
 * yt-dlp serves as generic engine for YouTube, Facebook, TikTok, and technical fallback
 * for Instagram, Threads, and X.
 *
 * Strictly prevents fallback loops when content/access restrictions or explicit NO_VIDEO
 * conditions are met.
 */
class PlatformEngineRouter(
    private val context: Context? = null,
    private val nativeInstagramEngine: PlatformMediaEngine = NativeInstagramEngine(context),
    private val nativeThreadsEngine: PlatformMediaEngine = NativeThreadsEngine(context),
    private val nativeXEngine: PlatformMediaEngine = NativeXEngine(context),
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

    private val _traceFlow = MutableStateFlow<EngineTrace?>(null)
    val traceFlow: StateFlow<EngineTrace?> = _traceFlow.asStateFlow()

    var lastTrace: EngineTrace? = null
        private set

    override fun isInitialized(): Boolean = ytDlpEngine.isInitialized()

    override fun getRuntimeVersion(): String? = ytDlpEngine.getRuntimeVersion()

    override suspend fun updateRuntime(): Result<String> = ytDlpEngine.updateRuntime()

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val platform = PlatformDetector.detect(url)
        val opId = UUID.randomUUID().toString()

        when (platform) {
            Platform.INSTAGRAM -> routeInstagram(url, opId)
            Platform.THREADS -> routeThreads(url, opId)
            Platform.X -> routeX(url, opId)
            else -> routeGeneric(url, platform, opId)
        }
    }

    private fun formatPrimaryCategory(error: Throwable?): String {
        val pe = error as? PlatformExtractionError ?: return "TECHNICAL_FAILURE"
        return if (!pe.internalReason.isNullOrBlank()) {
            "${pe.code.name} (${pe.internalReason})"
        } else {
            pe.code.name
        }
    }

    private fun getFallbackCategory(fallbackResult: Result<MediaInfo>, platform: Platform): String {
        if (fallbackResult.isSuccess) return "SUCCESS"
        val msg = fallbackResult.exceptionOrNull()?.message
        return YtDlpErrorParser.parse(msg, platform).category.name
    }

    private suspend fun routeInstagram(url: String, opId: String): Result<MediaInfo> {
        val primaryEngineName = nativeInstagramEngine.name
        val result = nativeInstagramEngine.extractMediaInfo(url)
        val profiles = (nativeInstagramEngine as? NativeInstagramEngine)?.lastProfileSequence ?: emptyList()
        val diagFingerprint = nativeInstagramEngine.lastDiagnosticFingerprint

        if (result.isSuccess) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.INSTAGRAM,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = "SUCCESS",
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = primaryEngineName,
                    finalResult = "SUCCESS",
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return result
        }

        val error = result.exceptionOrNull()
        val primaryCategory = formatPrimaryCategory(error)

        if (error is PlatformExtractionError && !error.canFallback) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.INSTAGRAM,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = primaryCategory,
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = "$primaryEngineName (TERMINATED)",
                    finalResult = primaryCategory,
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return Result.failure(error)
        }

        // Technical failure: attempt yt-dlp fallback
        safeLog("Instagram native extraction failed ($primaryCategory); falling back to yt-dlp")
        val fallbackResult = ytDlpEngine.extractMediaInfo(url)
        val fallbackCategory = getFallbackCategory(fallbackResult, Platform.INSTAGRAM)
        val finalResultStatus = if (fallbackResult.isSuccess) "SUCCESS" else fallbackCategory

        recordTrace(
            EngineTrace(
                operationId = opId,
                platform = Platform.INSTAGRAM,
                primaryEngine = primaryEngineName,
                primaryResultCategory = primaryCategory,
                requestProfileSequence = profiles,
                fallbackAttempted = true,
                fallbackEngine = "YtDlpDownloadEngine",
                fallbackResultCategory = fallbackCategory,
                finalEngine = "YtDlpDownloadEngine",
                finalResult = finalResultStatus,
                diagnosticFingerprint = diagFingerprint
            )
        )
        return fallbackResult
    }

    private suspend fun routeThreads(url: String, opId: String): Result<MediaInfo> {
        val primaryEngineName = nativeThreadsEngine.name
        val result = nativeThreadsEngine.extractMediaInfo(url)
        val profiles = (nativeThreadsEngine as? NativeThreadsEngine)?.lastProfileSequence ?: emptyList()
        val diagFingerprint = nativeThreadsEngine.lastDiagnosticFingerprint

        if (result.isSuccess) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.THREADS,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = "SUCCESS",
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = primaryEngineName,
                    finalResult = "SUCCESS",
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return result
        }

        val error = result.exceptionOrNull()
        val primaryCategory = formatPrimaryCategory(error)

        if (error is PlatformExtractionError && !error.canFallback) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.THREADS,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = primaryCategory,
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = "$primaryEngineName (TERMINATED)",
                    finalResult = primaryCategory,
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return Result.failure(error)
        }

        // Technical failure: attempt yt-dlp fallback
        safeLog("Threads native extraction failed ($primaryCategory); falling back to yt-dlp + plugin")
        val fallbackResult = ytDlpEngine.extractMediaInfo(url)
        val fallbackCategory = getFallbackCategory(fallbackResult, Platform.THREADS)
        val finalResultStatus = if (fallbackResult.isSuccess) "SUCCESS" else fallbackCategory

        recordTrace(
            EngineTrace(
                operationId = opId,
                platform = Platform.THREADS,
                primaryEngine = primaryEngineName,
                primaryResultCategory = primaryCategory,
                requestProfileSequence = profiles,
                fallbackAttempted = true,
                fallbackEngine = "YtDlpDownloadEngine",
                fallbackResultCategory = fallbackCategory,
                finalEngine = "YtDlpDownloadEngine",
                finalResult = finalResultStatus,
                diagnosticFingerprint = diagFingerprint
            )
        )

        if (fallbackResult.isSuccess) {
            return fallbackResult
        }

        // Section 4.2: When native engine already produced PARSE_ERROR, the fallback message must not be treated as authoritative content classification.
        // Do not rewrite the native result as PRIVATE/DELETED unless independent evidence supports it.
        val primaryErr = error as? PlatformExtractionError
        val primaryInternalReason = primaryErr?.internalReason
        val detailMsg = primaryErr?.message ?: "Threads 備援解析失敗；原始內容可能使用不同的公開頁面資料格式"
        val userMsg = primaryErr?.userMessage ?: "Threads 備援解析遭拒或失敗；此內容可能使用不同的公開頁面資料格式 (原生: $primaryCategory)"
        return Result.failure(
            PlatformExtractionError.ParseError(
                detail = detailMsg,
                userMessage = userMsg,
                internalReason = primaryInternalReason ?: "FALLBACK_$fallbackCategory",
                cause = fallbackResult.exceptionOrNull()
            )
        )
    }

    private suspend fun routeX(url: String, opId: String): Result<MediaInfo> {
        val primaryEngineName = nativeXEngine.name
        val result = nativeXEngine.extractMediaInfo(url)
        val profiles = (nativeXEngine as? NativeXEngine)?.lastProfileSequence ?: emptyList()
        val diagFingerprint = nativeXEngine.lastDiagnosticFingerprint

        if (result.isSuccess) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.X,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = "SUCCESS",
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = primaryEngineName,
                    finalResult = "SUCCESS",
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return result
        }

        val error = result.exceptionOrNull()
        val primaryCategory = formatPrimaryCategory(error)

        if (error is PlatformExtractionError && !error.canFallback) {
            recordTrace(
                EngineTrace(
                    operationId = opId,
                    platform = Platform.X,
                    primaryEngine = primaryEngineName,
                    primaryResultCategory = primaryCategory,
                    requestProfileSequence = profiles,
                    fallbackAttempted = false,
                    finalEngine = "$primaryEngineName (TERMINATED)",
                    finalResult = primaryCategory,
                    diagnosticFingerprint = diagFingerprint
                )
            )
            return Result.failure(error)
        }

        // Technical failure: attempt yt-dlp fallback
        safeLog("X native extraction failed ($primaryCategory); falling back to yt-dlp")
        val fallbackResult = ytDlpEngine.extractMediaInfo(url)
        val fallbackCategory = getFallbackCategory(fallbackResult, Platform.X)
        val finalResultStatus = if (fallbackResult.isSuccess) "SUCCESS" else fallbackCategory

        recordTrace(
            EngineTrace(
                operationId = opId,
                platform = Platform.X,
                primaryEngine = primaryEngineName,
                primaryResultCategory = primaryCategory,
                requestProfileSequence = profiles,
                fallbackAttempted = true,
                fallbackEngine = "YtDlpDownloadEngine",
                fallbackResultCategory = fallbackCategory,
                finalEngine = "YtDlpDownloadEngine",
                finalResult = finalResultStatus,
                diagnosticFingerprint = diagFingerprint
            )
        )
        return fallbackResult
    }

    private suspend fun routeGeneric(url: String, platform: Platform, opId: String): Result<MediaInfo> {
        val result = ytDlpEngine.extractMediaInfo(url)
        val status = if (result.isSuccess) "SUCCESS" else "FAILURE"
        recordTrace(
            EngineTrace(
                operationId = opId,
                platform = platform,
                primaryEngine = "YtDlpDownloadEngine",
                primaryResultCategory = status,
                fallbackAttempted = false,
                finalEngine = "YtDlpDownloadEngine",
                finalResult = status
            )
        )
        return result
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
                formatSelector.startsWith("audio:http://") ||
                formatSelector.startsWith("audio:https://") ||
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
            platform == Platform.X && isDirectMediaUrl -> {
                val res = nativeXEngine.download(request, destDir, onProgress, onStatus)
                if (res.isSuccess || res.exceptionOrNull() is InterruptedException) {
                    res
                } else {
                    safeLog("Native X download failed (${res.exceptionOrNull()?.message}); attempting yt-dlp fallback")
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
        nativeXEngine.cancelDownload()
        ytDlpEngine.cancelDownload()
    }

    private fun recordTrace(trace: EngineTrace) {
        lastTrace = trace
        _traceFlow.value = trace
        safeLog("[EngineTrace] ${trace.toDisplaySummary()}")
        if (!trace.diagnosticFingerprint.isNullOrBlank()) {
            safeLog("[EngineTrace-Diagnostics]\n${trace.diagnosticFingerprint}")
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
