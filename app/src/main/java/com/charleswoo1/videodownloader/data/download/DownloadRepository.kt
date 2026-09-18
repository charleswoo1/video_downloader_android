package com.charleswoo1.videodownloader.data.download

import android.content.Context
import com.charleswoo1.videodownloader.data.storage.DownloadStorage
import com.charleswoo1.videodownloader.domain.model.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadRepository {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _runtimeVersion = MutableStateFlow<String?>(null)
    val runtimeVersion: StateFlow<String?> = _runtimeVersion.asStateFlow()

    private var engine: DownloadEngine? = null
    private var storage: DownloadStorage? = null
    var isInitialized: Boolean = false
    private val isJobRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryStartDownload(): Boolean {
        return isJobRunning.compareAndSet(false, true)
    }

    fun markDownloadStarted() {
        isJobRunning.set(true)
    }

    fun finishDownload() {
        isJobRunning.set(false)
    }

    fun isDownloadActive(): Boolean {
        return isJobRunning.get()
    }

    fun initialize(context: Context) {
        if (engine == null) {
            engine = YtDlpDownloadEngine(context.applicationContext)
        }
        if (storage == null) {
            storage = DownloadStorage(context.applicationContext)
        }
    }

    fun setEngineForTesting(testEngine: DownloadEngine?) {
        this.engine = testEngine
    }

    fun getEngine(context: Context): DownloadEngine {
        initialize(context)
        return engine!!
    }

    fun getStorage(context: Context): DownloadStorage {
        initialize(context)
        return storage!!
    }

    fun updateState(state: DownloadState) {
        _downloadState.value = state
    }

    fun requestCancel() {
        engine?.cancelDownload()
        _downloadState.value = DownloadState.Cancelling
    }

    fun completeCancellation() {
        _downloadState.value = DownloadState.Cancelled
        finishDownload()
    }

    fun clearTerminalState() {
        val current = _downloadState.value
        if (current is DownloadState.Completed || current is DownloadState.Failed || current is DownloadState.Cancelled) {
            _downloadState.value = DownloadState.Idle
        }
    }

    fun refreshRuntimeVersion(): String? {
        val version = engine?.getRuntimeVersion()
        _runtimeVersion.value = version
        return version
    }

    fun getRuntimeVersion(): String? {
        return _runtimeVersion.value ?: refreshRuntimeVersion()
    }

    suspend fun updateRuntime(): Result<String> {
        val currentEngine = engine
            ?: return Result.failure(IllegalStateException("DownloadEngine is not initialized"))

        val result = currentEngine.updateRuntime()
        val activeVersion = currentEngine.getRuntimeVersion()
        _runtimeVersion.value = activeVersion

        return result.fold(
            onSuccess = { Result.success(activeVersion ?: it) },
            onFailure = { Result.failure(it) }
        )
    }

    fun reset() {
        _downloadState.value = DownloadState.Idle
        finishDownload()
    }
}
