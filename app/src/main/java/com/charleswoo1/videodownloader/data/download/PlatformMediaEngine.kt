package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import java.io.File

/**
 * Interface representing a platform-specific media engine for extraction and downloading.
 */
interface PlatformMediaEngine {
    val name: String

    fun supports(platform: Platform): Boolean

    suspend fun extractMediaInfo(url: String): Result<MediaInfo>

    suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File>

    fun cancelDownload()
}
