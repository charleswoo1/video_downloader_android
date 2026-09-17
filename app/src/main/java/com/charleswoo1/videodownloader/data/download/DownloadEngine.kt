package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import java.io.File

interface DownloadEngine {
    fun isInitialized(): Boolean

    suspend fun extractMediaInfo(url: String): Result<MediaInfo>

    suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File>

    fun cancelDownload()
}
