package com.charleswoo1.videodownloader.data.download.meta

/**
 * Categorization of content and access restrictions.
 */
enum class RestrictionReason {
    AUDIENCE_RESTRICTED,
    LOGIN_REQUIRED,
    DELETED_OR_PRIVATE
}

/**
 * Structured errors for Meta platform extraction.
 *
 * [canFallback] determines whether the router is permitted to attempt secondary engine fallback.
 * Content restrictions, deleted/private media, and posts without videos MUST NOT fall back.
 */
sealed class MetaExtractionError(
    val userMessage: String,
    val canFallback: Boolean,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    /**
     * Technical failures where secondary engine fallback is reasonable
     * (e.g. schema changed, parser exception, missing expected JSON).
     */
    class Technical(
        val detail: String,
        userMessage: String = "暫時無法解析此貼文。平台可能未提供匿名媒體資料，或頁面格式已變更。",
        cause: Throwable? = null
    ) : MetaExtractionError(userMessage, canFallback = true, cause = cause)

    /**
     * Access or audience restrictions (audience gated, login required, deleted/private).
     * Strictly prohibited from falling back.
     */
    class Restricted(
        val reason: RestrictionReason,
        userMessage: String
    ) : MetaExtractionError(userMessage, canFallback = false)

    /**
     * Target post found, but contains no downloadable video (image or text only).
     * Strictly prohibited from falling back to prevent grabbing unrelated recommended videos.
     */
    class NoVideo(
        userMessage: String = "此貼文未包含可下載的影片內容 (可能為純文字或純圖片)"
    ) : MetaExtractionError(userMessage, canFallback = false)

    /**
     * Malformed or unrecognized URL structure.
     */
    class InvalidUrl(
        userMessage: String = "無效的貼文網址，無法識別目標內容"
    ) : MetaExtractionError(userMessage, canFallback = false)
}

/**
 * Normalized media info extracted natively from Instagram or Threads.
 */
data class ExtractedMetaMedia(
    val postId: String,
    val canonicalUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val progressiveVideoUrls: List<String>,
    val dashVideoUrl: String? = null,
    val dashAudioUrl: String? = null,
    val heights: List<Int> = emptyList(),
    val durationSeconds: Long? = null
)

/**
 * Result wrapper for native extraction.
 */
sealed class MetaExtractionResult {
    data class Success(val media: ExtractedMetaMedia) : MetaExtractionResult()
    data class Failure(val error: MetaExtractionError) : MetaExtractionResult()
}
