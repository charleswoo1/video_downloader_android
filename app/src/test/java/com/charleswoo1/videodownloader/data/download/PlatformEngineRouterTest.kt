package com.charleswoo1.videodownloader.data.download

import android.content.Context
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
    private lateinit var fakeYtDlpEngine: FakeYtDlpEngine
    private lateinit var router: PlatformEngineRouter

    @Before
    fun setUp() {
        fakeInstagramEngine = FakeMediaEngine("NativeInstagramEngine", Platform.INSTAGRAM)
        fakeThreadsEngine = FakeMediaEngine("NativeThreadsEngine", Platform.THREADS)
        fakeYtDlpEngine = FakeYtDlpEngine()

        router = PlatformEngineRouter(
            context = null,
            nativeInstagramEngine = fakeInstagramEngine,
            nativeThreadsEngine = fakeThreadsEngine,
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
        assertEquals("NativeInstagramEngine", router.lastRoutingLog?.finalEngine)
        assertFalse(router.lastRoutingLog?.fallbackAttempted ?: true)
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
        assertFalse(router.lastRoutingLog?.fallbackAttempted ?: true)
    }

    @Test
    fun extractMediaInfo_instagramNoVideo_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeInstagramEngine.extractResult = Result.failure(
            MetaExtractionError.NoVideo("此 Instagram 貼文未包含任何影片 (不支援純圖片下載)")
        )

        val result = router.extractMediaInfo("https://www.instagram.com/p/DdXphotoOnly/")

        assertTrue("Should report failure", result.isFailure)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertFalse("MUST NOT fallback to yt-dlp on photo-only post!", fakeYtDlpEngine.extractCalled)
        assertFalse(router.lastRoutingLog?.fallbackAttempted ?: true)
    }

    @Test
    fun extractMediaInfo_instagramTechnicalFailure_fallsBackToYtDlp() = runBlocking {
        fakeInstagramEngine.extractResult = Result.failure(
            MetaExtractionError.Technical("Schema changed")
        )
        val ytdlpInfo = createMediaInfo(Platform.INSTAGRAM, "yt-dlp IG Reel")
        fakeYtDlpEngine.extractResult = Result.success(ytdlpInfo)

        val result = router.extractMediaInfo("https://www.instagram.com/reel/DdXfLoyTs__/")

        assertTrue(result.isSuccess)
        assertEquals("yt-dlp IG Reel", result.getOrNull()?.title)
        assertTrue(fakeInstagramEngine.extractCalled)
        assertTrue("MUST attempt yt-dlp fallback on technical failure", fakeYtDlpEngine.extractCalled)
        assertTrue(router.lastRoutingLog?.fallbackAttempted ?: false)
        assertEquals("YtDlpDownloadEngine", router.lastRoutingLog?.finalEngine)
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
        assertEquals("NativeThreadsEngine", router.lastRoutingLog?.finalEngine)
    }

    @Test
    fun extractMediaInfo_threadsPrivateOrDeleted_terminatesImmediatelyWithoutFallback() = runBlocking {
        fakeThreadsEngine.extractResult = Result.failure(
            MetaExtractionError.Restricted(
                RestrictionReason.DELETED_OR_PRIVATE,
                "Threads 貼文不存在、設為私人內容或需要登入帳號驗證"
            )
        )

        val result = router.extractMediaInfo("https://www.threads.com/@user/post/DdZprivate")

        assertTrue(result.isFailure)
        assertTrue(fakeThreadsEngine.extractCalled)
        assertFalse("MUST NOT fallback on private/deleted content!", fakeYtDlpEngine.extractCalled)
    }

    @Test
    fun extractMediaInfo_threadsTechnicalFailure_fallsBackToYtDlp() = runBlocking {
        fakeThreadsEngine.extractResult = Result.failure(
            MetaExtractionError.Technical("Target shortcode missing")
        )
        val ytdlpInfo = createMediaInfo(Platform.THREADS, "yt-dlp Threads Video")
        fakeYtDlpEngine.extractResult = Result.success(ytdlpInfo)

        val result = router.extractMediaInfo("https://www.threads.com/share/_2DcaFS7L/")

        assertTrue(result.isSuccess)
        assertTrue(fakeThreadsEngine.extractCalled)
        assertTrue("MUST fallback to yt-dlp on technical missing target", fakeYtDlpEngine.extractCalled)
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
        assertTrue(fakeYtDlpEngine.extractCalled)
        assertEquals("YtDlpDownloadEngine", router.lastRoutingLog?.finalEngine)
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
    fun download_ytdlpSelector_dispatchesToYtDlpEngine() = runBlocking {
        val req = DownloadRequest(
            url = "https://www.youtube.com/watch?v=123",
            title = "Test",
            qualityOption = QualityOption("best", "Best", "bestvideo+bestaudio/best")
        )
        val dest = File("/tmp")

        router.download(req, dest, { _, _, _ -> }, {})

        assertFalse(fakeInstagramEngine.downloadCalled)
        assertTrue(fakeYtDlpEngine.downloadCalled)
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
        assertTrue(fakeYtDlpEngine.cancelCalled)
    }
}
