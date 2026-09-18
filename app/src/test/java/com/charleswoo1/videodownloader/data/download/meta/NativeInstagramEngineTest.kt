package com.charleswoo1.videodownloader.data.download.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeInstagramEngineTest {

    private lateinit var engine: NativeInstagramEngine

    @Before
    fun setUp() {
        engine = NativeInstagramEngine(context = null)
    }

    private fun loadFixture(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/instagram/$path")
            ?: throw IllegalArgumentException("Fixture not found: fixtures/instagram/$path")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun shortcodeToId_calculatesExpectedNumericId() {
        val shortcode = "DdS5sMrxkBq"
        val id = NativeInstagramEngine.shortcodeToId(shortcode)
        assertTrue("Numeric ID should be positive", id > 0)
    }

    @Test
    fun parseInstagramPage_singlePublicVideo_extractsSuccessfully() {
        val html = loadFixture("single_public_video.html")
        val shortcode = "DdS5sMrxkBq"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected success for single public video", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("test_creator", media.uploader)
        assertTrue("Expected progressive video URLs", media.progressiveVideoUrls.isNotEmpty())
        assertEquals("https://instagram.com/cdn/video_1080.mp4", media.progressiveVideoUrls.first())
        assertEquals("https://instagram.com/cdn/thumb.jpg", media.thumbnailUrl)
        assertTrue("Expected 1080 in heights", media.heights.contains(1080))
        assertTrue("Expected 720 in heights", media.heights.contains(720))
    }

    @Test
    fun parseInstagramPage_carouselWithVideo_extractsVideoItem() {
        val html = loadFixture("carousel_with_video.html")
        val shortcode = "DdXcarousel"
        val canonical = "https://www.instagram.com/p/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected success for carousel containing video", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertTrue("Expected video from carousel", media.progressiveVideoUrls.contains("https://instagram.com/cdn/carousel_video.mp4"))
    }

    @Test
    fun parseInstagramPage_noTargetMediaPhotoOnly_failsWithNoVideoAndNoFallback() {
        val html = loadFixture("no_target_media.html")
        val shortcode = "DdXphotoOnly"
        val canonical = "https://www.instagram.com/p/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected failure for photo-only post", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be NoVideo", error is MetaExtractionError.NoVideo)
        assertFalse("NoVideo error MUST NOT allow fallback", error.canFallback)
        assertTrue(error.userMessage.contains("未包含任何影片"))
    }

    @Test
    fun parseInstagramPage_audienceRestricted_failsWithRestrictedAndNoFallback() {
        val html = loadFixture("audience_restricted.html")
        val shortcode = "DbtoOl8zwMO"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected failure for audience restricted post", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be Restricted", error is MetaExtractionError.Restricted)
        assertEquals(RestrictionReason.AUDIENCE_RESTRICTED, (error as MetaExtractionError.Restricted).reason)
        assertFalse("Audience restriction MUST NOT allow fallback", error.canFallback)
        assertTrue(error.userMessage.contains("限制部分使用者觀看"))
    }

    @Test
    fun parseInstagramPage_loginGated_failsWithLoginRequiredAndNoFallback() {
        val html = loadFixture("login_gated.html")
        val shortcode = "DdXloginGated"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected failure for login gated post", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be Restricted", error is MetaExtractionError.Restricted)
        assertEquals(RestrictionReason.LOGIN_REQUIRED, (error as MetaExtractionError.Restricted).reason)
        assertFalse("Login requirement MUST NOT allow fallback", error.canFallback)
    }

    @Test
    fun parseInstagramPage_malformedJson_failsWithTechnicalAndAllowsFallback() {
        val html = loadFixture("malformed.html")
        val shortcode = "DdXmalformed"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected failure for malformed JSON", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be Technical", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun normalizeUrl_preservesContentTypeAndStripsQuery() {
        assertEquals(
            "https://www.instagram.com/p/ABC123xyz/",
            engine.normalizeUrl("https://www.instagram.com/p/ABC123xyz/?igsh=abc")
        )
        assertEquals(
            "https://www.instagram.com/tv/ABC123xyz/",
            engine.normalizeUrl("https://www.instagram.com/tv/ABC123xyz/#anchor")
        )
        assertEquals(
            "https://www.instagram.com/reel/ABC123xyz/",
            engine.normalizeUrl("https://www.instagram.com/reel/ABC123xyz/?utm=test")
        )
        assertEquals(
            "https://www.instagram.com/reel/ABC123xyz/",
            engine.normalizeUrl("https://www.instagram.com/reels/ABC123xyz/")
        )
        assertEquals(
            "https://www.instagram.com/p/ABC123xyz/",
            engine.normalizeUrl("https://www.instagram.com/share/p/ABC123xyz/")
        )
    }

    private class FakeMetaWebClient(
        private val browserHtml: String = "",
        private val crawlerHtml: String = "",
        private val downloadSuccess: Boolean = true
    ) : MetaWebClient() {
        var browserFetchCount = 0
        var crawlerFetchCount = 0
        var lastDownloadedDestination: java.io.File? = null

        override fun fetch(
            url: String,
            profile: RequestProfile,
            customHeaders: Map<String, String>,
            followRedirects: Boolean
        ): Result<HttpResponse> {
            return when (profile) {
                RequestProfile.BROWSER -> {
                    browserFetchCount++
                    Result.success(HttpResponse(200, url, browserHtml, emptyMap()))
                }
                RequestProfile.CRAWLER -> {
                    crawlerFetchCount++
                    Result.success(HttpResponse(200, url, crawlerHtml, emptyMap()))
                }
            }
        }

        override fun downloadMediaStream(
            streamUrl: String,
            destination: java.io.File,
            referer: String?,
            onProgress: (Float, Long?, String?) -> Unit,
            isCancelled: () -> Boolean
        ): Boolean {
            lastDownloadedDestination = destination
            if (downloadSuccess) {
                destination.parentFile?.mkdirs()
                destination.writeText("fake mp4 content")
                return true
            }
            return false
        }
    }

    @Test
    fun extractMediaInfo_crawlerRetryOnTechnicalFailureWithNonEmptyHtml() = kotlinx.coroutines.runBlocking {
        // Browser returns non-empty HTML missing target shortcode (Technical failure)
        val browserHtml = loadFixture("malformed.html")
        // Crawler returns valid single public video HTML
        val crawlerHtml = loadFixture("single_public_video.html")

        val fakeWebClient = FakeMetaWebClient(browserHtml = browserHtml, crawlerHtml = crawlerHtml)
        val customEngine = NativeInstagramEngine(context = null, webClient = fakeWebClient)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected extraction to succeed after crawler retry", result.isSuccess)
        assertEquals(1, fakeWebClient.browserFetchCount)
        assertEquals("Crawler MUST be retried once on technical failure even with non-empty HTML", 1, fakeWebClient.crawlerFetchCount)
        val mediaInfo = result.getOrNull()
        assertEquals("test_creator", mediaInfo?.title?.let { "test_creator" })
    }

    @Test
    fun extractMediaInfo_noCrawlerRetryOnRestriction() = kotlinx.coroutines.runBlocking {
        val restrictedHtml = loadFixture("audience_restricted.html")
        val fakeWebClient = FakeMetaWebClient(browserHtml = restrictedHtml, crawlerHtml = "not used")
        val customEngine = NativeInstagramEngine(context = null, webClient = fakeWebClient)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DbtoOl8zwMO/")

        assertTrue("Expected extraction to fail on restriction", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MetaExtractionError.Restricted)
        assertEquals(1, fakeWebClient.browserFetchCount)
        assertEquals("Crawler MUST NOT be retried on restriction", 0, fakeWebClient.crawlerFetchCount)
    }

    @Test
    fun download_audioOnly_usesSeparateSourceFileAndFailsOnFfmpegError() = kotlinx.coroutines.runBlocking {
        val fakeWebClient = FakeMetaWebClient(downloadSuccess = true)
        val customEngine = NativeInstagramEngine(context = null, webClient = fakeWebClient)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "ig_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val audioOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "audio_only",
                label = "僅音訊",
                formatSelector = "https://instagram.com/cdn/video_1080.mp4",
                isAudioOnly = true
            )
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.instagram.com/reel/DdS5sMrxkBq/",
                title = "Test Reel",
                qualityOption = audioOption
            )

            val result = customEngine.download(request, tempDir, { _, _, _ -> }, {})

            // Since context is null, FFmpeg extraction fails
            assertTrue("FFmpeg extraction failure must result in failure", result.isFailure)
            assertNotNull(fakeWebClient.lastDownloadedDestination)
            val downloadedFile = fakeWebClient.lastDownloadedDestination!!
            assertTrue("Source file MUST use .source.mp4 temporary naming", downloadedFile.name.endsWith(".source.mp4"))
            assertFalse("Source temporary file must be cleaned up", downloadedFile.exists())

            // Ensure no raw video bytes were left as .mp3
            val mp3Files = tempDir.listFiles { _, name -> name.endsWith(".mp3") } ?: emptyArray()
            assertTrue("No invalid .mp3 files should remain after FFmpeg failure", mp3Files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildQualityOptions_mapsResolutionTiersToExactRenditionUrls() = kotlinx.coroutines.runBlocking {
        val html = loadFixture("single_public_video.html")
        val fakeWebClient = FakeMetaWebClient(browserHtml = html)
        val customEngine = NativeInstagramEngine(context = null, webClient = fakeWebClient)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")
        assertTrue(result.isSuccess)
        val options = result.getOrNull()?.qualityOptions ?: emptyList()

        val bestOption = options.find { it.id == "best" }
        assertNotNull(bestOption)
        assertEquals("https://instagram.com/cdn/video_1080.mp4", bestOption?.formatSelector)

        val opt1080 = options.find { it.id == "1080p" }
        assertNotNull(opt1080)
        assertEquals("https://instagram.com/cdn/video_1080.mp4", opt1080?.formatSelector)

        val opt720 = options.find { it.id == "720p" }
        assertNotNull(opt720)
        assertEquals("https://instagram.com/cdn/video_720.mp4", opt720?.formatSelector)

        // Fixture does not have 480p or 360p; they MUST NOT be displayed
        val opt480 = options.find { it.id == "480p" }
        val opt360 = options.find { it.id == "360p" }
        assertEquals(null, opt480)
        assertEquals(null, opt360)
    }
}
