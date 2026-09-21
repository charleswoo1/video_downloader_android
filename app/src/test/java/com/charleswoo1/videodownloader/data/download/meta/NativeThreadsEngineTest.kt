package com.charleswoo1.videodownloader.data.download.meta

import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.http.AuthenticatedPlatformSessionProvider
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.InMemoryPlatformCredentialStore
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import com.charleswoo1.videodownloader.data.download.http.SessionState
import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

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

    @Test
    fun extractCanonicalFromHtml_reversedAttributeOrder_extractsSuccessfully() {
        val reversedLinkHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <link href="https://www.threads.net/@reverse_user/post/Ddreversed123" rel="canonical" />
            </head>
            <body></body>
            </html>
        """.trimIndent()
        assertEquals(
            "https://www.threads.com/@reverse_user/post/Ddreversed123",
            engine.extractCanonicalFromHtml(reversedLinkHtml)
        )

        val reversedOgHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta content="https://www.threads.net/@reverse_user/post/Ddreversed456" property="og:url" />
            </head>
            <body></body>
            </html>
        """.trimIndent()
        assertEquals(
            "https://www.threads.com/@reverse_user/post/Ddreversed456",
            engine.extractCanonicalFromHtml(reversedOgHtml)
        )

        val nameOgHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta content="https://www.threads.net/@reverse_user/post/Ddreversed789" name="og:url" />
            </head>
            <body></body>
            </html>
        """.trimIndent()
        assertEquals(
            "https://www.threads.com/@reverse_user/post/Ddreversed789",
            engine.extractCanonicalFromHtml(nameOgHtml)
        )
    }

    @Test
    fun parseThreadsPage_targetPostCodeInWrapperWithDeeperChildMedia_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "containing_wrapper": {
                  "code": "BAVLndHzC",
                  "post": {
                    "code": "BAVLndHzC",
                    "user": {"username": "deep_threads_user"},
                    "caption": {"text": "Deep threads video"},
                    "video_versions": [
                      {"url": "https://threads.net/cdn/deep_threads_video.mp4", "width": 1080, "height": 1920}
                    ]
                  }
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "BAVLndHzC", "https://www.threads.com/@deep_threads_user/post/BAVLndHzC")
        assertTrue("Expected success for post with deeper child media", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("BAVLndHzC", media.postId)
        assertEquals("deep_threads_user", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/deep_threads_video.mp4"))
    }

    @Test
    fun parseThreadsPage_targetPostWrapperPresentWithoutMedia_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "post_card": {
                  "code": "_5-t0FGYG",
                  "view_state": "placeholder",
                  "tracking_token": "xyz123"
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "_5-t0FGYG", "https://www.threads.com/@user/post/_5-t0FGYG")
        assertTrue("Wrapper without media must fail extraction", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be Technical, NOT NoVideo; found: $error", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun parseThreadsPage_imageOnlyTargetWithRecommendedVideo_returnsTerminalNoVideo() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "target_post": {
                  "code": "TargetImagePost",
                  "is_video": false,
                  "media_type": 1,
                  "image_versions2": {
                    "candidates": [
                      {"url": "https://threads.net/cdn/target_image.jpg", "width": 1080, "height": 1080}
                    ]
                  }
                },
                "recommended_posts": [
                  {
                    "code": "UnrelatedVideoPost",
                    "video_versions": [
                      {"url": "https://threads.net/cdn/unrelated_video.mp4", "width": 1080, "height": 1920}
                    ]
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "TargetImagePost", "https://www.threads.com/@user/post/TargetImagePost")
        assertTrue("Image-only target must fail extraction", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be NoVideo, NOT Technical or Success; found: $error", error is MetaExtractionError.NoVideo)
        assertFalse("Terminal NoVideo MUST NOT allow fallback", error.canFallback)
    }

    @Test
    fun parseThreadsPage_unresolvedTargetWithRecommendedVideo_returnsTechnicalAndRefusesRecommendation() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "wrapper_stub": {
                  "code": "UnresolvedTarget",
                  "placeholder": true
                },
                "feed_units": [
                  {
                    "code": "UnrelatedFeedVideo",
                    "video_versions": [
                      {"url": "https://threads.net/cdn/feed_video.mp4", "width": 1080, "height": 1920}
                    ]
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "UnresolvedTarget", "https://www.threads.com/@user/post/UnresolvedTarget")
        assertTrue("Unresolved target must fail extraction", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be Technical, NOT NoVideo and NOT Success; found: $error", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun parseThreadsPage_targetWithUserAndCaptionAndUnknownMediaContainer_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "post": {
                  "code": "DdcLVjtknJ2",
                  "user": {"username": "threads_creator"},
                  "caption": {"text": "Post with unknown media container"},
                  "unknown_custom_media_container": {
                    "raw_data": "some_unsupported_format"
                  }
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "DdcLVjtknJ2", "https://www.threads.com/@threads_creator/post/DdcLVjtknJ2")
        assertTrue("Unknown container must result in Failure", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be Technical, NOT NoVideo; found: $error", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun parseThreadsPage_canonicalCodeDdcLVjtknJ2_inContainingThreadItems_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "DdcLVjtknJ2",
                        "user": {"username": "creator_ddc"},
                        "caption": {"text": "Canonical DdcLVjtknJ2 video"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/ddc_video_1080.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "DdcLVjtknJ2", "https://www.threads.com/@creator_ddc/post/DdcLVjtknJ2")
        assertTrue("Expected success for canonical DdcLVjtknJ2 post", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("DdcLVjtknJ2", media.postId)
        assertEquals("creator_ddc", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/ddc_video_1080.mp4"))
    }

    @Test
    fun parseThreadsPage_canonicalCodeDdaQOUWkpua_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "DdaQOUWkpua",
                        "user": {"username": "creator_dda"},
                        "caption": {"text": "Canonical DdaQOUWkpua video"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/dda_video_1080.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "DdaQOUWkpua", "https://www.threads.com/@creator_dda/post/DdaQOUWkpua")
        assertTrue("Expected success for canonical DdaQOUWkpua post", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("DdaQOUWkpua", media.postId)
        assertEquals("creator_dda", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/dda_video_1080.mp4"))
    }

    @Test
    fun parseThreadsPage_nestedJsonWithSignedMediaUrl_preservesExactUrl() {
        val signedUrl = "https://threads.net/cdn/video_1080.mp4?sig=abc+123&amp;stkn=tok+456+xyz\\u0026hash=def"
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "require": [
                [
                  "RelayPrefetchedStreamCache",
                  "next",
                  [],
                  [
                    "xdt_api__v1__post__graphql:{\"data\":{\"data\":{\"containing_thread\":{\"thread_items\":[{\"post\":{\"id\":\"111\",\"code\":\"SignedUrlPost\",\"user\":{\"username\":\"signed_user\"},\"video_versions\":[{\"url\":\"$signedUrl\",\"width\":1080,\"height\":1920}]}}]}}}}"
                  ]
                ]
              ]
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "SignedUrlPost", "https://www.threads.com/@signed_user/post/SignedUrlPost")
        assertTrue("Expected success for signed URL nested JSON", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        val extractedUrl = media.progressiveVideoUrls.first()
        // Verify full normalized URL preserves literal '+' and normalizes escapes
        assertEquals("https://threads.net/cdn/video_1080.mp4?sig=abc+123&stkn=tok+456+xyz&hash=def", extractedUrl)
    }

    @Test
    fun parseThreadsPage_targetWithMediaType19AndUnknownMediaContainer_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "MediaType19Post",
                        "user": {"username": "creator_19"},
                        "caption": {"text": "Media type 19 post without explicit text/image markers"},
                        "media_type": 19,
                        "unrecognized_media_payload": {
                          "stream": "https://threads.net/cdn/unknown_video.mp4"
                        }
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, "MediaType19Post", "https://www.threads.com/@creator_19/post/MediaType19Post")
        assertTrue("Expected failure for media_type=19 with unknown media container", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Expected Technical error allowing fallback, found: $error", error is MetaExtractionError.Technical)
        assertTrue("Must be fallback-eligible", error.canFallback)
    }

    @Test
    fun parseThreadsPage_replyPostDdcJ4wwkjVQ_inReplyThreads_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "reply_threads": [
                    {
                      "thread_items": [
                        {
                          "post": {
                            "code": "DdcJ4wwkjVQ",
                            "user": {"username": "reply_user"},
                            "caption": {"text": "Reply video in reply_threads"},
                            "video_versions": [
                              {"url": "https://threads.net/cdn/reply_video.mp4", "width": 1080, "height": 1920}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseThreadsPageWithDiagnostics(html, "DdcJ4wwkjVQ", "https://www.threads.com/@reply_user/post/DdcJ4wwkjVQ")
        assertTrue("Expected success for reply post DdcJ4wwkjVQ", outcome.result is MetaExtractionResult.Success)
        val media = (outcome.result as MetaExtractionResult.Success).media
        assertEquals("DdcJ4wwkjVQ", media.postId)
        assertEquals("reply_user", media.uploader)
        assertTrue(outcome.diagnostics.targetWrapperFound)
        assertTrue(outcome.diagnostics.mediaNodeFound)
    }

    @Test
    fun parseThreadsPage_nestedMediaPostDdbxeeBjzNO_inTextPostAppInfoMedia_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "DdbxeeBjzNO",
                        "user": {"username": "nested_user"},
                        "caption": {"text": "Nested media post"},
                        "text_post_app_info": {
                          "share_info": {
                            "media": {
                              "video_versions": [
                                {"url": "https://threads.net/cdn/nested_video.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseThreadsPageWithDiagnostics(html, "DdbxeeBjzNO", "https://www.threads.com/@nested_user/post/DdbxeeBjzNO")
        assertTrue("Expected success for DdbxeeBjzNO in share_info.media", outcome.result is MetaExtractionResult.Success)
        val media = (outcome.result as MetaExtractionResult.Success).media
        assertEquals("DdbxeeBjzNO", media.postId)
        assertEquals("nested_user", media.uploader)
        assertTrue(outcome.diagnostics.targetWrapperFound)
        assertTrue(outcome.diagnostics.mediaNodeFound)
    }

    @Test
    fun parseThreadsPage_targetIsolationRejectsMismatchedPostCode() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "UnrelatedPost123",
                        "video_versions": [{"url": "https://threads.net/cdn/unrelated.mp4", "width": 720, "height": 1280}]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseThreadsPageWithDiagnostics(html, "WantedPostCode", "https://www.threads.com/@u/post/WantedPostCode")
        assertTrue("Target isolation must reject mismatched code", outcome.result is MetaExtractionResult.Failure)
        assertFalse(outcome.diagnostics.targetWrapperFound)
        assertFalse(outcome.diagnostics.mediaNodeFound)
    }

    @Test
    fun parseThreadsPage_diagnosticFingerprintContainsExpectedFields() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {"data":{"post":{"code":"DiagPost123","video_versions":[{"url":"https://threads.net/v.mp4","width":720,"height":1280}]}}}
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseThreadsPageWithDiagnostics(html, "DiagPost123", "https://www.threads.com/@u/post/DiagPost123", resolvedShare = true)
        val diag = outcome.diagnostics
        assertTrue(diag.resolvedShare)
        assertEquals(1, diag.scriptCount)
        assertTrue(diag.rawCodeInHtml)
        assertTrue(diag.decodedCodeInHtml)
        assertTrue(diag.targetWrapperFound)
        assertTrue(diag.mediaNodeFound)
    }

    @Test
    fun extractMediaInfo_failedMobileAttempt_stillAppearsBetweenDesktopAndCrawlerInFingerprint() = runBlocking {
        val fakeSession = object : PlatformHttpSession() {
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
            ): Result<PlatformHttpSession.HttpResponse> {
                return when (profile) {
                    RequestProfile.DESKTOP_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(200, url, "<html><body>no video data here</body></html>", emptyMap())
                    )
                    RequestProfile.MOBILE_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(403, url, "", emptyMap())
                    )
                    RequestProfile.CRAWLER_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(200, url, "<html><body>crawler fallback page</body></html>", emptyMap())
                    )
                    else -> Result.failure(java.io.IOException("Unsupported profile"))
                }
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@user/post/TestCode123")
        assertTrue("Expected failure when all profiles fail to extract video", result.isFailure)

        val fp = testEngine.lastDiagnosticFingerprint
        assertNotNull(fp)
        assertTrue("Fingerprint must contain DESKTOP", fp!!.contains("profile=DESKTOP"))
        assertTrue("Fingerprint must contain MOBILE", fp.contains("profile=MOBILE"))
        assertTrue("Fingerprint must contain CRAWLER", fp.contains("profile=CRAWLER"))
        assertTrue("Fingerprint must contain empty body indicator for MOBILE", fp.contains("http_status=403") && fp.contains("stage=EMPTY_BODY"))

        val desktopIdx = fp.indexOf("profile=DESKTOP")
        val mobileIdx = fp.indexOf("profile=MOBILE")
        val crawlerIdx = fp.indexOf("profile=CRAWLER")

        assertTrue("DESKTOP must precede MOBILE", desktopIdx < mobileIdx)
        assertTrue("MOBILE must precede CRAWLER", mobileIdx < crawlerIdx)
    }

    @Test
    fun shortcodeToPk_calculatesExpectedNumericPkAndRoundtrips() {
        val shortcode = "DdcJ4wwkjVQ"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        assertTrue("PK should be non-empty digits", pk.isNotBlank() && pk.all { it.isDigit() })
        val backToShortcode = NativeThreadsEngine.pkToShortcode(pk)
        assertEquals(shortcode, backToShortcode)
    }

    @Test
    fun extractMediaInfo_barcelonaGraphQL_success_targetCodeMatch() = runBlocking {
        val shortcode = "DdZtargetPost"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val graphqlJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "code": "$shortcode",
                              "pk": "$pk",
                              "user": {"username": "graphql_user"},
                              "caption": {"text": "GraphQL video caption"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/gql_video.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, graphqlJson, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"mock_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@graphql_user/post/$shortcode")

        assertTrue("Expected extraction success via Barcelona GraphQL", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("GraphQL video caption", media?.title)
        assertEquals("https://threads.net/cdn/gql_video.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(listOf("BARCELONA_GRAPHQL"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_barcelonaGraphQL_success_targetPkMatch() = runBlocking {
        val shortcode = "DdZtargetPost"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val graphqlJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "pk": "$pk",
                              "user": {"username": "pk_user"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/pk_video.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, graphqlJson, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"mock_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@pk_user/post/$shortcode")

        assertTrue("Expected extraction success via PK match", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("https://threads.net/cdn/pk_video.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(listOf("BARCELONA_GRAPHQL"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_barcelonaGraphQL_targetNotFound_unrelatedVideoExists_refusesToExtractAndFallsBackOrFails() = runBlocking {
        val targetShortcode = "DdZWantedPost"
        val unrelatedShortcode = "DdZUnrelated999"
        val unrelatedPk = "999888777"
        val graphqlJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "code": "$unrelatedShortcode",
                              "pk": "$unrelatedPk",
                              "user": {"username": "unrelated_user"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/unrelated_video.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, graphqlJson, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"mock_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@user/post/$targetShortcode")

        assertTrue("Target isolation MUST reject unrelated video from GraphQL and fail", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MetaExtractionError.Technical || error is PlatformExtractionError)
        assertFalse("Must never download unrelated video URL", result.getOrNull()?.qualityOptions?.any { it.formatSelector.contains("unrelated_video") } == true)
        assertTrue("Sequence should include BARCELONA_GRAPHQL and fallback profiles", testEngine.lastProfileSequence.contains("BARCELONA_GRAPHQL"))
    }

    @Test
    fun extractMediaInfo_barcelonaGraphQL_explicit429_returnsRateLimitedImmediately() = runBlocking {
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(429, url, "Too Many Requests", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"mock_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@user/post/TestCode429")

        assertTrue("Expected failure on HTTP 429", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be RateLimited, found $error", error is PlatformExtractionError.RateLimited)
        assertEquals(listOf("BARCELONA_GRAPHQL"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_authenticatedSession_extractsSuccessfully() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(
            Platform.THREADS,
            "sessionid=threads_session_123; csrftoken=csrftok; ds_user_id=8888"
        )
        sessionProvider.markActive(Platform.THREADS, "Validated")

        val relayHtml = loadFixture("target_post_video_versions.html")

        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                val html = if (customHeaders.containsKey("X-Anonymous-Context")) {
                    """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script></html>"""
                } else {
                    relayHtml
                }
                return Result.success(HttpResponse(200, url, html, emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@test_user/post/DdZtargetPost")

        assertTrue("Expected success via Authenticated Relay", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("Check out this Threads video", media?.title)
        assertEquals("https://threads.net/cdn/video_1080.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(listOf("BARCELONA_GRAPHQL", "AUTHENTICATED_RELAY"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_authenticatedSession_sessionExpired401_marksExpiredAndReturnsSessionExpired() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(
            Platform.THREADS,
            "sessionid=expired_threads_session; csrftoken=csrftok; ds_user_id=8888"
        )
        sessionProvider.markActive(Platform.THREADS, "Validated")

        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                if (customHeaders.containsKey("X-Anonymous-Context")) {
                    return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script></html>""", emptyMap()))
                }
                // Authenticated Relay call receives 401 Unauthorized
                return Result.success(HttpResponse(401, url, "Unauthorized", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.net/@user/post/ExpiredPost")

        assertTrue("Expected failure on 401 expired session", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be SessionExpired, found $error", error is PlatformExtractionError.SessionExpired)
        assertFalse("Session must be marked expired", sessionProvider.hasAuthenticatedSession(Platform.THREADS))
        assertEquals(SessionState.EXPIRED, sessionProvider.sessionStatus(Platform.THREADS).state)
        assertEquals(listOf("BARCELONA_GRAPHQL", "AUTHENTICATED_RELAY"), testEngine.lastProfileSequence)
    }

    @Test
    fun fetchBarcelonaGraphQL_requestCapture_verifiesBootstrapAndLsdPropagation() = runBlocking {
        val shortcode = "DdZtargetPost"
        val expectedPk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val graphqlJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "code": "$shortcode",
                              "pk": "$expectedPk",
                              "user": {"username": "graphql_user"},
                              "caption": {"text": "Captured post caption"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/gql_vid.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        data class CapturedRequest(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String?
        )

        val capturedRequests = mutableListOf<CapturedRequest>()

        val fakeSession = object : PlatformHttpSession() {
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
                val bodyStr = body?.let { String(it, Charsets.UTF_8) }
                capturedRequests.add(CapturedRequest(url, method, customHeaders, bodyStr))

                return when {
                    url.contains("/api/graphql") -> {
                        Result.success(HttpResponse(200, url, graphqlJson, emptyMap()))
                    }
                    else -> {
                        // Bootstrap page
                        Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"captured_threads_lsd_123"}]</script></html>""", mapOf("Set-Cookie" to "csrftoken=captured_threads_csrf_456; Path=/")))
                    }
                }
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected extraction to succeed", result.isSuccess)

        // 1. Verify page bootstrap GET happens before GraphQL POST
        assertEquals(2, capturedRequests.size)
        val bootstrapReq = capturedRequests[0]
        val gqlReq = capturedRequests[1]

        assertEquals("GET", bootstrapReq.method)
        assertTrue("Bootstrap request must target post URL", bootstrapReq.url.contains(shortcode))

        assertEquals("POST", gqlReq.method)
        assertEquals("https://www.threads.com/api/graphql", gqlReq.url)

        // 2. Verify headers contract
        assertEquals("captured_threads_lsd_123", gqlReq.headers["X-FB-LSD"])
        assertEquals("captured_threads_csrf_456", gqlReq.headers["X-CSRFToken"])
        assertEquals("BarcelonaPostPageContentQuery", gqlReq.headers["X-FB-Friendly-Name"])
        assertEquals(NativeThreadsEngine.THREADS_APP_ID, gqlReq.headers["X-IG-App-ID"])
        assertEquals("https://www.threads.com", gqlReq.headers["Origin"])
        assertEquals("https://www.threads.com/@user/post/$shortcode", gqlReq.headers["Referer"])
        assertEquals("same-origin", gqlReq.headers["Sec-Fetch-Site"])
        assertEquals("cors", gqlReq.headers["Sec-Fetch-Mode"])

        // 3. Verify form body contract
        val bodyStr = gqlReq.body
        assertNotNull(bodyStr)
        assertTrue("Form body must contain lsd token", bodyStr!!.contains("lsd=captured_threads_lsd_123"))
        assertTrue("Form body must contain doc_id", bodyStr.contains("doc_id=${NativeThreadsEngine.BARCELONA_DOC_ID}"))
        assertTrue("Form body must contain fb_api_req_friendly_name", bodyStr.contains("fb_api_req_friendly_name=BarcelonaPostPageContentQuery"))
        assertTrue("Form body must contain RelayModern", bodyStr.contains("fb_api_caller_class=RelayModern"))

        // Verify postID in variables
        val decodedBody = java.net.URLDecoder.decode(bodyStr, "UTF-8")
        assertTrue("Variables must contain postID: $expectedPk", decodedBody.contains(""""postID":"$expectedPk""""))
    }

    @Test
    fun fetchBarcelonaGraphQL_antiJsonPrefix_parsesSuccessfully() = runBlocking {
        val shortcode = "DdZantiJson"
        val expectedPk = NativeThreadsEngine.shortcodeToPk(shortcode)

        val rawJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "id": "$expectedPk",
                              "code": "$shortcode",
                              "caption": {"text": "Anti-JSON test video"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/antijson_1080.mp4", "width": 1080, "height": 1920}
                              ],
                              "user": {"username": "antijson_tester"}
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        // Real Threads response prepends for (;;); to prevent JSON hijacking
        val antiJsonBody = "for (;;);$rawJson"

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, antiJsonBody, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"real_lsd_token"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.fetchBarcelonaGraphQL(shortcode, expectedPk, "https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected parsing with anti-JSON prefix to succeed", result.isSuccess)
        val media = result.getOrNull()
        assertEquals("https://threads.net/cdn/antijson_1080.mp4", media?.progressiveVideoUrls?.first())
        assertEquals("antijson_tester", media?.uploader)
    }

    @Test
    fun fetchBarcelonaGraphQL_activeSession_isolatesAnonymousCookiesAndDoesNotSendSessionId() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(
            Platform.THREADS,
            "sessionid=authenticated_threads_session_999; csrftoken=auth_csrf"
        )
        sessionProvider.markActive(Platform.THREADS, "Validated")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val capturedGqlHeaders = mutableListOf<Map<String, String>>()
        val shortcode = "DdZisolateTest"
        val expectedPk = NativeThreadsEngine.shortcodeToPk(shortcode)

        val graphqlJson = """
            {"data":{"data":{"edges":[{"node":{"thread_items":[{"post":{"id":"$expectedPk","code":"$shortcode","video_versions":[{"url":"https://threads.net/cdn/v.mp4","width":720,"height":1280}],"user":{"username":"u"}}}]}}]}}}
        """.trimIndent()

        val mockSession = PlatformHttpSession(
            sessionProvider = sessionProvider,
            customClientBuilder = {
                addInterceptor { chain ->
                    val request = chain.request()
                    val urlStr = request.url.toString()
                    if (urlStr.contains("/api/graphql")) {
                        val headerMap = request.headers.names().associateWith { request.header(it) ?: "" }
                        capturedGqlHeaders.add(headerMap)
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(graphqlJson.toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        // Bootstrap page setting an anonymous cookie
                        okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .header("Set-Cookie", "csrftoken=anonymous_csrf_tok; Path=/")
                            .message("OK")
                            .body("""<html><script>["LSD",[],{"token":"anonymous_lsd_tok"}]</script></html>""".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                }
            }
        )

        // Seed authenticated session cookie into mockSession's main cookieJar (simulating previous authenticated relay)
        mockSession.syncSessionCookies(Platform.THREADS)

        val testEngine = NativeThreadsEngine(context = null, httpSession = mockSession)
        val result = testEngine.fetchBarcelonaGraphQL(shortcode, expectedPk, "https://www.threads.com/@u/post/$shortcode")

        assertTrue("Extraction via isolated anonymous context should succeed", result.isSuccess)
        assertEquals(1, capturedGqlHeaders.size)
        val headers = capturedGqlHeaders[0]
        val cookieHeader = headers["Cookie"] ?: ""
        assertFalse("Anonymous Barcelona GraphQL MUST NOT send authenticated sessionid cookie: $cookieHeader", cookieHeader.contains("authenticated_threads_session_999"))
        assertEquals("anonymous_lsd_tok", headers["X-FB-LSD"])
        assertEquals("anonymous_csrf_tok", headers["X-CSRFToken"])
    }

    @Test
    fun fetchBarcelonaGraphQL_wrongTarget_rejectsUnrelatedPost() = runBlocking {
        val shortcode = "DdZwantedPost"
        val expectedPk = NativeThreadsEngine.shortcodeToPk(shortcode)

        val unrelatedJson = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "id": "999999999999",
                              "code": "DdZunrelatedPost",
                              "video_versions": [
                                {"url": "https://threads.net/cdn/unrelated.mp4", "width": 1080,"height": 1920}
                              ],
                              "user": {"username": "wrong_user"}
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, unrelatedJson, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"real_lsd_token"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.fetchBarcelonaGraphQL(shortcode, expectedPk, "https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected failure when GraphQL returns unrelated post", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be TargetNotInPageData; found $error", error is PlatformExtractionError.TargetNotInPageData)
    }

    @Test
    fun fetchBarcelonaGraphQL_missingLsd_failsAndDoesNotSendEmptyLsd() = runBlocking {
        val shortcode = "DdZmissingLsd"
        val capturedGqlRequests = mutableListOf<String>()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    capturedGqlRequests.add(url)
                    return Result.failure(java.io.IOException("Should not be called"))
                }
                // HTML without LSD token
                return Result.success(HttpResponse(200, url, "<html><body>No LSD token here</body></html>", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.fetchBarcelonaGraphQL(shortcode, NativeThreadsEngine.shortcodeToPk(shortcode), "https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected failure when LSD token is missing", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be TargetNotInPageData indicating LSD missing; found: $error", error is PlatformExtractionError.TargetNotInPageData)
        assertTrue("GraphQL request MUST NOT be sent with empty LSD", capturedGqlRequests.isEmpty())
    }

    @Test
    fun extractMediaInfo_activeSession_publicSuccess_neverCallsAuthenticatedRelay() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZPublicPost"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val gqlPayload = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "id": "$pk",
                              "code": "$shortcode",
                              "video_versions": [
                                {"url": "https://threads.net/cdn/video_anon.mp4", "width": 1080, "height": 1920}
                              ],
                              "user": {"username": "anon_user"}
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val capturedUrls = mutableListOf<String>()
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                capturedUrls.add(url)
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, gqlPayload, emptyMap()))
                }
                // Target page bootstrap
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_123"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@anon_user/post/$shortcode")

        assertTrue("Anonymous-first extraction should succeed", result.isSuccess)
        val steps = testEngine.lastProfileSequence
        assertEquals(listOf("BARCELONA_GRAPHQL"), steps)
        assertFalse("AUTHENTICATED_RELAY must NOT be called when anonymous succeeds", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_activeSession_barcelonaFails_fallsBackToAuthenticatedRelay() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZAuthPost"
        val authHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "auth_user"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/auth_video.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            <script>["LSD",[],{"token":"tok_valid_lsd"}]</script>
            </body></html>
        """.trimIndent()

        val capturedCalls = mutableListOf<String>()
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                capturedCalls.add(url)
                if (url.contains("/api/graphql")) {
                    // Reference-backed unavailable/restricted GraphQL response triggers AUTHENTICATED_RELAY
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                // Target page (bootstrap or authenticated relay)
                return Result.success(HttpResponse(200, url, authHtml, emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@auth_user/post/$shortcode")

        assertTrue("Authenticated relay fallback should succeed", result.isSuccess)
        val steps = testEngine.lastProfileSequence
        assertEquals(listOf("BARCELONA_GRAPHQL", "AUTHENTICATED_RELAY"), steps)
    }

    @Test
    fun extractMediaInfo_activeSession_lsdBootstrapFailure_authenticatedRelayNotCalled() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdLsdFailPost"
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                // Return page without LSD token
                return Result.success(HttpResponse(200, url, "<html><body>Missing LSD token</body></html>", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@anon_user/post/$shortcode")

        assertTrue("Should fail without target media", result.isFailure)
        val steps = testEngine.lastProfileSequence
        assertFalse("AUTHENTICATED_RELAY MUST NOT be called on LSD bootstrap failure", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_activeSession_malformedGraphQLJson_authenticatedRelayNotCalled() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdBadJsonPost"
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    // Malformed JSON response
                    return Result.success(HttpResponse(200, url, "invalid json payload {", emptyMap()))
                }
                // Target page bootstrap returns valid LSD
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_valid_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@anon_user/post/$shortcode")

        assertTrue("Should fail without target media", result.isFailure)
        val steps = testEngine.lastProfileSequence
        assertFalse("AUTHENTICATED_RELAY MUST NOT be called on malformed GraphQL JSON", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_activeSession_emptyEdgesNonNullData_authenticatedRelayCalled() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZEmptyEdges"
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":{"data":{"edges":[]}}}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@anon_user/post/$shortcode")

        assertTrue("Should fail without target media", result.isFailure)
        val steps = testEngine.lastProfileSequence
        assertTrue("AUTHENTICATED_RELAY MUST be called on non-null data with empty edges when active session exists", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_activeSession_targetNotFound_authenticatedRelaySucceeds() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdhP9fiD1aG"
        val unrelatedShortcode = "DdOtherCode"
        val unrelatedGql = """
            {
              "data": {
                "data": {
                  "edges": [
                    {
                      "node": {
                        "thread_items": [
                          {
                            "post": {
                              "code": "$unrelatedShortcode",
                              "pk": "99999999",
                              "video_versions": [
                                {"url": "https://threads.net/cdn/other_vid.mp4", "width": 1080, "height": 1920}
                              ],
                              "user": {"username": "other_user"}
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val authHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "target_user"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/target_video.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            <script>["LSD",[],{"token":"tok_valid_lsd"}]</script>
            </body></html>
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, unrelatedGql, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, authHtml, emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@target_user/post/$shortcode")

        assertTrue("Expected extraction to succeed via AUTHENTICATED_RELAY when GraphQL had BARCELONA_TARGET_NOT_FOUND", result.isSuccess)
        val steps = testEngine.lastProfileSequence
        assertTrue("AUTHENTICATED_RELAY MUST be called when active session exists and GraphQL target not found", steps.contains("AUTHENTICATED_RELAY"))
        assertEquals(listOf("BARCELONA_GRAPHQL", "AUTHENTICATED_RELAY"), steps)
        val media = result.getOrNull()
        assertEquals("https://threads.net/cdn/target_video.mp4", media?.qualityOptions?.first()?.formatSelector)
    }

    @Test
    fun extractMediaInfo_noSession_targetNotFound_authenticatedRelayNotCalled() = runBlocking {
        val shortcode = "DdZTargetMissingNoSession"
        val unrelatedGql = """
            {
              "data": {
                "data": {
                  "edges": []
                }
              }
            }
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, unrelatedGql, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script><body>No target</body></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Should fail when no active session and target missing", result.isFailure)
        val steps = testEngine.lastProfileSequence
        assertFalse("AUTHENTICATED_RELAY MUST NOT be called without an active session", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_activeSession_topLevelError_authenticatedRelayNotCalled() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZErrorPost"
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"error":{"message":"API Error occurred"}}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Should fail on GraphQL error", result.isFailure)
        val steps = testEngine.lastProfileSequence
        assertFalse("AUTHENTICATED_RELAY MUST NOT be called on top-level GraphQL error", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun extractMediaInfo_noSession_nullDataWithErrors_allFallbacksFail_retainsUnavailableOrRestrictedGuidance() = runBlocking {
        val shortcode = "DdZUnavailablePost"
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                // Return generic HTML that does not contain target post
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script><body>Feed without target</body></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected failure for unavailable post", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be MetaExtractionError.Restricted, found $error", error is MetaExtractionError.Restricted)
        val restrictedErr = error as MetaExtractionError.Restricted
        assertEquals(RestrictionReason.DELETED_OR_PRIVATE, restrictedErr.reason)
        assertEquals("THREADS_UNAVAILABLE_OR_RESTRICTED", restrictedErr.internalReason)
        assertFalse("Restricted error must not allow secondary fallback", restrictedErr.canFallback)
        assertTrue("Error message must contain session guidance", restrictedErr.message?.contains("Threads") == true && restrictedErr.message?.contains("Session") == true)
    }

    @Test
    fun extractMediaInfo_noSession_nullDataWithErrors_anonymousHtmlSucceeds_extractionSucceeds() = runBlocking {
        val shortcode = "DdZPublicTarget"
        val targetHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "public_user"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/public_video.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            <script>["LSD",[],{"token":"tok_valid_lsd"}]</script>
            </body></html>
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    // GraphQL returns unavailable
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                // Target page succeeds
                return Result.success(HttpResponse(200, url, targetHtml, emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@public_user/post/$shortcode")

        assertTrue("Expected extraction to succeed via HTML fallback", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("https://threads.net/cdn/public_video.mp4", media?.qualityOptions?.first()?.formatSelector)
    }

    @Test
    fun extractMediaInfo_noSession_nonNullDataTargetMissing_allFallbacksFail_returnsTargetNotFoundNotRestricted() = runBlocking {
        val shortcode = "DdZMissingTarget"
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":{"data":{"edges":[]}}}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script><body>Feed without target</body></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Should fail", result.isFailure)
        val error = result.exceptionOrNull()
        // Must NOT falsely return Restricted / LoginRequired when data was non-null
        assertTrue("Error should be Technical/ParseError, not Restricted; found $error", error is MetaExtractionError.Technical)
        assertFalse("Must not claim login is required", error?.message?.contains("需要登入") == true && error is MetaExtractionError.Restricted)
    }

    @Test
    fun fetchBarcelonaGraphQL_topLevelError_returnsApiErrorWithNonAuthReason() = runBlocking {
        val shortcode = "DdZApiErr"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"error":{"message":"Internal error"}}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_valid_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val res = testEngine.fetchBarcelonaGraphQL(shortcode, pk, "https://www.threads.com/@u/post/$shortcode")

        assertTrue(res.isFailure)
        val err = res.exceptionOrNull()
        assertTrue(err is PlatformExtractionError.ApiError)
        assertEquals("BARCELONA_API_ERROR", (err as PlatformExtractionError.ApiError).internalReason)
    }

    @Test
    fun fetchBarcelonaGraphQL_nullDataWithErrors_returnsAuthFallbackEligible() = runBlocking {
        val shortcode = "DdZNullData"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null,"errors":[{"message":"Post unavailable"}]}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_valid_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val res = testEngine.fetchBarcelonaGraphQL(shortcode, pk, "https://www.threads.com/@u/post/$shortcode")

        assertTrue(res.isFailure)
        val err = res.exceptionOrNull()
        assertTrue(err is PlatformExtractionError.TargetNotInPageData)
        assertEquals("THREADS_AUTH_FALLBACK_ELIGIBLE", (err as PlatformExtractionError.TargetNotInPageData).internalReason)
    }

    @Test
    fun fetchBarcelonaGraphQL_nonNullDataTargetMissing_returnsTargetNotFound() = runBlocking {
        val shortcode = "DdZTargetMissing"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":{"data":{"edges":[]}}}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_valid_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val res = testEngine.fetchBarcelonaGraphQL(shortcode, pk, "https://www.threads.com/@u/post/$shortcode")

        assertTrue(res.isFailure)
        val err = res.exceptionOrNull()
        assertTrue(err is PlatformExtractionError.TargetNotInPageData)
        assertEquals("BARCELONA_TARGET_NOT_FOUND", (err as PlatformExtractionError.TargetNotInPageData).internalReason)
    }

    @Test
    fun extractMediaInfo_activeSession_nullDataWithoutErrors_fallsBackToAuthenticatedRelay() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZNullNoErrors"
        val authHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "auth_user"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/auth_video.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            <script>["LSD",[],{"token":"tok_valid_lsd"}]</script>
            </body></html>
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    // Reference-backed unavailable response: data is null without errors
                    return Result.success(HttpResponse(200, url, """{"data":null}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, authHtml, emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@auth_user/post/$shortcode")

        assertTrue("Authenticated relay fallback should succeed when data is null without errors", result.isSuccess)
        val steps = testEngine.lastProfileSequence
        assertEquals(listOf("BARCELONA_GRAPHQL", "AUTHENTICATED_RELAY"), steps)
    }

    @Test
    fun extractMediaInfo_noSession_nullDataWithoutErrors_allFallbacksFail_retainsUnavailableOrRestrictedGuidance() = runBlocking {
        val shortcode = "DdZUnavailableNoErrors"
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null}""", emptyMap()))
                }
                // Return generic HTML that does not contain target post
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script><body>Feed without target</body></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Expected failure for unavailable post", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be MetaExtractionError.Restricted, found $error", error is MetaExtractionError.Restricted)
        val restrictedErr = error as MetaExtractionError.Restricted
        assertEquals(RestrictionReason.DELETED_OR_PRIVATE, restrictedErr.reason)
        assertEquals("THREADS_UNAVAILABLE_OR_RESTRICTED", restrictedErr.internalReason)
        assertFalse("Restricted error must not allow secondary fallback", restrictedErr.canFallback)
        assertTrue("Error message must contain session guidance", restrictedErr.message?.contains("Threads") == true && restrictedErr.message?.contains("Session") == true)
    }

    @Test
    fun fetchBarcelonaGraphQL_nullDataWithoutErrors_returnsAuthFallbackEligible() = runBlocking {
        val shortcode = "DdZNullDataNoErrors"
        val pk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val fakeSession = object : PlatformHttpSession() {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":null}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_valid_lsd"}]""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val res = testEngine.fetchBarcelonaGraphQL(shortcode, pk, "https://www.threads.com/@u/post/$shortcode")

        assertTrue(res.isFailure)
        val err = res.exceptionOrNull()
        assertTrue(err is PlatformExtractionError.TargetNotInPageData)
        val targetErr = err as PlatformExtractionError.TargetNotInPageData
        assertEquals("THREADS_AUTH_FALLBACK_ELIGIBLE", targetErr.internalReason)
        assertTrue("Detail should include 'no details' when errors is absent", targetErr.detail.contains("no details"))
    }

    @Test
    fun extractMediaInfo_activeSession_http429_authenticatedRelayNotCalled() = runBlocking {
        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.THREADS, "sessionid=active_threads_sess")
        sessionProvider.markActive(Platform.THREADS, "Test active")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.THREADS))

        val shortcode = "DdZ429Post"
        val fakeSession = object : PlatformHttpSession(sessionProvider = sessionProvider) {
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
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(429, url, "Rate limited", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, """<html><script>["LSD",[],{"token":"tok_valid_lsd"}]</script></html>""", emptyMap()))
            }
        }

        val testEngine = NativeThreadsEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.threads.com/@user/post/$shortcode")

        assertTrue("Should fail on HTTP 429", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Error should be RateLimited", err is PlatformExtractionError.RateLimited)
        val steps = testEngine.lastProfileSequence
        assertFalse("AUTHENTICATED_RELAY MUST NOT be called on HTTP 429", steps.contains("AUTHENTICATED_RELAY"))
    }

    @Test
    fun parseThreadsPage_targetMatchesByPkOnly_extractsSuccessfully() {
        val shortcode = "DdhP9fiD1aG"
        val targetPk = NativeThreadsEngine.shortcodeToPk(shortcode)
        val canonical = "https://www.threads.com/@pk_user/post/$shortcode"

        // Embedded payload has NO "code", but has "pk" matching targetPk
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "pk": "$targetPk",
                        "user": {"username": "pk_user"},
                        "caption": {"text": "Post matched strictly by numeric pk"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/pk_video_1080.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success when target post matches by pk only", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertEquals("pk_user", media.uploader)
        assertEquals("Post matched strictly by numeric pk", media.title)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/pk_video_1080.mp4"))
    }

    @Test
    fun parseThreadsPage_repostedPostWithVideoVersions_extractsSuccessfully() {
        val shortcode = "DdRepostedPost"
        val canonical = "https://www.threads.com/@reposter/post/$shortcode"

        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "reposter"},
                        "caption": {"text": "Check out this reposted video"},
                        "text_post_app_info": {
                          "share_info": {
                            "reposted_post": {
                              "user": {"username": "original_author"},
                              "video_versions": [
                                {"url": "https://threads.net/cdn/reposted_vid_1080.mp4", "width": 1080, "height": 1920}
                              ]
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for reposted_post structure", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertTrue(media.progressiveVideoUrls.contains("https://threads.net/cdn/reposted_vid_1080.mp4"))
    }

    @Test
    fun parseThreadsPage_repostedPostWithDash_extractsSuccessfully() {
        val shortcode = "DdRepostedDash"
        val canonical = "https://www.threads.com/@dash_reposter/post/$shortcode"

        val dashManifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4" contentType="video">
                  <Representation id="v1" width="1080" height="1920" bandwidth="3000000">
                    <BaseURL>https://threads.net/cdn/reposted_dash_v.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" contentType="audio">
                  <Representation id="a1" bandwidth="128000">
                    <BaseURL>https://threads.net/cdn/reposted_dash_a.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$shortcode",
                        "user": {"username": "dash_reposter"},
                        "text_post_app_info": {
                          "share_info": {
                            "reposted_post": {
                              "video_dash_manifest": ${org.json.JSONObject.quote(dashManifest)}
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, shortcode, canonical)

        assertTrue("Expected success for reposted_post with DASH manifest", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("https://threads.net/cdn/reposted_dash_v.mp4", media.dashVideoUrl)
        assertEquals("https://threads.net/cdn/reposted_dash_a.mp4", media.dashAudioUrl)
    }

    @Test
    fun parseThreadsPage_strictTargetIsolation_unrelatedPkAndCode_refusesExtraction() {
        val targetShortcode = "DdTargetWanted"
        val unrelatedShortcode = "DdUnrelatedOther"
        val unrelatedPk = "8888888888"
        val canonical = "https://www.threads.com/@user/post/$targetShortcode"

        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "containing_thread": {
                  "thread_items": [
                    {
                      "post": {
                        "code": "$unrelatedShortcode",
                        "pk": "$unrelatedPk",
                        "user": {"username": "unrelated_user"},
                        "video_versions": [
                          {"url": "https://threads.net/cdn/unrelated_vid.mp4", "width": 1080, "height": 1920}
                        ]
                      }
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val result = engine.parseThreadsPage(html, targetShortcode, canonical)

        assertTrue("Target isolation must refuse to extract unrelated video", result is MetaExtractionResult.Failure)
        val err = (result as MetaExtractionResult.Failure).error
        assertTrue("Error should be Technical/ParseError indicating target post not found", err is MetaExtractionError.Technical)
    }
}

