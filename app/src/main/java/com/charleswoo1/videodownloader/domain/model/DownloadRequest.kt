package com.charleswoo1.videodownloader.domain.model

data class DownloadRequest(
    val url: String,
    val title: String,
    val qualityOption: QualityOption
)
