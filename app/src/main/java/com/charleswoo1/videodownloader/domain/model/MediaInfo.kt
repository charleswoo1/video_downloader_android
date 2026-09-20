package com.charleswoo1.videodownloader.domain.model

data class QualityOption(
    val id: String,
    val label: String,
    val formatSelector: String,
    val isAudioOnly: Boolean = false,
    val resolutionNote: String? = null
)

data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val platform: Platform,
    val extractor: String,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val qualityOptions: List<QualityOption> = emptyList()
)
