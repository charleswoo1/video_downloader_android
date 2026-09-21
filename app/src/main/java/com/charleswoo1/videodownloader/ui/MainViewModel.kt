package com.charleswoo1.videodownloader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.charleswoo1.videodownloader.data.download.RuntimeDiagnostics
import com.charleswoo1.videodownloader.data.download.RuntimeDiagnosticsHelper
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.DownloadState
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.QualityOption
import com.charleswoo1.videodownloader.domain.url.SharedTextUrlExtractor
import com.charleswoo1.videodownloader.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Analyzing : AnalysisState
    data class Success(val mediaInfo: MediaInfo) : AnalysisState
    data class Error(val message: String) : AnalysisState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val downloadEngine = DownloadRepository.getEngine(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _selectedQuality = MutableStateFlow<QualityOption?>(null)
    val selectedQuality: StateFlow<QualityOption?> = _selectedQuality.asStateFlow()

    val downloadState: StateFlow<DownloadState> = DownloadRepository.downloadState

    val runtimeVersion: StateFlow<String?> = DownloadRepository.runtimeVersion

    private val _runtimeDiagnostics = MutableStateFlow<RuntimeDiagnostics?>(null)
    val runtimeDiagnostics: StateFlow<RuntimeDiagnostics?> = _runtimeDiagnostics.asStateFlow()

    val engineTrace: StateFlow<com.charleswoo1.videodownloader.data.download.EngineTrace?> = DownloadRepository.engineTrace

    val sessionProvider = DownloadRepository.sessionProvider
    val instagramSession: StateFlow<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo>? = sessionProvider.statusFlow(com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM)
    val threadsSession: StateFlow<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo>? = sessionProvider.statusFlow(com.charleswoo1.videodownloader.domain.model.Platform.THREADS)
    val xSession: StateFlow<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo>? = sessionProvider.statusFlow(com.charleswoo1.videodownloader.domain.model.Platform.X)

    private val _activeLoginPlatform = MutableStateFlow<com.charleswoo1.videodownloader.domain.model.Platform?>(null)
    val activeLoginPlatform: StateFlow<com.charleswoo1.videodownloader.domain.model.Platform?> = _activeLoginPlatform.asStateFlow()

    fun startWebLogin(platform: com.charleswoo1.videodownloader.domain.model.Platform) {
        _activeLoginPlatform.value = platform
    }

    fun dismissWebLogin() {
        _activeLoginPlatform.value = null
    }

    fun importSession(platform: com.charleswoo1.videodownloader.domain.model.Platform, rawInput: String): Result<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo> {
        return sessionProvider.importSession(platform, rawInput)
    }

    fun importCapturedSession(
        platform: com.charleswoo1.videodownloader.domain.model.Platform,
        rawCookieHeader: String
    ): Result<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo> {
        return sessionProvider.importCapturedSession(platform, rawCookieHeader)
    }

    fun importMetaSession(rawInput: String): Result<Pair<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo, com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo>> {
        return sessionProvider.importMetaSession(rawInput)
    }

    fun clearSession(platform: com.charleswoo1.videodownloader.domain.model.Platform) {
        sessionProvider.clearSession(platform)
    }

    fun validateSession(platform: com.charleswoo1.videodownloader.domain.model.Platform) {
        viewModelScope.launch {
            val httpSession = com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession(sessionProvider = sessionProvider)
            sessionProvider.validateSession(platform, httpSession)
        }
    }

    suspend fun validateSessionSuspending(
        platform: com.charleswoo1.videodownloader.domain.model.Platform
    ): Result<com.charleswoo1.videodownloader.data.download.http.PlatformSessionInfo> {
        val httpSession = com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession(sessionProvider = sessionProvider)
        return sessionProvider.validateSession(platform, httpSession)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _runtimeDiagnostics.value = RuntimeDiagnosticsHelper.collectDiagnostics(application)
        }
    }

    fun onUrlInputChanged(newUrl: String) {
        _urlInput.value = newUrl
        if (_analysisState.value is AnalysisState.Error) {
            _analysisState.value = AnalysisState.Idle
        }
    }

    fun onQualitySelected(option: QualityOption) {
        _selectedQuality.value = option
    }

    fun onEngineTraceDismiss() {
        // Trace dismissal logic if needed
    }

    fun handleSharedText(sharedText: String?) {
        if (sharedText.isNullOrBlank()) return

        DownloadRepository.clearTerminalState()
        val extractedUrl = SharedTextUrlExtractor.extractFirstUrl(sharedText)
        if (extractedUrl != null) {
            _urlInput.value = extractedUrl
            startAnalysis(extractedUrl)
        } else {
            _analysisState.value = AnalysisState.Error("分享文字中未偵測到有效的 HTTP/HTTPS 影片網址")
        }
    }

    fun analyzeCurrentUrl() {
        startAnalysis(_urlInput.value.trim())
    }

    fun startAnalysis(url: String) {
        if (url.isBlank()) {
            _analysisState.value = AnalysisState.Error("請輸入有效的影片網址")
            return
        }

        DownloadRepository.clearTerminalState()
        val extracted = SharedTextUrlExtractor.extractFirstUrl(url) ?: url

        viewModelScope.launch {
            _analysisState.value = AnalysisState.Analyzing
            _selectedQuality.value = null

            val result = downloadEngine.extractMediaInfo(extracted)
            result.onSuccess { info ->
                _analysisState.value = AnalysisState.Success(info)
                _selectedQuality.value = info.qualityOptions.firstOrNull()
            }.onFailure { error ->
                _analysisState.value = AnalysisState.Error(
                    error.message ?: "解析失敗，請確認網址或稍後再試"
                )
            }
        }
    }

    fun startDownload(context: Context) {
        if (DownloadRepository.isDownloadActive()) return

        val state = _analysisState.value
        if (state !is AnalysisState.Success) return

        val quality = _selectedQuality.value ?: state.mediaInfo.qualityOptions.firstOrNull() ?: return
        val request = DownloadRequest(
            url = state.mediaInfo.sourceUrl,
            title = state.mediaInfo.title,
            qualityOption = quality
        )

        DownloadService.startDownload(context, request)
    }

    fun cancelDownload(context: Context) {
        DownloadService.cancelDownload(context)
    }

    fun resetAnalysis() {
        _analysisState.value = AnalysisState.Idle
        _selectedQuality.value = null
    }
}
