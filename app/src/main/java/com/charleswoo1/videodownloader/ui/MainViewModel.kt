package com.charleswoo1.videodownloader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.DownloadState
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.QualityOption
import com.charleswoo1.videodownloader.domain.url.SharedTextUrlExtractor
import com.charleswoo1.videodownloader.service.DownloadService
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

    private val _runtimeVersion = MutableStateFlow<String?>(downloadEngine.getRuntimeVersion())
    val runtimeVersion: StateFlow<String?> = _runtimeVersion.asStateFlow()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _runtimeVersion.value = downloadEngine.getRuntimeVersion()
        }
    }

    fun onUrlInputChanged(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun onQualitySelected(option: QualityOption) {
        _selectedQuality.value = option
    }

    fun handleSharedText(sharedText: String?) {
        if (sharedText.isNullOrBlank()) return

        DownloadRepository.clearTerminalState()
        val extractedUrl = SharedTextUrlExtractor.extractFirstUrl(sharedText)
        if (extractedUrl != null) {
            _urlInput.value = extractedUrl
            analyzeCurrentUrl()
        } else {
            _analysisState.value = AnalysisState.Error("分享文字中未偵測到有效的 HTTP/HTTPS 影片網址")
        }
    }

    fun analyzeCurrentUrl() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) {
            _analysisState.value = AnalysisState.Error("請先輸入或貼上網址")
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
        val currentState = downloadState.value
        val isActivelyDownloading = DownloadRepository.isDownloadActive() &&
                (currentState is DownloadState.Downloading ||
                 currentState is DownloadState.Preparing ||
                 currentState is DownloadState.PostProcessing ||
                 currentState is DownloadState.Cancelling)

        if (isActivelyDownloading) return

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
        DownloadRepository.requestCancel()
        DownloadService.cancelDownload(context)
    }

    fun resetAnalysis() {
        _analysisState.value = AnalysisState.Idle
        _selectedQuality.value = null
    }
}
