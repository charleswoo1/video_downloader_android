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

    private var engine: DownloadEngine? = null
    private var storage: DownloadStorage? = null
    var isInitialized: Boolean = false

    fun initialize(context: Context) {
        if (engine == null) {
            engine = YtDlpDownloadEngine(context.applicationContext)
        }
        if (storage == null) {
            storage = DownloadStorage(context.applicationContext)
        }
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

    fun cancel() {
        engine?.cancelDownload()
        _downloadState.value = DownloadState.Cancelled
    }
}
