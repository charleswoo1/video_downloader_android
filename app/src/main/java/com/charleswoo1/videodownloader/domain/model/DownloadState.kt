package com.charleswoo1.videodownloader.domain.model

import android.net.Uri

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Preparing : DownloadState
    data class Downloading(
        val progress: Float,
        val etaSeconds: Long? = null,
        val speedText: String? = null
    ) : DownloadState
    data object PostProcessing : DownloadState
    data class Completed(
        val fileName: String,
        val contentUri: Uri? = null,
        val filePath: String? = null
    ) : DownloadState
    data object Cancelled : DownloadState
    data class Failed(
        val errorMessage: String
    ) : DownloadState
}
