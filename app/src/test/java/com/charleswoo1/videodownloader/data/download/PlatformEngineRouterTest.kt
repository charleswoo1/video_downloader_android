package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.data.download.meta.MetaExtractionError
import com.charleswoo1.videodownloader.data.download.meta.RestrictionReason
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PlatformEngineRouterTest {

    private class FakeMediaEngine(
        override val name: String,
        private val supportedPlatform: Platform
    ) : PlatformMediaEngine {
        var extractResult: Result<MediaInfo> = Result.failure(IllegalStateException("Not set"))
        var extractCalled = false
        var downloadCalled = false
        var cancelCalled = false

        var downloadResult: Result<File> = Result.success(File("/tmp/fake_out.mp4"))
        override fun supports(platform: Platform): Boolean = platform == supportedPlatform

        override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
            extractCalled = true
            return extractResult
        }

        override suspend fun download(
            request: DownloadRequest,
            destDir: File,
            onProgress: (Float, Long?, String?) -> Unit,
            onStatus: (String) -> Unit
        ): Result<File> {
            downloadCalled = true
            return downloadResult
        }

        override fun cancelDownload() {
            cancelCalled = true
        }
    }

    private class FakeYtDlpEngine : DownloadEngine {
        var extractResult: Result<MediaInfo> = Result.failure(IllegalStateException("Not set"))
        var extractCalled = false
        var downloadCalled = false
        var cancelCalled = false
        var downloadResult: Result<File> = Result.success(File("/tmp/ytdlp_out.mp4"))
        var lastDownloadRequest: DownloadRequest? = null

        override fun isInitialized(): Boolean = true

        override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
            extractCalled = true
            return extractResult
        }

        override suspend fun download(
            request: DownloadRequest,
            destDir: File,
            onProgress: (Float, Long?, String?) -> Unit,
            onStatus: (String) -> Unit
        ): Result<File> {
            downloadCalled = true
            lastDownloadRequest = request
            return downloadResult
        }

        override fun cancelDownload() {
            cancelCalled = true
        }
    }

    private lateinit var fakeInstagramEngine: FakeMediaEngine
    private lateinit var fakeThreadsEngine: FakeMediaEngine
    private lateinit var fakeXEngine: FakeMediaEngine
    private lateinit var fakeYtDlpEngine: FakeYtDlpEngine
    private lateinit var router: PlatformEngineRouter

    @Before
    fun setUp() {
        fakeInstagramEngine = FakeMediaEngine("NativeInstagramEngine", Platform.INSTAGRAM)
        fakeThreadsEngine = FakeMediaEngine("NativeThreadsEngine", Platform.THREADS)
        fakeXEngine = FakeMediaEngine("NativeXEngine", Platform.X)
        fakeYtDlpEngine = FakeYtDlpEngine()

        router = PlatformEngineRouter(
            context = null,
            nativeInstagramEngine = fakeInstagramEngine,
            nativeThreadsEngine = fakeThreadsEngine,
            nativeXEngine = fakeXEngine,
            ytDlpEngine = fakeYtDlpEngine
        )
    }

    private fun createMediaInfo(platform: Platform, title: String): MediaInfo {
        return MediaInfo(
            sourceUrl = "https://example.com",
            title = title,
            platform = platform,
            extractor = platform.displayName,
            qualityOptions = listOf(QualityOption("best", "Best", "https://example.com/stream.mp4"))
        )
    }

    @Test
    fun extractMediaInfo_instagramNativeSuccess_returnsNativeAndNoFallback() = runBlocking {
        val expectedInfo = createMediaInfo(Platform.INSTAGRAM, "IG Reel")
        fakeInstagramEngine.extractResult = Result.success(expectedInfo)

        val result = router.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue(result.isSuccess)
        assertEquals("IG Reel", result.getOrNull()?.title)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertFalse(fakeYtDlpEngine.extractCalled)
        assertEquals("NativeInstagramEngine", router.lastTrace?.finalEngine)
        assertFalse(router.lastTrace?.fallbackAttempted ?: true)
        assertTrue(router.lastTrace?.toDisplaySummary()?.contains("fallback: no") == true)
    }

    @Test
    fun extractMediaInfo_instagramAudienceRestricted_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeInstagramEngine.extractResult = Result.failure(
            MetaExtractionError.Restricted(
                RestrictionReason.AUDIENCE_RESTRICTED,
                "此 Instagram 內容限制部分使用者觀看，匿名模式無法存取。"
            )
        )

        val result = router.extractMediaInfo("https://www.instagram.com/reel/DbtoOl8zwMO/")

        assertTrue("Should report failure", result.isFailure)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertFalse("MUST NOT fallback to yt-dlp on audience restriction!", fakeYtDlpEngine.extractCalled)
        assertFalse(router.lastTrace?.fallbackAttempted ?: true)
        assertEquals("AUDIENCE_RESTRICTED", router.lastTrace?.primaryResultCategory)
    }

    @Test
    fun extractMediaInfo_instagramNoVideo_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeInstagramEngine.extractResult = Result.failure(
            PlatformExtractionError.NoVideo("此 Instagram 貼文未包含任何影片")
        )

        val result = router.extractMediaInfo("https://www.instagram.com/p/DdXphotoOnly/")

        assertTrue("Should report failure", result.isFailure)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertFalse("MUST NOT fallback to yt-dlp on photo-only post!", fakeYtDlpEngine.extractCalled)
        assertFalse(router.lastTrace?.fallbackAttempted ?: true)
        assertEquals("NO_VIDEO", router.lastTrace?.primaryResultCategory)
    }

    @Test
    fun extractMediaInfo_instagramTechnicalFailure_fallsBackToYtDlp() = runBlocking {
        fakeInstagramEngine.extractResult = Result.failure(
            PlatformExtractionError.ParseError("Schema changed")
        )
        val ytdlpInfo = createMediaInfo(Platform.INSTAGRAM, "yt-dlp IG Reel")
        fakeYtDlpEngine.extractResult = Result.success(ytdlpInfo)

        val result = router.extractMediaInfo("https://www.instagram.com/reel/DdXfLoyTs__/")

        assertTrue(result.isSuccess)
        assertEquals("yt-dlp IG Reel", result.getOrNull()?.title)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertTrue("MUST attempt yt-dlp fallback on technical failure", fakeYtDlpEngine.extractCalled)
        assertTrue(router.lastTrace?.fallbackAttempted ?: false)
        assertEquals("YtDlpDownloadEngine", router.lastTrace?.finalEngine)
        assertEquals("PARSE_ERROR", router.lastTrace?.primaryResultCategory)
    }

    @Test
    fun extractMediaInfo_threadsNativeSuccess_returnsNativeAndNoFallback() = runBlocking {
        val expectedInfo = createMediaInfo(Platform.THREADS, "Threads Video")
        fakeThreadsEngine.extractResult = Result.success(expectedInfo)

        val result = router.extractMediaInfo("https://www.threads.com/@user/post/DdZ123")

        assertTrue(result.isSuccess)
        assertEquals("Threads Video", result.getOrNull()?.title)
        assertTrue(fakeThreadsEngine.extractCalled)
        assertFalse(fakeYtDlpEngine.extractCalled)
        assertEquals("NativeThreadsEngine", router.lastTrace?.finalEngine)
    }

    @Test
    fun extractMediaInfo_threadsPrivateOrDeleted_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeThreadsEngine.extractResult = Result.failure(
            PlatformExtractionError.DeletedOrNotFound("Threads 貼文不存在或已刪除")
        )

        val result = router.extractMediaInfo("https://www.threads.com/@user/post/DdZprivate")

        assertTrue(result.isFailure)
        assertTrue(fakeThreadsEngine.extractCalled)
        assertFalse("MUST NOT fallback on private/deleted content!", fakeYtDlpEngine.extractCalled)
        assertEquals("DELETED_OR_NOT_FOUND", router.lastTrace?.primaryResultCategory)
    }

    @Test
    fun extractMediaInfo_threadsTechnicalFailure_fallsBackToYtDlp() = runBlocking {
        fakeThreadsEngine.extractResult = Result.failure(
            PlatformExtractionError.TargetNotInPageData("Target shortcode missing")
        )
        val ytdlpInfo = createMediaInfo(Platform.THREADS, "yt-dlp Threads Video")
        fakeYtDlpEngine.extractResult = Result.success(ytdlpInfo)

        val result = router.extractMediaInfo("https://www.threads.com/share/_2DcaFS7L/")

        assertTrue(result.isSuccess)
        assertTrue(fakeThreadsEngine.extractCalled)
        assertTrue("MUST fallback to yt-dlp on technical missing target", fakeYtDlpEngine.extractCalled)
        assertEquals("TARGET_NOT_IN_PAGE_DATA", router.lastTrace?.primaryResultCategory)
        assertEquals("YtDlpDownloadEngine", router.lastTrace?.finalEngine)
    }

    @Test
    fun extractMediaInfo_xNativeSuccess_returnsNativeAndNoFallback() = runBlocking {
        val expectedInfo = createMediaInfo(Platform.X, "X Video Tweet")
        fakeXEngine.extractResult = Result.success(expectedInfo)

        val result = router.extractMediaInfo("https://x.com/Twitter/status/1234567890")

        assertTrue(result.isSuccess)
        assertEquals("X Video Tweet", result.getOrNull()?.title)
        assertTrue(fakeXEngine.extractCalled)
        assertFalse(fakeYtDlpEngine.extractCalled)
        assertEquals("NativeXEngine", router.lastTrace?.finalEngine)
        assertFalse(router.lastTrace?.fallbackAttempted ?: true)
    }

    @Test
    fun extractMediaInfo_xNoVideo_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeXEngine.extractResult = Result.failure(
            PlatformExtractionError.NoVideo("此 X 貼文為純文字，未包含影片")
        )

        val result = router.extractMediaInfo("https://x.com/SmallQQQQQ/status/2099089385958645960")

        assertTrue(result.isFailure)
        assertTrue(fakeXEngine.extractCalled)
        assertFalse("MUST NOT fallback to yt-dlp on explicit NO_VIDEO!", fakeYtDlpEngine.extractCalled)
        assertFalse(router.lastTrace?.fallbackAttempted ?: true)
        assertEquals("NO_VIDEO", router.lastTrace?.primaryResultCategory)
    }

    @Test
    fun extractMediaInfo_xTechnicalFailure_fallsBackToYtDlp() = runBlocking {
        fakeXEngine.extractResult = Result.failure(
            PlatformExtractionError.ApiError(403, "API access blocked")
        )
        val ytdlpInfo = createMediaInfo(Platform.X, "yt-dlp X Video")
        fakeYtDlpEngine.extractResult = Result.success(ytdlpInfo)

        val result = router.extractMediaInfo("https://x.com/user/status/9876543210")

        assertTrue(result.isSuccess)
        assertTrue(fakeXEngine.extractCalled)
        assertTrue("MUST fallback to yt-dlp on technical API error", fakeYtDlpEngine.extractCalled)
        assertEquals("API_ERROR", router.lastTrace?.primaryResultCategory)
        assertEquals("YtDlpDownloadEngine", router.lastTrace?.finalEngine)
    }

    @Test
    fun extractMediaInfo_youtube_routesDirectlyToYtDlp() = runBlocking {
        val ytInfo = createMediaInfo(Platform.YOUTUBE, "YouTube Video")
        fakeYtDlpEngine.extractResult = Result.success(ytInfo)

        val result = router.extractMediaInfo("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue(result.isSuccess)
        assertEquals("YouTube Video", result.getOrNull()?.title)
        assertFalse("Instagram engine must not be called for YouTube", fakeInstagramEngine.extractCalled)
        assertFalse("Threads engine must not be called for YouTube", fakeThreadsEngine.extractCalled)
        assertFalse("X engine must not be called for YouTube", fakeXEngine.extractCalled)
        assertTrue(fakeYtDlpEngine.extractCalled)
        assertEquals("YtDlpDownloadEngine", router.lastTrace?.finalEngine)
    }

    @Test
    fun download_directMediaUrl_dispatchesToNativeEngine() = runBlocking {
        val req = DownloadRequest(
            url = "https://www.instagram.com/reel/123",
            title = "Test",
            qualityOption = QualityOption("best", "Best", "https://instagram.com/stream.mp4")
        )
        val dest = File("/tmp")

        router.download(req, dest, { _, _, _ -> }, {})

        assertTrue(fakeInstagramEngine.downloadCalled)
        assertFalse(fakeYtDlpEngine.downloadCalled)
    }

    @Test
    fun download_nativeXDirectMedia_dispatchesToNativeXEngine() = runBlocking {
        val req = DownloadRequest(
            url = "https://x.com/user/status/123",
            title = "X Video",
            qualityOption = QualityOption("best", "Best", "https://video.twimg.com/stream.mp4")
        )
        val dest = File("/tmp")

        router.download(req, dest, { _, _, _ -> }, {})

        assertTrue(fakeXEngine.downloadCalled)
        assertFalse(fakeYtDlpEngine.downloadCalled)
    }

    @Test
    fun download_nativeXFailure_fallsBackToYtDlp() = runBlocking {
        fakeXEngine.downloadResult = Result.failure(IllegalStateException("Stream download failed"))
        fakeYtDlpEngine.downloadResult = Result.success(File("/tmp/fallback_x.mp4"))

        val req = DownloadRequest(
            url = "https://x.com/user/status/123",
            title = "X Video",
            qualityOption = QualityOption("best", "Best", "https://video.twimg.com/stream.mp4")
        )
        val dest = File("/tmp")

        val result = router.download(req, dest, { _, _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertTrue(fakeXEngine.downloadCalled)
        assertTrue(fakeYtDlpEngine.downloadCalled)
        assertEquals("bestvideo+bestaudio/best", fakeYtDlpEngine.lastDownloadRequest?.qualityOption?.formatSelector)
    }

    @Test
    fun download_nativeThreadsFailure_fallsBackToYtDlp() = runBlocking {
        fakeThreadsEngine.downloadResult = Result.failure(IllegalStateException("FFmpeg merge failure"))
        fakeYtDlpEngine.downloadResult = Result.success(File("/tmp/fallback_threads.mp4"))

        val req = DownloadRequest(
            url = "https://www.threads.com/@user/post/123",
            title = "Threads Test",
            qualityOption = QualityOption("best", "Best", "https://threads.net/v.mp4|https://threads.net/a.mp4")
        )
        val dest = File("/tmp")

        val result = router.download(req, dest, { _, _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertTrue("Native engine must have been attempted", fakeThreadsEngine.downloadCalled)
        assertTrue("yt-dlp engine must have been invoked as fallback", fakeYtDlpEngine.downloadCalled)
        assertEquals(
            "Fallback selector should be normalized for yt-dlp",
            "bestvideo+bestaudio/best",
            fakeYtDlpEngine.lastDownloadRequest?.qualityOption?.formatSelector
        )
    }

    @Test
    fun download_nativeInstagramFailure_fallsBackToYtDlp() = runBlocking {
        fakeInstagramEngine.downloadResult = Result.failure(IllegalStateException("Stream download failed"))
        fakeYtDlpEngine.downloadResult = Result.success(File("/tmp/fallback_ig.mp4"))

        val req = DownloadRequest(
            url = "https://www.instagram.com/reel/123",
            title = "IG Test",
            qualityOption = QualityOption("best", "Best", "https://instagram.com/v.mp4")
        )
        val dest = File("/tmp")

        val result = router.download(req, dest, { _, _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertTrue("Native Instagram engine must have been attempted", fakeInstagramEngine.downloadCalled)
        assertTrue("yt-dlp engine must have been invoked as fallback", fakeYtDlpEngine.downloadCalled)
        assertEquals(
            "Fallback selector should be normalized for yt-dlp",
            "bestvideo+bestaudio/best",
            fakeYtDlpEngine.lastDownloadRequest?.qualityOption?.formatSelector
        )
    }

    @Test
    fun cancelDownload_cancelsAllEngines() {
        router.cancelDownload()

        assertTrue(fakeInstagramEngine.cancelCalled)
        assertTrue(fakeThreadsEngine.cancelCalled)
        assertTrue(fakeXEngine.cancelCalled)
        assertTrue(fakeYtDlpEngine.cancelCalled)
    }

    @Test
    fun extractMediaInfo_primaryParseError_fallbackFailure_retainsPrimaryParseError() = runBlocking {
        fakeThreadsEngine.extractResult = Result.failure(
            PlatformExtractionError.ParseError(
                "Threads 已找到貼文連結，但目前頁面未提供可解析的目標媒體資料。",
                internalReason = "NO_TARGET_FOUND"
            )
        )
        fakeYtDlpEngine.extractResult = Result.failure(
            PlatformExtractionError.DeletedOrNotFound(
                "Threads 貼文不存在、設為私人內容或需要登入帳號驗證"
            )
        )

        val result = router.extractMediaInfo("https://www.threads.com/@user/post/DdcJ4wwkjVQ")
        assertTrue("Extraction must fail", result.isFailure)
        val err = result.exceptionOrNull() as? PlatformExtractionError
        assertEquals(PlatformErrorCode.PARSE_ERROR, err?.code)
        assertEquals("NO_TARGET_FOUND", err?.internalReason)
        assertTrue(err?.userMessage?.contains("Threads 已找到貼文連結") == true)
    }
}
