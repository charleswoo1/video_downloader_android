package com.charleswoo1.videodownloader.data.download.meta

import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError

/**
 * Categorization of content and access restrictions.
 */
enum class RestrictionReason {
    AUDIENCE_RESTRICTED,
    LOGIN_REQUIRED,
    DELETED_OR_PRIVATE
}

/**
 * Structured errors for Meta platform extraction, unified with [PlatformExtractionError].
 *
 * [canFallback] determines whether the router is permitted to attempt secondary engine fallback.
 * Content restrictions, deleted/private media, and posts without videos MUST NOT fall back.
 */
sealed class MetaExtractionError(
    code: PlatformErrorCode,
    userMessage: String,
    canFallback: Boolean,
    internalReason: String? = null,
    cause: Throwable? = null
) : PlatformExtractionError(code, userMessage, canFallback, internalReason, cause) {

    /**
     * Technical failures where secondary engine fallback is reasonable
     * (e.g. schema changed, parser exception, missing expected JSON).
     */
    class Technical(
        val detail: String,
        userMessage: String = "暫時無法解析此貼文。平台可能未提供匿名媒體資料，或頁面格式已變更。",
        internalReason: String? = null,
        cause: Throwable? = null
    ) : MetaExtractionError(
        PlatformErrorCode.PARSE_ERROR,
        userMessage,
        canFallback = true,
        internalReason = internalReason,
        cause = cause
    )

    /**
     * Access or audience restrictions (audience gated, login required, deleted/private).
     * Strictly prohibited from falling back.
     */
    class Restricted(
        val reason: RestrictionReason,
        userMessage: String,
        internalReason: String? = null
    ) : MetaExtractionError(
        when (reason) {
            RestrictionReason.AUDIENCE_RESTRICTED -> PlatformErrorCode.AUDIENCE_RESTRICTED
            RestrictionReason.LOGIN_REQUIRED -> PlatformErrorCode.LOGIN_REQUIRED
            RestrictionReason.DELETED_OR_PRIVATE -> PlatformErrorCode.DELETED_OR_NOT_FOUND
        },
        userMessage,
        canFallback = false,
        internalReason = internalReason
    )

    /**
     * Target post found, but contains no downloadable video (image or text only).
     * Strictly prohibited from falling back to prevent grabbing unrelated recommended videos.
     */
    class NoVideo(
        userMessage: String = "此貼文未包含可下載的影片內容 (可能為純文字或純圖片)",
        internalReason: String? = null
    ) : MetaExtractionError(PlatformErrorCode.NO_VIDEO, userMessage, canFallback = false, internalReason = internalReason)

    /**
     * Malformed or unrecognized URL structure.
     */
    class InvalidUrl(
        userMessage: String = "無效的貼文網址，無法識別目標內容",
        internalReason: String? = null
    ) : MetaExtractionError(PlatformErrorCode.PAGE_VARIANT_UNSUPPORTED, userMessage, canFallback = false, internalReason = internalReason)
}

/**
 * Represents a specific video stream rendition with dimension metadata.
 */
data class NativeMediaRendition(
    val url: String,
    val width: Int = 0,
    val height: Int = 0
) {
    val resolution: Int
        get() = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height, 0)
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
    val progressiveVideoUrls: List<String> = emptyList(),
    val renditions: List<NativeMediaRendition> = emptyList(),
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
