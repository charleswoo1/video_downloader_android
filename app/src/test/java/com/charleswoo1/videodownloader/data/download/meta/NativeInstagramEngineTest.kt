package com.charleswoo1.videodownloader.data.download.meta

import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
    fun normalizeCdnUrl_handlesEscapedSlashesUnicodeAndPercentEncoding() {
        val raw = "https:\\/\\/scontent.cdninstagram.com\\/o1\\/v\\/t2\\/f2\\/m\\/test?bytestart=0\\u00253D100%26token=abc"
        val normalized = NativeInstagramEngine.normalizeCdnUrl(raw)
        assertEquals("https://scontent.cdninstagram.com/o1/v/t2/f2/m/test?bytestart=0=100&token=abc", normalized)
    }

    @Test
    fun isMetaVideoUrl_acceptsValidCdnPathsWithoutMp4Suffix() {
        assertTrue(NativeInstagramEngine.isMetaVideoUrl("https://scontent.cdninstagram.com/o1/v/t2/f2/m/sample_stream"))
        assertTrue(NativeInstagramEngine.isMetaVideoUrl("https://video.fbcdn.net/v/t39.0/stream.mp4"))
        assertTrue(NativeInstagramEngine.isMetaVideoUrl("https://instagram.com/cdn/video_1080.mp4"))
        assertFalse(NativeInstagramEngine.isMetaVideoUrl("https://example.com/other.jpg"))
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
    fun parseInstagramPage_xdtApiWebInfo_extractsSuccessfullyWithEscapedNonMp4Url() {
        val html = loadFixture("xdt_api_web_info.html")
        val shortcode = "DdXfLoyTs__"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected success for xdt_api__v1__media__shortcode__web_info", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals(shortcode, media.postId)
        assertTrue("Expected non-.mp4 Meta CDN URL normalized", media.progressiveVideoUrls.first().contains("o1/v/t2/f2/m/test_stream"))
    }

    @Test
    fun parseInstagramPage_targetAbsentUnrelatedExists_failsWithTechnical() {
        val html = loadFixture("target_absent_unrelated_exists.html")
        val shortcode = "TargetCodeMissing"
        val canonical = "https://www.instagram.com/reel/$shortcode/"

        val result = engine.parseInstagramPage(html, shortcode, canonical)

        assertTrue("Expected failure when target is absent", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue(error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
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

    private class FakePlatformHttpSession(
        private val browserHtml: String = "",
        private val mobileHtml: String = "",
        private val crawlerHtml: String = "",
        private val downloadSuccess: Boolean = true
    ) : PlatformHttpSession() {
        var desktopFetchCount = 0
        var mobileFetchCount = 0
        var crawlerFetchCount = 0
        var lastDownloadedDestination: File? = null

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
            destination: File,
            referer: String?,
            origin: String?,
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
    fun extractMediaInfo_escalatesFromDesktopToMobileAndCrawlerOnTechnicalFailure() = kotlinx.coroutines.runBlocking {
        val browserHtml = loadFixture("malformed.html")
        val mobileHtml = loadFixture("malformed.html")
        val crawlerHtml = loadFixture("single_public_video.html")

        val fakeSession = FakePlatformHttpSession(browserHtml = browserHtml, mobileHtml = mobileHtml, crawlerHtml = crawlerHtml)
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected extraction to succeed after crawler retry", result.isSuccess)
        assertEquals(1, fakeSession.desktopFetchCount)
        assertEquals(1, fakeSession.mobileFetchCount)
        assertEquals(1, fakeSession.crawlerFetchCount)
        assertEquals(listOf("DESKTOP", "MOBILE", "CRAWLER"), customEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_noRetryOnAudienceRestriction() = kotlinx.coroutines.runBlocking {
        val restrictedHtml = loadFixture("audience_restricted.html")
        val fakeSession = FakePlatformHttpSession(browserHtml = restrictedHtml)
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DbtoOl8zwMO/")

        assertTrue("Expected extraction to fail on restriction", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MetaExtractionError.Restricted)
        assertEquals(1, fakeSession.desktopFetchCount)
        assertEquals(0, fakeSession.mobileFetchCount)
        assertEquals(0, fakeSession.crawlerFetchCount)
        assertEquals(listOf("DESKTOP"), customEngine.lastProfileSequence)
    }

    @Test
    fun download_audioOnly_failsGracefullyOnFfmpegError() = kotlinx.coroutines.runBlocking {
        val fakeSession = FakePlatformHttpSession(downloadSuccess = true)
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ig_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val audioOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "audio_only",
                label = "僅音訊",
                formatSelector = "audio:https://instagram.com/cdn/video_1080.mp4",
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
            val mp3Files = tempDir.listFiles { _, name -> name.endsWith(".mp3") } ?: emptyArray()
            assertTrue("No invalid .mp3 files should remain after FFmpeg failure", mp3Files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildQualityOptions_mapsResolutionTiersToExactRenditionUrls() = kotlinx.coroutines.runBlocking {
        val html = loadFixture("single_public_video.html")
        val fakeSession = FakePlatformHttpSession(browserHtml = html)
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)

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
    }

    @Test
    fun normalizeCdnUrl_preservesLiteralPlusAndDecodesEntities() {
        val raw = "https:\\/\\/scontent.cdninstagram.com\\/o1\\/v\\/t2\\/f2\\/m\\/test?token=abc+def%2B123&amp;stkn=tok+xyz\\u0026name=%E6%B8%AC%E8%A9%A6"
        val normalized = NativeInstagramEngine.normalizeCdnUrl(raw)
        assertEquals("https://scontent.cdninstagram.com/o1/v/t2/f2/m/test?token=abc+def+123&stkn=tok+xyz&name=測試", normalized)
    }

    @Test
    fun parseInstagramPage_targetAbsentXdtItem_doesNotMatchUnrelated() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "xdt_api__v1__media__shortcode__web_info": {
                "items": [
                  {
                    "code": "UnrelatedCode",
                    "id": "999999999999",
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/unrelated.mp4", "width": 1080, "height": 1920}
                    ]
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "TargetShortcode", "https://www.instagram.com/reel/TargetShortcode/")
        assertTrue("Target-absent XDT item must fail extraction", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Expected Technical error allowing fallback", error is MetaExtractionError.Technical)
    }

    @Test
    fun parseInstagramPage_schemaOrgVideoObject_unrelatedVideo_doesNotMatch() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "VideoObject",
              "name": "Unrelated Video",
              "url": "https://www.instagram.com/reel/UnrelatedShortcode/",
              "contentUrl": "https://instagram.com/cdn/unrelated.mp4"
            }
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "TargetShortcode", "https://www.instagram.com/reel/TargetShortcode/")
        assertTrue("Unrelated schema.org VideoObject must not match target post", result is MetaExtractionResult.Failure)
    }

    @Test
    fun parseInstagramPage_schemaOrgVideoObject_matchingTarget_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "VideoObject",
              "name": "Target Video",
              "embedUrl": "https://www.instagram.com/p/TargetShortcode/embed",
              "contentUrl": "https://instagram.com/cdn/target_video.mp4?stkn=tok+123"
            }
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "TargetShortcode", "https://www.instagram.com/reel/TargetShortcode/")
        assertTrue("Matching schema.org VideoObject must succeed", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("TargetShortcode", media.postId)
        assertEquals("https://instagram.com/cdn/target_video.mp4?stkn=tok+123", media.progressiveVideoUrls.first())
    }

    @Test
    fun extractMediaInfo_desktopLoginWall_escalatesToMobileAndSucceeds() = kotlinx.coroutines.runBlocking {
        val loginWallHtml = "<html><head><title>Login • Instagram</title></head><body><a href=\"/accounts/login/\">Login</a></body></html>"
        val publicFixtureHtml = loadFixture("single_public_video.html")

        val testSession = object : PlatformHttpSession() {
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
                val returnedBody = when (profile) {
                    RequestProfile.DESKTOP_NAVIGATION -> loginWallHtml
                    RequestProfile.MOBILE_NAVIGATION -> publicFixtureHtml
                    else -> ""
                }
                return Result.success(HttpResponse(200, url, returnedBody, emptyMap()))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = testSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected extraction success when mobile profile succeeds after desktop login wall", result.isSuccess)
        val mediaInfo = result.getOrNull()
        assertNotNull(mediaInfo)
        assertEquals("https://www.instagram.com/reel/DdS5sMrxkBq/", mediaInfo?.sourceUrl)
        assertEquals(listOf("DESKTOP", "MOBILE"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_allProfilesLoginWall_returnsTerminalLoginRequired() = kotlinx.coroutines.runBlocking {
        val loginWallHtml = "<html><head><title>Login • Instagram</title></head><body><a href=\"/accounts/login/\">Login</a></body></html>"

        val testSession = object : PlatformHttpSession() {
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
                return Result.success(HttpResponse(200, url, loginWallHtml, emptyMap()))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = testSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected extraction failure when all profiles hit login wall", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be MetaExtractionError.Restricted", error is MetaExtractionError.Restricted)
        val restricted = error as MetaExtractionError.Restricted
        assertEquals(RestrictionReason.LOGIN_REQUIRED, restricted.reason)
        assertFalse("Terminal login-required must not fallback", restricted.canFallback)
        assertEquals(listOf("DESKTOP", "MOBILE", "CRAWLER"), testEngine.lastProfileSequence)
    }

    @Test
    fun buildFetchUrl_preservesQueryParametersAndNormalizesPath() {
        val withStkn = "https://www.instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn"
        assertEquals(
            "https://www.instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn",
            engine.buildFetchUrl(withStkn)
        )

        val withFragmentAndParam = "https://instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn#top"
        assertEquals(
            "https://www.instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn",
            engine.buildFetchUrl(withFragmentAndParam)
        )

        val cleanUrl = "https://www.instagram.com/reel/DdXfJ2nzdtA/"
        assertEquals(
            "https://www.instagram.com/reel/DdXfJ2nzdtA/",
            engine.buildFetchUrl(cleanUrl)
        )
    }

    @Test
    fun extractMediaInfo_preservesStknQueryParameterInFetchUrl() = kotlinx.coroutines.runBlocking {
        val publicFixtureHtml = loadFixture("single_public_video.html")
        val requestedUrls = mutableListOf<String>()

        val testSession = object : PlatformHttpSession() {
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
                requestedUrls.add(url)
                return Result.success(HttpResponse(200, url, publicFixtureHtml, emptyMap()))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = testSession)
        val inputUrl = "https://www.instagram.com/reel/DdS5sMrxkBq/?stkn=ZWhiNjI5c2NqcGpn"
        val result = testEngine.extractMediaInfo(inputUrl)

        assertTrue("Expected extraction success", result.isSuccess)
        val mediaInfo = result.getOrNull()
        assertNotNull(mediaInfo)
        assertEquals(inputUrl, mediaInfo?.sourceUrl)
        assertTrue("Fetch URL must retain stkn query parameter", requestedUrls.isNotEmpty() && requestedUrls.first().contains("stkn=ZWhiNjI5c2NqcGpn"))
    }

    @Test
    fun extractMediaInfo_desktopLoginWall_mobileTechnical_returnsFallbackEligibleTechnical() = kotlinx.coroutines.runBlocking {
        val loginWallHtml = "<html><head><title>Login • Instagram</title></head><body><a href=\"/accounts/login/\">Login</a></body></html>"
        val nonLoginNoDataHtml = "<html><head><title>Instagram</title></head><body><div>Just empty container</div></body></html>"

        val testSession = object : PlatformHttpSession() {
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
                val returnedBody = when (profile) {
                    RequestProfile.DESKTOP_NAVIGATION -> loginWallHtml
                    else -> nonLoginNoDataHtml
                }
                return Result.success(HttpResponse(200, url, returnedBody, emptyMap()))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = testSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected failure when all profiles fail", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be MetaExtractionError.Technical when not all profiles are login-gated", error is MetaExtractionError.Technical)
        val technical = error as MetaExtractionError.Technical
        assertTrue("Mixed profile failure must allow fallback to yt-dlp", technical.canFallback)
    }

    @Test
    fun parseInstagramPage_nestedIfNotGatedLoggedOut_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "shortcode_media": {
                "shortcode": "DdXnested",
                "if_not_gated_logged_out": {
                  "node": {
                    "shortcode": "DdXnested",
                    "is_video": true,
                    "video_url": "https://instagram.com/cdn/nested_video.mp4",
                    "dimensions": {"height": 1080, "width": 1920},
                    "owner": {"username": "nested_creator"}
                  }
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXnested", "https://www.instagram.com/reel/DdXnested/")
        assertTrue("Expected success for if_not_gated_logged_out container", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("DdXnested", media.postId)
        assertEquals("nested_creator", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://instagram.com/cdn/nested_video.mp4"))
    }

    @Test
    fun parseInstagramPage_outerWrapperPlaceholderWithDeeperXdt_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "wrapper": {
                "shortcode": "DdXdeep",
                "placeholder": true
              },
              "xdt_api__v1__media__shortcode__web_info": {
                "items": [
                  {
                    "code": "DdXdeep",
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/deep_video.mp4", "width": 1080, "height": 1920}
                    ],
                    "user": {"username": "deep_creator"}
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXdeep", "https://www.instagram.com/reel/DdXdeep/")
        assertTrue("Expected success for deeper XDT node when outer wrapper is placeholder", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("DdXdeep", media.postId)
        assertEquals("deep_creator", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://instagram.com/cdn/deep_video.mp4"))
    }

    @Test
    fun parseInstagramPage_targetWrapperPresentWithoutMedia_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "shortcode_media": {
                "shortcode": "DdXemptyWrapper",
                "id": "12345678"
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXemptyWrapper", "https://www.instagram.com/reel/DdXemptyWrapper/")
        assertTrue("Target wrapper without media must result in Failure", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be Technical, NOT NoVideo; found: $error", error is MetaExtractionError.Technical)
        assertTrue("Technical error MUST allow fallback", error.canFallback)
    }

    @Test
    fun parseInstagramPage_resolvedPureImagePost_returnsTerminalNoVideo() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "shortcode_media": {
                "shortcode": "DdXimageOnly",
                "is_video": false,
                "display_url": "https://instagram.com/cdn/photo.jpg",
                "image_versions2": {
                  "candidates": [
                    {"url": "https://instagram.com/cdn/photo.jpg", "width": 1080, "height": 1080}
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXimageOnly", "https://www.instagram.com/p/DdXimageOnly/")
        assertTrue("Resolved pure image post must result in Failure", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Error must be NoVideo, found: $error", error is MetaExtractionError.NoVideo)
        assertFalse("Terminal NoVideo MUST NOT allow fallback", error.canFallback)
    }

    @Test
    fun parseInstagramPage_dataSjsScriptWithoutJsonType_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script data-sjs>
            requireLazy(["ScheduledServerJS"], function(sjs) {
              sjs.handle({
                "require": [
                  ["RelayPrefetchedStreamCache", "next", [], [
                    "xdt_api__v1__media__shortcode__web_info",
                    {
                      "items": [
                        {
                          "code": "DdXdatasjs",
                          "video_versions": [
                            {"url": "https://instagram.com/cdn/datasjs_video.mp4", "width": 1080, "height": 1920}
                          ],
                          "user": {"username": "sjs_user"}
                        }
                      ]
                    }
                  ]]
                ]
              });
            });
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXdatasjs", "https://www.instagram.com/reel/DdXdatasjs/")
        assertTrue("Expected success for data-sjs script block", result is MetaExtractionResult.Success)
        val media = (result as MetaExtractionResult.Success).media
        assertEquals("DdXdatasjs", media.postId)
        assertEquals("sjs_user", media.uploader)
        assertTrue(media.progressiveVideoUrls.contains("https://instagram.com/cdn/datasjs_video.mp4"))
    }

    @Test
    fun extractMediaInfo_desktopWrapperWithoutMedia_escalatesToMobileAndSucceeds() = kotlinx.coroutines.runBlocking {
        val desktopWrapperOnlyHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "shortcode_media": {
                "shortcode": "DdXescalate",
                "id": "123456"
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val mobileFullMediaHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "xdt_api__v1__media__shortcode__web_info": {
                "items": [
                  {
                    "code": "DdXescalate",
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/mobile_video.mp4", "width": 1080, "height": 1920}
                    ],
                    "user": {"username": "mobile_user"}
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val fakeSession = object : PlatformHttpSession() {
            var desktopCount = 0
            var mobileCount = 0
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
                        desktopCount++
                        Result.success(HttpResponse(200, url, desktopWrapperOnlyHtml, emptyMap()))
                    }
                    RequestProfile.MOBILE_NAVIGATION -> {
                        mobileCount++
                        Result.success(HttpResponse(200, url, mobileFullMediaHtml, emptyMap()))
                    }
                    else -> Result.failure(java.io.IOException("Unexpected profile"))
                }
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/DdXescalate/")

        assertTrue("Expected extraction to succeed after escalating to MOBILE", result.isSuccess)
        val mediaInfo = result.getOrNull()
        assertNotNull(mediaInfo)
        assertEquals("https://www.instagram.com/reel/DdXescalate/", mediaInfo?.sourceUrl)
        assertEquals(1, fakeSession.desktopCount)
        assertEquals(1, fakeSession.mobileCount)
        assertEquals(listOf("DESKTOP", "MOBILE"), testEngine.lastProfileSequence)
    }
}
