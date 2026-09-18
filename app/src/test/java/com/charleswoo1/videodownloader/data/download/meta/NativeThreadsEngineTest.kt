package com.charleswoo1.videodownloader.data.download.meta

import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeThreadsEngineTest {

    private lateinit var engine: NativeThreadsEngine

    @Before
    fun setUp() {
        engine = NativeThreadsEngine(context = null)
    }

    private fun loadFixture(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/threads/$path")
            ?: throw IllegalArgumentException("Fixture not found: fixtures/threads/$path")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun normalizeUrl_cleansAndNormalizesThreadsDomains() {
        assertEquals(
            "https://www.threads.com/@user/post/12345",
            engine.normalizeUrl("https://threads.net/@user/post/12345?x=1#fragment")
        )
        assertEquals(
            "https://www.threads.com/@user/post/12345",
            engine.normalizeUrl("https://www.threads.net/@user/post/12345/media")
        )
    }

    @Test
    fun parseThreadsPage_targetPostWithVideoVersions_extractsSuccessfully() {
        val html = loadFixture("target_post_video_versions.html")
        val shortcode = "DdZtargetPost"
        val canonical = "https://www.threads.com/@test_user/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for post with video_versions", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("test_user", media.uploader)
        assertEquals("Check out this Threads video", media.title)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/video_1080.mp4"))
        assertTrue(media.heights.contains(1080))
        assertTrue(media.heights.contains(720))
    }

    @Test
    fun parseThreadsPage_targetPostWithDash_extractsDashStreamPair() {
        val html = loadFixture("target_post_dash.html")
        val shortcode = "DddashPost"
        val canonical = "https://www.threads.com/@dash_creator/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for post with DASH manifest", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("https://threads.net/cdn/dash_video.mp4", media.dashVideoUrl)
        assertEquals("https://threads.net/cdn/dash_audio.mp4", media.dashAudioUrl)
    }

    @Test
    fun parseThreadsPage_carouselWithVideo_extractsChildVideo() {
        val html = loadFixture("carousel_video.html")
        val shortcode = "DdZcarouselPost"
        val canonical = "https://www.threads.com/@carousel_user/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for carousel post", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/carousel_vid.mp4"))
    }

    @Test
    fun parseThreadsPage_quotedMedia_extractsWrappedPostVideo() {
        val html = loadFixture("quoted_media.html")
        val shortcode = "DdZquotedPost"
        val canonical = "https://www.threads.com/@quoter_user/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for quoted post with video", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/quoted_vid.mp4"))
    }

    /**
     * MANDATORY TEST from Section 17 of Handoff:
     * "The 'target absent while unrelated video exists' test is mandatory.
     * It must prove the extractor refuses to download an unrelated clip."
     */
    @Test
    fun parseThreadsPage_targetAbsentWhileUnrelatedVideoExists_refusesToExtractUnrelatedClip() {
        val html = loadFixture("target_absent_unrelated_exists.html")
        val targetShortcode = "DdZmissingTarget"
        val canonical = "https://www.threads.com/@someone/post/$targetShortcode"

        val result = engine.parseThreadsPage(html, targetShortcode, canonical)

        // The extractor MUST FAIL and NOT return the unrelated recommendation clip (DdZunrelatedPost)
        assertTrue("Parser MUST report failure when target post is absent", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue(
            "Expected failure indicating target was not found in page data",
            error is MetaExtractionError.Technical || error is MetaExtractionError.Restricted
        )
    }

    @Test
    fun parseThreadsPage_malformedJson_failsWithTechnicalAndAllowsFallback() {
        val html = loadFixture("malformed.html")
        val shortcode = "DdZmalformed"
        val canonical = "https://www.threads.com/@user/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected failure for malformed JSON", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be Technical", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun parseThreadsPage_quotedAttachmentPost_extractsSuccessfully() {
        val html = loadFixture("quoted_attachment_post.html")
        val shortcode = "DdZquotedAttachPost"
        val canonical = "https://www.threads.com/@attach_quoter/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for quoted_attachment_post", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("attach_quoter", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/quoted_attach_vid.mp4"))
        assertTrue("Should contain 720 resolution", media.heights.contains(720))
    }

    @Test
    fun parseThreadsPage_linkedInlineMedia_extractsSuccessfully() {
        val html = loadFixture("linked_inline_media.html")
        val shortcode = "DdZlinkedInlinePost"
        val canonical = "https://www.threads.com/@inline_user/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for linked_inline_media", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("inline_user", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/linked_inline_vid.mp4"))
        assertTrue("Should contain 1080 resolution", media.heights.contains(1080))
    }

    private class FakePlatformHttpSession(
        private val browserHtml: String = "",
        private val mobileHtml: String = "",
        private val crawlerHtml: String = "",
        private val downloadSuccess: Boolean = true
    ) : PlatformHttpSession() {
        var desktopFetchCount = 0
        var mobileFetchCount = 0
        var crawlerFetchCount = 0
        var downloadedDestinations = mutableListOf<java.io.File>()

        override fun fetch(
            url: String,
            profile: RequestProfile,
            identity: BrowserIdentity,
            origin: String?,
            referer: String?,
            customHeaders: Map<String, String>,
            followRedirects: Boolean,
            body: ByteArray?,
            contentType: String?,
            method: String
        ): Result<HttpResponse> {
            return when (profile) {
                RequestProfile.DESKTOP_NAVIGATION -> {
                    desktopFetchCount++
                    Result.success(HttpResponse(200, url, browserHtml, emptyMap()))
                }
                RequestProfile.MOBILE_NAVIGATION -> {
                    mobileFetchCount++
                    Result.success(HttpResponse(200, url, mobileHtml, emptyMap()))
                }
                RequestProfile.CRAWLER_NAVIGATION -> {
                    crawlerFetchCount++
                    Result.success(HttpResponse(200, url, crawlerHtml, emptyMap()))
                }
                else -> Result.success(HttpResponse(200, url, browserHtml, emptyMap()))
            }
        }

        override fun downloadMediaStream(
            streamUrl: String,
            destination: java.io.File,
            referer: String?,
            origin: String?,
            onProgress: (Float, Long?, String?) -> Unit,
            isCancelled: () -> Boolean
        ): Boolean {
            downloadedDestinations.add(destination)
            if (downloadSuccess) {
                destination.parentFile?.mkdirs()
                destination.writeText("fake stream bytes")
                return true
            }
            return false
        }
    }

    @Test
    fun extractMediaInfo_crawlerRetryOnTechnicalFailureWithNonEmptyHtml() = kotlinx.coroutines.runBlocking {
        val browserHtml = loadFixture("malformed.html")
        val crawlerHtml = loadFixture("target_post_video_versions.html")

        val fakeSession = FakePlatformHttpSession(browserHtml = browserHtml, mobileHtml = browserHtml, crawlerHtml = crawlerHtml)
        val customEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.threads.com/@test_user/post/DdZtargetPost")

        assertTrue("Expected extraction to succeed after crawler retry", result.isSuccess)
        assertEquals(1, fakeSession.desktopFetchCount)
        assertEquals(1, fakeSession.mobileFetchCount)
        assertEquals("Crawler MUST be retried once on technical failure even with non-empty HTML", 1, fakeSession.crawlerFetchCount)
        val mediaInfo = result.getOrNull()
        assertEquals("Check out this Threads video", mediaInfo?.title)
    }

    @Test
    fun extractMediaInfo_noCrawlerRetryOnRestriction() = kotlinx.coroutines.runBlocking {
        // HTML containing deleted / private marker
        val restrictedHtml = "<html><body>Sorry, this page isn't available.</body></html>"
        val fakeSession = FakePlatformHttpSession(browserHtml = restrictedHtml, crawlerHtml = "not used")
        val customEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.threads.com/@test_user/post/DdZdeleted")

        assertTrue("Expected failure on restriction", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MetaExtractionError.Restricted)
        assertEquals(1, fakeSession.desktopFetchCount)
        assertEquals("Crawler MUST NOT be retried on restriction", 0, fakeSession.crawlerFetchCount)
    }

    @Test
    fun download_dashMergeFailure_deletesTempFilesAndReturnsFailure() = kotlinx.coroutines.runBlocking {
        val fakeSession = FakePlatformHttpSession(downloadSuccess = true)
        val customEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "threads_dash_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val dashOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "best",
                label = "最佳畫質",
                formatSelector = "https://threads.net/video.mp4|https://threads.net/audio.mp4"
            )
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@user/post/DdZdash",
                title = "Dash Post",
                qualityOption = dashOption
            )

            val result = customEngine.download(request, tempDir, { _, _, _ -> }, {})

            // Since context is null, FFmpeg merge fails
            assertTrue("DASH merge failure MUST return failure (not fake video-only success)", result.isFailure)

            // Verify video and audio part temp files are deleted
            val partFiles = tempDir.listFiles { _, name -> name.endsWith(".part") } ?: emptyArray()
            assertTrue("Temporary .part files must be cleaned up", partFiles.isEmpty())

            // Verify no partial mp4 remains
            val mp4Files = tempDir.listFiles { _, name -> name.endsWith(".mp4") } ?: emptyArray()
            assertTrue("No invalid output video should remain on merge failure", mp4Files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun download_audioOnly_usesSeparateSourceFileAndFailsOnFfmpegError() = kotlinx.coroutines.runBlocking {
        val fakeSession = FakePlatformHttpSession(downloadSuccess = true)
        val customEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "threads_audio_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val audioOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "audio_only",
                label = "僅音訊",
                formatSelector = "https://threads.net/video.mp4",
                isAudioOnly = true
            )
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@user/post/DdZaudio",
                title = "Audio Post",
                qualityOption = audioOption
            )

            val result = customEngine.download(request, tempDir, { _, _, _ -> }, {})

            assertTrue("FFmpeg extraction failure must result in failure", result.isFailure)
            assertTrue("Must have downloaded stream to temp source", fakeSession.downloadedDestinations.isNotEmpty())
            val downloadedSource = fakeSession.downloadedDestinations.first()
            assertTrue("Source file MUST use .source.mp4 naming", downloadedSource.name.endsWith(".source.mp4"))
            assertFalse("Source temporary file must be cleaned up", downloadedSource.exists())

            val mp3Files = tempDir.listFiles { _, name -> name.endsWith(".mp3") } ?: emptyArray()
            assertTrue("No invalid .mp3 files should remain after FFmpeg failure", mp3Files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildQualityOptions_mapsResolutionTiersToExactRenditionUrls() = kotlinx.coroutines.runBlocking {
        val html = loadFixture("target_post_video_versions.html")
        val fakeSession = FakePlatformHttpSession(browserHtml = html)
        val customEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.threads.com/@test_user/post/DdZtargetPost")
        assertTrue(result.isSuccess)
        val options = result.getOrNull()?.qualityOptions ?: emptyList()

        val bestOption = options.find { it.id == "best" }
        assertNotNull(bestOption)
        assertEquals("https://threads.net/cdn/video_1080.mp4", bestOption?.formatSelector)

        val opt1080 = options.find { it.id == "1080p" }
        assertNotNull(opt1080)
        assertEquals("https://threads.net/cdn/video_1080.mp4", opt1080?.formatSelector)

        val opt720 = options.find { it.id == "720p" }
        assertNotNull(opt720)
        assertEquals("https://threads.net/cdn/video_720.mp4", opt720?.formatSelector)

        // Fixture does not have 480p or 360p; they MUST NOT be displayed
        val opt480 = options.find { it.id == "480p" }
        val opt360 = options.find { it.id == "360p" }
        assertEquals(null, opt480)
        assertEquals(null, opt360)
    }

    @Test
    fun parseThreadsPage_escapedJsonPayload_extractsSuccessfully() {
        val html = loadFixture("escaped_json_payload.html")
        val shortcode = "EscapedPostCode"
        val canonical = "https://www.threads.com/@escaped_author/post/$shortcode"

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for escaped JSON payload", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("escaped_author", media.uploader)
        assertEquals("Video from escaped JSON", media.title)
        assertTrue(media.progressiveVideoUrls.isNotEmpty())
        assertEquals("https://threads.net/cdn/escaped_1080.mp4?sig=abc+123&stkn=tok+456", media.progressiveVideoUrls.first())
    }

    @Test
    fun parseDashManifest_multipleVideoRepresentations_selectsHighestResolutionAndPreservesLiterals() {
        val manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4" contentType="video">
                  <Representation id="v480" width="480" height="854" bandwidth="1000000">
                    <BaseURL>https://threads.net/cdn/v480.mp4?sig=low+res&amp;stkn=tok+1</BaseURL>
                  </Representation>
                  <Representation id="v1080" width="1080" height="1920" bandwidth="4500000">
                    <BaseURL>https://threads.net/cdn/v1080.mp4?sig=high+res%2Bopt&amp;stkn=tok+2</BaseURL>
                  </Representation>
                  <Representation id="v720" width="720" height="1280" bandwidth="2200000">
                    <BaseURL>https://threads.net/cdn/v720.mp4?sig=med+res&amp;stkn=tok+3</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" contentType="audio">
                  <Representation id="a64" bandwidth="64000">
                    <BaseURL>https://threads.net/cdn/a64.mp4?token=low</BaseURL>
                  </Representation>
                  <Representation id="a128" bandwidth="128000">
                    <BaseURL>https://threads.net/cdn/a128.mp4?token=high+qual&amp;stkn=tok+4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val (videoUrl, audioUrl) = engine.parseDashManifest(manifest)
        assertEquals(
            "Expected 1080p stream with preserved literal '+' and unescaped '&amp;'",
            "https://threads.net/cdn/v1080.mp4?sig=high+res+opt&stkn=tok+2",
            videoUrl
        )
        assertEquals(
            "Expected 128k audio stream with preserved literal '+' and unescaped '&amp;'",
            "https://threads.net/cdn/a128.mp4?token=high+qual&stkn=tok+4",
            audioUrl
        )
    }

    @Test
    fun normalizeCdnUrl_preservesLiteralPlusInQueryTokens() {
        val raw = "https:\\/\\/video.fbcdn.net\\/v.mp4?token=abc+def%2B123&amp;stkn=tok+xyz\\u0026name=%E6%B8%AC%E8%A9%A6"
        val normalized = NativeThreadsEngine.normalizeCdnUrl(raw)
        assertEquals("https://video.fbcdn.net/v.mp4?token=abc+def+123&stkn=tok+xyz&name=測試", normalized)
    }

    @Test
    fun download_dashVideoNetworkFailure_returnsNetworkErrorNotInterrupted() = kotlinx.coroutines.runBlocking {
        val testSession = object : PlatformHttpSession() {
            override fun downloadMediaStream(
                streamUrl: String,
                destination: java.io.File,
                referer: String?,
                origin: String?,
                onProgress: (Float, Long?, String?) -> Unit,
                isCancelled: () -> Boolean
            ): Boolean {
                // Simulate HTTP/CDN network failure
                return false
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = testSession)
        val tempDir = java.io.File.createTempFile("threads_test", "").apply { delete(); mkdirs() }

        try {
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@u/post/123",
                title = "Test Post",
                qualityOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                    id = "best",
                    label = "最佳畫質",
                    formatSelector = "https://cdn.threads.net/v.mp4|https://cdn.threads.net/a.mp4"
                )
            )

            val result = testEngine.download(request, tempDir, { _, _, _ -> }, {})

            assertTrue("Download must fail", result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue(
                "Network failure must return PlatformExtractionError.NetworkError, not InterruptedException; found: $error",
                error is com.charleswoo1.videodownloader.data.download.PlatformExtractionError.NetworkError
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun download_dashVideoCancelled_returnsInterruptedException() = kotlinx.coroutines.runBlocking {
        lateinit var testEngineRef: NativeThreadsEngine
        val testSession = object : PlatformHttpSession() {
            override fun downloadMediaStream(
                streamUrl: String,
                destination: java.io.File,
                referer: String?,
                origin: String?,
                onProgress: (Float, Long?, String?) -> Unit,
                isCancelled: () -> Boolean
            ): Boolean {
                // Simulate user cancellation triggered mid-stream
                testEngineRef.cancelDownload()
                return false
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = testSession)
        testEngineRef = testEngine

        val tempDir = java.io.File.createTempFile("threads_test_cancel", "").apply { delete(); mkdirs() }

        try {
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@u/post/123",
                title = "Test Post",
                qualityOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                    id = "best",
                    label = "最佳畫質",
                    formatSelector = "https://cdn.threads.net/v.mp4|https://cdn.threads.net/a.mp4"
                )
            )

            val result = testEngine.download(request, tempDir, { _, _, _ -> }, {})

            assertTrue("Download must fail", result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue(
                "Cancellation must return InterruptedException; found: $error",
                error is InterruptedException
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
