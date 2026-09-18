package com.charleswoo1.videodownloader.data.download

/**
 * Standard error taxonomy for platform-native extractors and routers.
 */
enum class PlatformErrorCode(val isTerminal: Boolean) {
    // Content / terminal restrictions (strictly NO fallback)
    NO_VIDEO(isTerminal = true),
    PRIVATE_CONTENT(isTerminal = true),
    LOGIN_REQUIRED(isTerminal = true),
    AUDIENCE_RESTRICTED(isTerminal = true),
    AGE_RESTRICTED(isTerminal = true),
    DELETED_OR_NOT_FOUND(isTerminal = true),
    GEO_RESTRICTED(isTerminal = true),

    // Technical / fallback-eligible errors (fallback permitted)
    NETWORK(isTerminal = false),
    PAGE_VARIANT_UNSUPPORTED(isTerminal = false),
    TARGET_NOT_IN_PAGE_DATA(isTerminal = false),
    PARSE_ERROR(isTerminal = false),
    API_ERROR(isTerminal = false),
    TOKEN_REFRESH_FAILED(isTerminal = false),
    MEDIA_URL_UNSUPPORTED(isTerminal = false),
    TRANSIENT_HTTP_ERROR(isTerminal = false),

    // Infrastructure errors
    FFMPEG_ERROR(isTerminal = true),
    STORAGE_ERROR(isTerminal = true),
    CANCELLED(isTerminal = true)
}

/**
 * Structured platform extraction exception.
 * [canFallback] indicates whether router is allowed to attempt secondary engine fallback.
 */
open class PlatformExtractionError(
    val code: PlatformErrorCode,
    val userMessage: String,
    val canFallback: Boolean = !code.isTerminal,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    class NoVideo(
        userMessage: String = "此貼文未包含可下載的影片內容（可能為純文字或純圖片）"
    ) : PlatformExtractionError(PlatformErrorCode.NO_VIDEO, userMessage, canFallback = false)

    class PrivateContent(
        userMessage: String = "此內容為私人貼文或受隱私設定保護，無法公開存取"
    ) : PlatformExtractionError(PlatformErrorCode.PRIVATE_CONTENT, userMessage, canFallback = false)

    class LoginRequired(
        userMessage: String = "此平台要求登入帳號後方可檢視內容"
    ) : PlatformExtractionError(PlatformErrorCode.LOGIN_REQUIRED, userMessage, canFallback = false)

    class AudienceRestricted(
        userMessage: String = "此內容受到年齡或特定受眾限制，無法公開存取"
    ) : PlatformExtractionError(PlatformErrorCode.AUDIENCE_RESTRICTED, userMessage, canFallback = false)

    class AgeRestricted(
        userMessage: String = "此內容設有年齡限制，無法公開存取"
    ) : PlatformExtractionError(PlatformErrorCode.AGE_RESTRICTED, userMessage, canFallback = false)

    class DeletedOrNotFound(
        userMessage: String = "此貼文可能已被刪除或原始網址不存在"
    ) : PlatformExtractionError(PlatformErrorCode.DELETED_OR_NOT_FOUND, userMessage, canFallback = false)

    class GeoRestricted(
        userMessage: String = "此內容在您目前所在的地區無法存取"
    ) : PlatformExtractionError(PlatformErrorCode.GEO_RESTRICTED, userMessage, canFallback = false)

    class NetworkError(
        val detail: String,
        userMessage: String = "網路連線異常，無法完成擷取請求",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.NETWORK, userMessage, canFallback = true, cause = cause)

    class PageVariantUnsupported(
        val detail: String,
        userMessage: String = "未支援的頁面呈現變體，無法解析媒體資訊",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.PAGE_VARIANT_UNSUPPORTED, userMessage, canFallback = true, cause = cause)

    class TargetNotInPageData(
        val detail: String,
        userMessage: String = "頁面資料中未找到指定目標貼文的媒體資訊",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.TARGET_NOT_IN_PAGE_DATA, userMessage, canFallback = true, cause = cause)

    class ParseError(
        val detail: String,
        userMessage: String = "無法解析平台回傳之媒體資料結構",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.PARSE_ERROR, userMessage, canFallback = true, cause = cause)

    class ApiError(
        val httpCode: Int,
        val detail: String,
        userMessage: String = "平台 API 回傳錯誤碼 ($httpCode)",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.API_ERROR, userMessage, canFallback = true, cause = cause)

    class TokenRefreshFailed(
        val detail: String,
        userMessage: String = "擷取認證 Token 更新失敗",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.TOKEN_REFRESH_FAILED, userMessage, canFallback = true, cause = cause)

    class MediaUrlUnsupported(
        val detail: String,
        userMessage: String = "擷取之媒體串流 URL 格式無法直接下載",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.MEDIA_URL_UNSUPPORTED, userMessage, canFallback = true, cause = cause)

    class TransientHttpError(
        val httpCode: Int,
        val detail: String,
        userMessage: String = "暫時性 HTTP 網路錯誤 ($httpCode)，請稍後再試",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.TRANSIENT_HTTP_ERROR, userMessage, canFallback = true, cause = cause)

    class FfmpegError(
        val detail: String,
        userMessage: String = "音訊/視訊合成處理失敗",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.FFMPEG_ERROR, userMessage, canFallback = false, cause = cause)

    class StorageError(
        val detail: String,
        userMessage: String = "本機儲存空間或權限錯誤，無法儲存檔案",
        cause: Throwable? = null
    ) : PlatformExtractionError(PlatformErrorCode.STORAGE_ERROR, userMessage, canFallback = false, cause = cause)

    class Cancelled(
        userMessage: String = "下載作業已取消"
    ) : PlatformExtractionError(PlatformErrorCode.CANCELLED, userMessage, canFallback = false)
}
