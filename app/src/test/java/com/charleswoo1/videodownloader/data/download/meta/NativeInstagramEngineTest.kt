package com.charleswoo1.videodownloader.data.download.meta

import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.http.AuthenticatedPlatformSessionProvider
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.InMemoryPlatformCredentialStore
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import com.charleswoo1.videodownloader.domain.model.Platform
import kotlinx.coroutines.runBlocking
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
        private val polarisJson: String? = null,
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
                    if (url == "https://www.instagram.com/" || url == "https://www.instagram.com") {
                        Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"mock_lsd"}</script></html>""", mapOf("Set-Cookie" to "csrftoken=mock_csrf; Path=/")))
                    } else {
                        desktopFetchCount++
                        Result.success(HttpResponse(200, url, if (browserHtml.isNotBlank()) browserHtml else """<html><script>["LSD",[],{"token":"mock_lsd"}]</script></html>""", emptyMap()))
                    }
                }
                RequestProfile.MOBILE_NAVIGATION -> {
                    mobileFetchCount++
                    Result.success(HttpResponse(200, url, if (mobileHtml.isNotBlank()) mobileHtml else browserHtml, emptyMap()))
                }
                RequestProfile.CRAWLER_NAVIGATION -> {
                    crawlerFetchCount++
                    Result.success(HttpResponse(200, url, if (crawlerHtml.isNotBlank()) crawlerHtml else browserHtml, emptyMap()))
                }
                RequestProfile.API -> {
                    if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                        Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                    } else if (url.contains("/api/graphql")) {
                        if (polarisJson != null) {
                            Result.success(HttpResponse(200, url, polarisJson, emptyMap()))
                        } else {
                            Result.failure(java.io.IOException("Polaris not mocked"))
                        }
                    } else if (polarisJson != null) {
                        Result.success(HttpResponse(200, url, """["LSD",[],{"token":"mock_lsd"}]""", emptyMap()))
                    } else {
                        Result.failure(java.io.IOException("Polaris not mocked"))
                    }
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
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER"), customEngine.lastProfileSequence)
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
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP"), customEngine.lastProfileSequence)
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
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE"), testEngine.lastProfileSequence)
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
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER"), testEngine.lastProfileSequence)
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
                if (profile != RequestProfile.API) {
                    if (url.contains("DdS5sMrxkBq")) {
                        requestedUrls.add(url)
                    }
                    return Result.success(HttpResponse(200, url, publicFixtureHtml, emptyMap()))
                }
                return Result.failure(java.io.IOException("Polaris not mocked"))
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
                        if (url == "https://www.instagram.com/" || url == "https://www.instagram.com") {
                            Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"mock_lsd"}</script></html>""", emptyMap()))
                        } else {
                            desktopCount++
                            Result.success(HttpResponse(200, url, desktopWrapperOnlyHtml, emptyMap()))
                        }
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
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE"), testEngine.lastProfileSequence)
    }

    @Test
    fun parseInstagramPage_targetWithImageVersions2AndUnknownMediaContainer_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "xdt_shortcode_media": {
                  "shortcode": "DdXunknownContainer",
                  "id": "999888777",
                  "owner": {"username": "creator_reel"},
                  "image_versions2": {
                    "candidates": [
                      {"url": "https://instagram.com/thumbnail_candidate.jpg", "width": 1080, "height": 1920}
                    ]
                  },
                  "unrecognized_video_stream": {
                    "url": "https://instagram.com/stream.mp4"
                  }
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXunknownContainer", "https://www.instagram.com/reel/DdXunknownContainer/")
        assertTrue("Expected failure for target with image_versions2 and unknown media container", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Expected Technical error, found: $error", error is MetaExtractionError.Technical)
        assertTrue("Must allow fallback / profile escalation", error.canFallback)
    }

    @Test
    fun parseInstagramPage_explicitIsVideoTrueWithoutDownloadableRenditions_returnsTechnicalAllowingFallback() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "xdt_shortcode_media": {
                  "shortcode": "DdXexplicitVideoNoStream",
                  "id": "11223344",
                  "owner": {"username": "creator_video"},
                  "is_video": true,
                  "media_type": 2,
                  "video_versions": []
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseInstagramPage(html, "DdXexplicitVideoNoStream", "https://www.instagram.com/reel/DdXexplicitVideoNoStream/")
        assertTrue("Expected failure for confirmed video without renditions", result is MetaExtractionResult.Failure)
        val error = (result as MetaExtractionResult.Failure).error
        assertTrue("Expected Technical error, NEVER NoVideo; found: $error", error is MetaExtractionError.Technical)
        assertTrue("Must allow fallback / profile escalation", error.canFallback)
    }

    @Test
    fun extractMediaInfo_explicitIsVideoWithoutRenditions_escalatesProfiles() = runBlocking {
        val desktopNoStreamHtml = """
            <!DOCTYPE html><html><body><script type="application/json">
            {"data":{"xdt_shortcode_media":{"shortcode":"DdXnoStream","id":"123","is_video":true,"video_versions":[]}}}
            </script></body></html>
        """.trimIndent()
        val mobileWithStreamHtml = """
            <!DOCTYPE html><html><body><script type="application/json">
            {"data":{"xdt_shortcode_media":{"shortcode":"DdXnoStream","id":"123","is_video":true,"video_versions":[{"url":"https://instagram.com/cdn/mobile.mp4","width":1080,"height":1920}]}}}
            </script></body></html>
        """.trimIndent()

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
            var desktopCalled = 0
            var mobileCalled = 0
            override fun fetch(
                url: String,
                profile: com.charleswoo1.videodownloader.data.download.http.RequestProfile,
                identity: com.charleswoo1.videodownloader.data.download.http.BrowserIdentity,
                origin: String?,
                referer: String?,
                customHeaders: Map<String, String>,
                followRedirects: Boolean,
                body: ByteArray?,
                contentType: String?,
                method: String
            ): Result<HttpResponse> {
                return when (profile) {
                    com.charleswoo1.videodownloader.data.download.http.RequestProfile.DESKTOP_NAVIGATION -> {
                        if (url == "https://www.instagram.com/" || url == "https://www.instagram.com") {
                            Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"mock_lsd"}</script></html>""", emptyMap()))
                        } else {
                            desktopCalled++
                            Result.success(HttpResponse(200, url, desktopNoStreamHtml, emptyMap()))
                        }
                    }
                    com.charleswoo1.videodownloader.data.download.http.RequestProfile.MOBILE_NAVIGATION -> {
                        mobileCalled++
                        Result.success(HttpResponse(200, url, mobileWithStreamHtml, emptyMap()))
                    }
                    else -> Result.failure(java.io.IOException("Unexpected"))
                }
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/DdXnoStream/")

        assertTrue("Expected extraction to succeed after escalating to MOBILE", result.isSuccess)
        assertEquals(1, fakeSession.desktopCalled)
        assertEquals(1, fakeSession.mobileCalled)
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE"), testEngine.lastProfileSequence)
    }

    @Test
    fun parseInstagramPage_reelDdcwYWzxZI7_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "require": [
                [
                  "RelayPrefetchedStreamCache",
                  "next",
                  [],
                  [
                    "xdt_api__v1__clips__home__web_info:{\"data\":{\"xdt_api__v1__clips__home__web_info\":{\"items\":[{\"media\":{\"code\":\"DdcwYWzxZI7\",\"id\":\"333444\",\"owner\":{\"username\":\"creator_ddcw\"},\"video_versions\":[{\"url\":\"https://cdninstagram.com/o1/v/t2/f2/m/ddcw.mp4\",\"width\":1080,\"height\":1920}]}}]}}}"
                  ]
                ]
              ]
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseInstagramPageWithDiagnostics(html, "DdcwYWzxZI7", "https://www.instagram.com/reel/DdcwYWzxZI7/")
        assertTrue("Expected success for DdcwYWzxZI7", outcome.result is MetaExtractionResult.Success)
        val media = (outcome.result as MetaExtractionResult.Success).media
        assertEquals("DdcwYWzxZI7", media.postId)
        assertEquals("creator_ddcw", media.uploader)
        assertTrue(outcome.diagnostics.targetWrapperSeen)
        assertTrue(outcome.diagnostics.validatedMediaNodeFound)
    }

    @Test
    fun parseInstagramPage_reelDcvXZ8PnbK_clipsContainer_extractsSuccessfully() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "data": {
                "xdt_api__v1__clips__clips__web_info": {
                  "items": [
                    {
                      "code": "DcvXZ-8PnbK",
                      "id": "777888",
                      "owner": {"username": "creator_dcv"},
                      "video_versions": [
                        {"url": "https://cdninstagram.com/o1/v/t2/f2/m/dcv.mp4", "width": 1080, "height": 1920}
                      ]
                    }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseInstagramPageWithDiagnostics(html, "DcvXZ-8PnbK", "https://www.instagram.com/reel/DcvXZ-8PnbK/")
        assertTrue("Expected success for DcvXZ-8PnbK clips container", outcome.result is MetaExtractionResult.Success)
        val media = (outcome.result as MetaExtractionResult.Success).media
        assertEquals("DcvXZ-8PnbK", media.postId)
        assertEquals("creator_dcv", media.uploader)
        assertTrue(outcome.diagnostics.targetWrapperSeen)
        assertTrue(outcome.diagnostics.validatedMediaNodeFound)
    }

    @Test
    fun parseInstagramPage_diagnosticFingerprintContainsExpectedFields() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {"data":{"xdt_shortcode_media":{"code":"DiagTestPost","id":"123","video_versions":[{"url":"https://cdninstagram.com/v.mp4","width":720,"height":1280}]}}}
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseInstagramPageWithDiagnostics(html, "DiagTestPost", "https://www.instagram.com/reel/DiagTestPost/")
        val diag = outcome.diagnostics
        assertEquals(1, diag.appJsonCount)
        assertTrue(diag.rawShortcodeSeen)
        assertTrue(diag.decodedShortcodeSeen)
        assertTrue(diag.targetWrapperSeen)
        assertTrue(diag.validatedMediaNodeFound)
        assertTrue(diag.matchedKeys.contains("video_versions"))
    }

    @Test
    fun parseInstagramPage_targetIsolationRejectsMismatchedShortcode() {
        val html = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {"data":{"xdt_shortcode_media":{"code":"OtherPost","id":"999","video_versions":[{"url":"https://cdninstagram.com/other.mp4","width":720,"height":1280}]}}}
            </script>
            </body></html>
        """.trimIndent()

        val outcome = engine.parseInstagramPageWithDiagnostics(html, "TargetPostWanted", "https://www.instagram.com/reel/TargetPostWanted/")
        assertTrue("Target isolation must reject mismatched shortcode", outcome.result is MetaExtractionResult.Failure)
        assertFalse(outcome.diagnostics.targetWrapperSeen)
        assertFalse(outcome.diagnostics.validatedMediaNodeFound)
    }

    @Test
    fun extractMediaInfo_failedDesktopAndEmptyMobile_escalatesToCrawlerAndSucceeds() = runBlocking {
        val crawlerHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {"data":{"xdt_shortcode_media":{"code":"TestEscalatePost","id":"999111","video_versions":[{"url":"https://cdninstagram.com/escalate.mp4","width":1080,"height":1920}]}}}
            </script>
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
            ): Result<PlatformHttpSession.HttpResponse> {
                return when (profile) {
                    RequestProfile.DESKTOP_NAVIGATION -> Result.failure(java.io.IOException("Network connection reset"))
                    RequestProfile.MOBILE_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(403, url, "", mapOf("content-type" to "text/html"))
                    )
                    RequestProfile.CRAWLER_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(200, url, crawlerHtml, mapOf("content-type" to "text/html"))
                    )
                    else -> Result.failure(java.io.IOException("Unsupported profile"))
                }
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/TestEscalatePost/")

        assertTrue("Extraction should succeed via CRAWLER escalation", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("https://cdninstagram.com/escalate.mp4", media?.qualityOptions?.first()?.formatSelector)

        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER"), testEngine.lastProfileSequence)

        val fp = testEngine.lastDiagnosticFingerprint
        assertNotNull(fp)
        assertTrue("Fingerprint must contain DESKTOP with FETCH_FAILED", fp!!.contains("profile=DESKTOP") && fp.contains("stage=FETCH_FAILED") && fp.contains("error=IOException"))
        assertTrue("Fingerprint must contain MOBILE with EMPTY_BODY", fp.contains("profile=MOBILE") && fp.contains("http_status=403") && fp.contains("stage=EMPTY_BODY"))
        assertTrue("Fingerprint must contain CRAWLER with error=NONE", fp.contains("profile=CRAWLER") && fp.contains("http_status=200") && fp.contains("error=NONE"))

        val desktopIdx = fp.indexOf("profile=DESKTOP")
        val mobileIdx = fp.indexOf("profile=MOBILE")
        val crawlerIdx = fp.indexOf("profile=CRAWLER")

        assertTrue("DESKTOP must precede MOBILE", desktopIdx < mobileIdx)
        assertTrue("MOBILE must precede CRAWLER", mobileIdx < crawlerIdx)
    }

    @Test
    fun extractMediaInfo_allProfilesFailBeforeParsing_returnsFallbackEligibleTechnicalError() = runBlocking {
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
                    RequestProfile.DESKTOP_NAVIGATION -> Result.failure(java.io.IOException("Desktop fetch timeout"))
                    RequestProfile.MOBILE_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(403, url, "", mapOf("content-type" to "text/html"))
                    )
                    RequestProfile.CRAWLER_NAVIGATION -> Result.success(
                        PlatformHttpSession.HttpResponse(500, url, "   ", mapOf("content-type" to "text/html"))
                    )
                    else -> Result.failure(java.io.IOException("Unsupported profile"))
                }
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/AllFailedPost/")

        assertTrue("Extraction must fail when all profiles fail", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Error must be MetaExtractionError.Technical", err is MetaExtractionError.Technical)
        assertTrue("Technical error MUST be fallback-eligible", (err as MetaExtractionError.Technical).canFallback)
        assertFalse("Must not report target-not-in-page when no parse ran", err.message?.contains("not found in page data") == true)

        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER"), testEngine.lastProfileSequence)

        val fp = testEngine.lastDiagnosticFingerprint
        assertNotNull(fp)
        assertTrue("Fingerprint must contain DESKTOP FETCH_FAILED", fp!!.contains("profile=DESKTOP") && fp.contains("stage=FETCH_FAILED"))
        assertTrue("Fingerprint must contain MOBILE EMPTY_BODY", fp.contains("profile=MOBILE") && fp.contains("stage=EMPTY_BODY"))
        assertTrue("Fingerprint must contain CRAWLER EMPTY_BODY", fp.contains("profile=CRAWLER") && fp.contains("stage=EMPTY_BODY"))
        assertFalse("Fingerprint must NOT contain TARGET_NOT_IN_PAGE", fp.contains("TARGET_NOT_IN_PAGE"))
    }

    @Test
    fun extractMediaInfo_polarisGraphQL_success_extractsMediaImmediately() = runBlocking {
        val polarisJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "DdS5sMrxkBq",
                    "id": "123456789",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_1080.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "polaris_user"},
                    "display_url": "https://instagram.com/cdn/thumb.jpg"
                  }
                }
              }
            }
        """.trimIndent()

        val fakeSession = FakePlatformHttpSession(polarisJson = polarisJson)
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")
        assertTrue("Expected extraction to succeed via Polaris GraphQL", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertTrue("Expected non-blank title", media?.title?.isNotBlank() == true)
        assertEquals("https://instagram.com/cdn/polaris_1080.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(listOf("POLARIS_GRAPHQL"), customEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_polarisGraphQL_gatedLoggedOut_returnsTerminalLoginRequired() = runBlocking {
        val polarisJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": null
                }
              }
            }
        """.trimIndent()

        val fakeSession = FakePlatformHttpSession(
            polarisJson = polarisJson,
            browserHtml = "<html><head><title>Login • Instagram</title></head><body><a href=\"/accounts/login/\">Login</a></body></html>"
        )
        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)

        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")
        assertTrue("Expected failure when post is gated logged out and webpage is login-walled", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be LoginRequired, found $error", error is PlatformExtractionError.LoginRequired || (error is MetaExtractionError.Restricted && error.reason == RestrictionReason.LOGIN_REQUIRED))
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER"), customEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_polarisGraphQL_explicit429_returnsRateLimitedImmediately() = runBlocking {
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
                return Result.success(HttpResponse(429, url, "Too Many Requests", emptyMap()))
            }
        }

        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")
        assertTrue("Expected failure on HTTP 429", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be RateLimited, found $error", error is PlatformExtractionError.RateLimited)
        assertEquals(listOf("POLARIS_GRAPHQL"), customEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_authenticatedSession_callsMediaInfoApi_success() = runBlocking {
        val store = com.charleswoo1.videodownloader.data.download.http.InMemoryPlatformCredentialStore()
        val sessionProvider = com.charleswoo1.videodownloader.data.download.http.AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(
            com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM,
            "sessionid=test_session_id_123456789; csrftoken=abc; ds_user_id=12345"
        )
        sessionProvider.markActive(com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM, "Validated")

        val apiJson = """
            {
              "items": [
                {
                  "code": "DdS5sMrxkBq",
                  "id": "123456789",
                  "video_versions": [
                    {"url": "https://instagram.com/cdn/auth_video.mp4", "width": 1080, "height": 1920}
                  ],
                  "user": {"username": "auth_creator"},
                  "image_versions2": {
                    "candidates": [
                      {"url": "https://instagram.com/cdn/auth_thumb.jpg", "width": 1080, "height": 1920}
                    ]
                  }
                }
              ]
            }
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
                if (url == "https://www.instagram.com/" || url == "https://www.instagram.com") {
                    return Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"mock_lsd"}</script></html>""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":{"xig_polaris_media":{"if_not_gated_logged_out":null}}}""", emptyMap()))
                }
                if (url.contains("/api/v1/media/")) {
                    return Result.success(HttpResponse(200, url, apiJson, emptyMap()))
                }
                return Result.success(HttpResponse(200, url, "<html><title>Login • Instagram</title><a href=\"/accounts/login/\">Login</a></html>", emptyMap()))
            }
        }

        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected success via Authenticated API", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertTrue("Expected non-blank title", media?.title?.isNotBlank() == true)
        assertEquals("https://instagram.com/cdn/auth_video.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER", "API_AUTHENTICATED"), customEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_authenticatedSession_sessionExpired401_marksExpiredAndReturnsSessionExpired() = runBlocking {
        val store = com.charleswoo1.videodownloader.data.download.http.InMemoryPlatformCredentialStore()
        val sessionProvider = com.charleswoo1.videodownloader.data.download.http.AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(
            com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM,
            "sessionid=expired_session; csrftoken=abc; ds_user_id=12345"
        )
        sessionProvider.markActive(com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM, "Validated")

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
                if (url == "https://www.instagram.com/" || url == "https://www.instagram.com") {
                    return Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"mock_lsd"}</script></html>""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, """{"data":{"xig_polaris_media":{"if_not_gated_logged_out":null}}}""", emptyMap()))
                }
                if (url.contains("/api/v1/media/")) {
                    return Result.success(HttpResponse(401, url, """{"message": "login_required"}""", emptyMap()))
                }
                return Result.success(HttpResponse(200, url, "<html><title>Login • Instagram</title><a href=\"/accounts/login/\">Login</a></html>", emptyMap()))
            }
        }

        val customEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = customEngine.extractMediaInfo("https://www.instagram.com/reel/DdS5sMrxkBq/")

        assertTrue("Expected failure on 401 expired session", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be SessionExpired, found $error", error is PlatformExtractionError.SessionExpired)
        assertFalse("Session must be marked expired", sessionProvider.hasAuthenticatedSession(com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM))
        assertEquals(
            com.charleswoo1.videodownloader.data.download.http.SessionState.EXPIRED,
            sessionProvider.sessionStatus(com.charleswoo1.videodownloader.domain.model.Platform.INSTAGRAM).state
        )
        assertEquals(listOf("POLARIS_GRAPHQL", "DESKTOP", "MOBILE", "CRAWLER", "API_AUTHENTICATED"), customEngine.lastProfileSequence)
    }

    private data class CapturedRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?
    )

    @Test
    fun fetchPolarisLoggedOutGraphQL_requestCapture_verifiesYtDlpContract() = runBlocking {
        val capturedRequests = mutableListOf<CapturedRequest>()
        val shortcode = "DdS5sMrxkBq"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)

        val polarisResponseJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_capture.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "polaris_tester"}
                  }
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
                val bodyStr = body?.let { String(it, Charsets.UTF_8) }
                capturedRequests.add(CapturedRequest(url, method, customHeaders, bodyStr))

                return when {
                    url.contains("/api/v1/web/get_ruling_for_content/") -> {
                        Result.success(HttpResponse(200, url, """{"status":"ok"}""", mapOf("Set-Cookie" to "csrftoken=captured_csrf_123; Path=/")))
                    }
                    url == "https://www.instagram.com/" || url == "https://www.instagram.com/p/$shortcode/" -> {
                        Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"captured_lsd_token_abc"}</script></html>""", emptyMap()))
                    }
                    url.contains("/api/graphql") -> {
                        Result.success(HttpResponse(200, url, polarisResponseJson, emptyMap()))
                    }
                    else -> Result.failure(java.io.IOException("Unknown url: $url"))
                }
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Expected extraction to succeed", result.isSuccess)

        // 1. Verify content-ruling preflight check happened
        val rulingReq = capturedRequests.find { it.url.contains("/api/v1/web/get_ruling_for_content/") }
        assertNotNull("Content-ruling preflight request must be made", rulingReq)
        assertTrue("Ruling request must target expected numeric mediaId", rulingReq!!.url.contains("target_id=$expectedMediaId"))
        assertEquals("936619743392459", rulingReq.headers["X-IG-App-ID"])

        // 2. Verify page bootstrap happened to extract LSD
        val pageReq = capturedRequests.find { it.url == "https://www.instagram.com/" || it.url == "https://www.instagram.com/p/$shortcode/" }
        assertNotNull("Page bootstrap request must be made to extract LSD", pageReq)

        // 3. Verify Polaris GraphQL POST request contract
        val gqlReq = capturedRequests.find { it.url.contains("/api/graphql") }
        assertNotNull("Polaris GraphQL POST request must be made", gqlReq)
        assertEquals("POST", gqlReq!!.method)

        // Header contract verification
        assertEquals("PolarisLoggedOutDesktopWWWPostRootContentQuery", gqlReq.headers["X-FB-Friendly-Name"])
        assertEquals("captured_lsd_token_abc", gqlReq.headers["X-FB-LSD"])
        assertEquals("captured_csrf_123", gqlReq.headers["X-CSRFToken"])
        assertEquals("936619743392459", gqlReq.headers["X-IG-App-ID"])
        assertEquals("XMLHttpRequest", gqlReq.headers["X-Requested-With"])
        assertEquals("https://www.instagram.com", gqlReq.headers["Origin"])
        assertEquals("https://www.instagram.com/p/$shortcode/", gqlReq.headers["Referer"])

        // Form post data contract verification
        val formBody = gqlReq.body
        assertNotNull("GraphQL form body must not be null", formBody)
        assertTrue("Form body must contain lsd token", formBody!!.contains("lsd=captured_lsd_token_abc"))
        assertTrue("Form body must contain fb_api_caller_class=RelayModern", formBody.contains("fb_api_caller_class=RelayModern"))
        assertTrue("Form body must contain fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery", formBody.contains("fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery"))
        assertTrue("Form body must contain doc_id=27130156389949648", formBody.contains("doc_id=27130156389949648"))

        // Critical test requirement: variables MUST contain media_id, NOT shortcode
        val decodedFormBody = java.net.URLDecoder.decode(formBody, "UTF-8")
        assertTrue("Variables must contain media_id: $expectedMediaId", decodedFormBody.contains(""""media_id":"$expectedMediaId""""))
        assertFalse("Variables MUST NOT regress to shortcode", decodedFormBody.contains(""""shortcode":"""))
    }

    @Test
    fun fetchPolarisLoggedOutGraphQL_missingLsd_doesNotSendGraphQLWithPlaceholder() = runBlocking {
        val shortcode = "DdSmissingLsd"
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
                    return Result.failure(java.io.IOException("GraphQL must not be called when LSD is missing"))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                // Page returns HTML without any LSD token
                return Result.success(HttpResponse(200, url, "<html><body>No LSD token here</body></html>", emptyMap()))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.fetchPolarisLoggedOutGraphQL(shortcode, "https://www.instagram.com/p/$shortcode/")

        assertTrue("Expected failure when LSD token is missing", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error must be TargetNotInPageData; found: $error", error is PlatformExtractionError.TargetNotInPageData)
        assertTrue("GraphQL POST request MUST NOT be sent with placeholder/dummy LSD", capturedGqlRequests.isEmpty())
    }

    @Test
    fun fetchPolarisLoggedOutGraphQL_rulingTransportFailure_stillAttemptsPolaris() = runBlocking {
        val shortcode = "DdSpreflightFail"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)
        val capturedGqlRequests = mutableListOf<String>()

        val polarisResponseJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_resilient.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "resilient_user"}
                  }
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
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    // Ruling preflight fails with transport error
                    return Result.failure(java.io.IOException("Network connection reset on ruling preflight"))
                }
                if (url == "https://www.instagram.com/" || url == "https://www.instagram.com/p/$shortcode/") {
                    return Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"real_bootstrap_lsd_123"}</script></html>""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    capturedGqlRequests.add(url)
                    return Result.success(HttpResponse(200, url, polarisResponseJson, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Extraction must succeed even if ruling preflight has transport failure", result.isSuccess)
        val media = result.getOrNull()
        assertEquals("https://instagram.com/cdn/polaris_resilient.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(1, capturedGqlRequests.size)
    }

    @Test
    fun fetchPolarisLoggedOutGraphQL_ruling500_continuesAsNonFatalAdvisory() = runBlocking {
        val shortcode = "DdSpreflight500"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)
        val capturedGqlRequests = mutableListOf<String>()

        val polarisResponseJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_server_err_ok.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "server_err_user"}
                  }
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
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    // Ruling preflight returns non-terminal 500
                    return Result.success(HttpResponse(500, url, "Internal Server Error", emptyMap()))
                }
                if (url == "https://www.instagram.com/" || url == "https://www.instagram.com/p/$shortcode/") {
                    return Result.success(HttpResponse(200, url, """<html><script id="__eqmc">{"l":"real_bootstrap_lsd_500"}</script></html>""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    capturedGqlRequests.add(url)
                    return Result.success(HttpResponse(200, url, polarisResponseJson, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Extraction must succeed even if ruling preflight returns 500", result.isSuccess)
        val media = result.getOrNull()
        assertEquals("https://instagram.com/cdn/polaris_server_err_ok.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertEquals(1, capturedGqlRequests.size)
    }

    @Test
    fun fetchAnonymousLsdToken_basePageEqmc_extractsTokenAndSendsInPolaris() = runBlocking {
        val shortcode = "DdSbasePageEqmc"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)
        val capturedBaseHeaders = mutableListOf<Map<String, String>>()
        val capturedGqlHeaders = mutableListOf<Map<String, String>>()
        var basePageCalled = false

        val basePageHtml = """
            <!DOCTYPE html><html><head>
            <script id="__eqmc" type="application/json">{"l":"real_eqmc_lsd_987","f":123}</script>
            </head><body>Instagram</body></html>
        """.trimIndent()

        val polarisResponseJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_eqmc.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "eqmc_user"}
                  }
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
                if (url == "https://www.instagram.com/") {
                    basePageCalled = true
                    capturedBaseHeaders.add(customHeaders)
                    return Result.success(HttpResponse(200, url, basePageHtml, emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    capturedGqlHeaders.add(customHeaders)
                    return Result.success(HttpResponse(200, url, polarisResponseJson, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Base-page LSD extraction should succeed", result.isSuccess)
        assertTrue("Base-page https://www.instagram.com/ must be requested", basePageCalled)
        assertEquals(1, capturedBaseHeaders.size)
        assertEquals("true", capturedBaseHeaders[0]["X-Anonymous-Context"])

        assertEquals(1, capturedGqlHeaders.size)
        assertEquals("real_eqmc_lsd_987", capturedGqlHeaders[0]["X-FB-LSD"])
        assertEquals("true", capturedGqlHeaders[0]["X-Anonymous-Context"])
    }

    @Test
    fun fetchAnonymousLsdToken_targetPageLoginWall_doesNotBlockBasePageBootstrap() = runBlocking {
        val shortcode = "DdSTargetGated"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)
        val capturedGqlHeaders = mutableListOf<Map<String, String>>()

        val basePageHtml = """
            <!DOCTYPE html><html><head>
            <script>["LSD",[],{"token":"homepage_lsd_token"}]</script>
            </head><body>Instagram Homepage</body></html>
        """.trimIndent()

        val polarisResponseJson = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/polaris_homepage_lsd.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "home_lsd_user"}
                  }
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
                if (url == "https://www.instagram.com/") {
                    return Result.success(HttpResponse(200, url, basePageHtml, emptyMap()))
                }
                if (url == "https://www.instagram.com/p/$shortcode/") {
                    // Target post redirects to login wall
                    return Result.success(HttpResponse(302, "https://www.instagram.com/accounts/login/?next=/p/$shortcode/", "", emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    capturedGqlHeaders.add(customHeaders)
                    return Result.success(HttpResponse(200, url, polarisResponseJson, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Target login wall must not prevent extraction when base-page yields LSD", result.isSuccess)
        assertEquals("homepage_lsd_token", capturedGqlHeaders[0]["X-FB-LSD"])
    }

    @Test
    fun extractMediaInfo_polarisMissingGated_fallsThroughToWebpageRelay_succeedsWithoutLogin() = runBlocking {
        val shortcode = "DdSRelayFallback"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)

        // Polaris returns xig_polaris_media without if_not_gated_logged_out
        val polarisResponseNoGated = """
            {
              "data": {
                "xig_polaris_media": {}
              }
            }
        """.trimIndent()

        val relayPageHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "require": [
                [
                  "RelayPrefetchedStreamCache",
                  "next",
                  [],
                  [
                    "PolarisQuery",
                    {
                      "__bbox": {
                        "result": {
                          "data": {
                            "xig_polaris_media": {
                              "if_not_gated_logged_out": {
                                "shortcode": "$shortcode",
                                "id": "$expectedMediaId",
                                "is_video": true,
                                "video_versions": [
                                  {"url": "https://instagram.com/cdn/relay_saved_video.mp4", "width": 1080, "height": 1920}
                                ],
                                "owner": {"username": "relay_hero"}
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                ]
              ]
            }
            </script>
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
                if (url == "https://www.instagram.com/") {
                    return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"base_lsd"}]""", emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, polarisResponseNoGated, emptyMap()))
                }
                if (url.contains("/p/$shortcode/")) {
                    return Result.success(HttpResponse(200, url, relayPageHtml, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Should succeed via target webpage Relay fallback when Polaris lacks if_not_gated_logged_out", result.isSuccess)
        val media = result.getOrNull()
        assertEquals("https://instagram.com/cdn/relay_saved_video.mp4", media?.qualityOptions?.first()?.formatSelector)
        assertTrue("Sequence should include POLARIS_GRAPHQL", testEngine.lastProfileSequence.contains("POLARIS_GRAPHQL"))
        assertTrue("Sequence should include DESKTOP fallback", testEngine.lastProfileSequence.contains("DESKTOP"))
    }

    @Test
    fun extractMediaInfo_polarisMissingGated_andTargetPageLoginRedirect_returnsLoginRequired() = runBlocking {
        val shortcode = "DdSLoginRedirect"

        val polarisResponseNoGated = """
            {
              "data": {
                "xig_polaris_media": {}
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
                if (url == "https://www.instagram.com/") {
                    return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"base_lsd"}]""", emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, polarisResponseNoGated, emptyMap()))
                }
                if (url.contains("/p/$shortcode/") || url.contains("/reel/$shortcode/")) {
                    return Result.success(HttpResponse(302, "https://www.instagram.com/accounts/login/?next=/reel/$shortcode/", "", emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Should fail with LoginRequired when target webpage redirects to login", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Error must be LoginRequired; found $err", err is PlatformExtractionError.LoginRequired || (err is MetaExtractionError.Restricted && err.reason == RestrictionReason.LOGIN_REQUIRED))
    }

    @Test
    fun extractMediaInfo_activeSession_publicSuccess_neverCallsAuthenticatedApi() = runBlocking {
        val shortcode = "DdSPublicAnon"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)

        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.INSTAGRAM, "sessionid=active_ig_session_123")
        sessionProvider.markActive(Platform.INSTAGRAM, "Active session")
        assertTrue(sessionProvider.hasAuthenticatedSession(Platform.INSTAGRAM))

        var authenticatedApiCalled = false

        val polarisSuccess = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/public_video.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "public_creator"}
                  }
                }
              }
            }
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
                if (url.contains("/api/v1/media/")) {
                    authenticatedApiCalled = true
                    return Result.failure(java.io.IOException("Authenticated API should NOT be called for public content"))
                }
                if (url == "https://www.instagram.com/") {
                    return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_123"}]""", emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    return Result.success(HttpResponse(200, url, polarisSuccess, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue("Public post must succeed anonymously", result.isSuccess)
        assertFalse("Authenticated API must NEVER be called when anonymous extraction succeeds", authenticatedApiCalled)
        assertEquals(listOf("POLARIS_GRAPHQL"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_anonymousRequests_doNotSendSessionIdEvenWhenActiveSessionExists() = runBlocking {
        val shortcode = "DdSIsoCookie"
        val expectedMediaId = NativeInstagramEngine.shortcodeToMediaId(shortcode)

        val store = InMemoryPlatformCredentialStore()
        val sessionProvider = AuthenticatedPlatformSessionProvider(store)
        sessionProvider.importSession(Platform.INSTAGRAM, "sessionid=secret_active_session_456")
        sessionProvider.markActive(Platform.INSTAGRAM, "Active session")

        val capturedHeaders = mutableListOf<Map<String, String>>()

        val polarisSuccess = """
            {
              "data": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "shortcode": "$shortcode",
                    "id": "$expectedMediaId",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/public_video.mp4", "width": 1080, "height": 1920}
                    ],
                    "owner": {"username": "iso_creator"}
                  }
                }
              }
            }
        """.trimIndent()

        val realHttpSession = PlatformHttpSession(sessionProvider = sessionProvider)
        // Ensure active cookies exist in cookieJar
        realHttpSession.syncSessionCookies(Platform.INSTAGRAM)
        assertTrue(realHttpSession.cookieJar.getCookieValue("instagram.com", "sessionid") == "secret_active_session_456")

        val fakeSession = object : PlatformHttpSession(
            cookieJar = realHttpSession.cookieJar,
            sessionProvider = sessionProvider
        ) {
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
                // Anonymous context check
                assertTrue("Anonymous requests must specify X-Anonymous-Context", customHeaders["X-Anonymous-Context"] == "true")
                if (url == "https://www.instagram.com/") {
                    return Result.success(HttpResponse(200, url, """["LSD",[],{"token":"tok_iso"}]""", emptyMap()))
                }
                if (url.contains("/api/v1/web/get_ruling_for_content/")) {
                    return Result.success(HttpResponse(200, url, """{"status":"ok"}""", emptyMap()))
                }
                if (url.contains("/api/graphql")) {
                    capturedHeaders.add(customHeaders)
                    return Result.success(HttpResponse(200, url, polarisSuccess, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url: $url"))
            }
        }

        val testEngine = NativeInstagramEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://www.instagram.com/reel/$shortcode/")

        assertTrue(result.isSuccess)
        // Ensure anonymous cookie jar was used, never containing the authenticated sessionid
        val anonSessionCookie = fakeSession.anonymousCookieJar.getCookieValue("instagram.com", "sessionid")
        assertTrue("Anonymous cookie jar MUST NOT contain authenticated sessionid", anonSessionCookie.isNullOrBlank())
    }

    @Test
    fun targetIsolation_genericMatcherRejectsIdentityLessPolarisMedia_butRelayProvenanceAccepts() {
        val targetShortcode = "DdSExactTarget"

        // 1. Generic container with identity-less xig_polaris_media (blank code and id)
        val arbitraryContainerHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "suggested_feed": {
                "xig_polaris_media": {
                  "if_not_gated_logged_out": {
                    "code": "",
                    "id": "",
                    "is_video": true,
                    "video_versions": [
                      {"url": "https://instagram.com/cdn/wrong_feed_video.mp4", "width": 1080, "height": 1920}
                    ]
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcomeArbitrary = engine.parseInstagramPageWithDiagnostics(arbitraryContainerHtml, targetShortcode, "https://www.instagram.com/p/$targetShortcode/")
        assertTrue("Generic matcher MUST reject identity-less polarisMedia in arbitrary container", outcomeArbitrary.result is MetaExtractionResult.Failure)

        // 2. Unrelated xig_polaris_media with different code
        val unrelatedContainerHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "xig_polaris_media": {
                "if_not_gated_logged_out": {
                  "code": "DdSWrongPost",
                  "id": "999888777",
                  "is_video": true,
                  "video_versions": [
                    {"url": "https://instagram.com/cdn/unrelated_video.mp4", "width": 1080, "height": 1920}
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcomeUnrelated = engine.parseInstagramPageWithDiagnostics(unrelatedContainerHtml, targetShortcode, "https://www.instagram.com/p/$targetShortcode/")
        assertTrue("Generic matcher MUST reject polarisMedia with mismatching code/id", outcomeUnrelated.result is MetaExtractionResult.Failure)

        // 3. Exact RelayPrefetchedStreamCache provenance accepts even without code/id on target page
        val relayProvenanceHtml = """
            <!DOCTYPE html><html><body>
            <script type="application/json">
            {
              "require": [
                [
                  "RelayPrefetchedStreamCache",
                  "next",
                  [],
                  [
                    "PolarisQuery",
                    {
                      "__bbox": {
                        "result": {
                          "data": {
                            "xig_polaris_media": {
                              "if_not_gated_logged_out": {
                                "code": "",
                                "id": "",
                                "is_video": true,
                                "video_versions": [
                                  {"url": "https://instagram.com/cdn/provenance_target_video.mp4", "width": 1080, "height": 1920}
                                ],
                                "owner": {"username": "provenance_owner"}
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                ]
              ]
            }
            </script>
            </body></html>
        """.trimIndent()

        val outcomeRelay = engine.parseInstagramPageWithDiagnostics(relayProvenanceHtml, targetShortcode, "https://www.instagram.com/p/$targetShortcode/")
        assertTrue("Relay provenance MUST accept target media under exact structural path", outcomeRelay.result is MetaExtractionResult.Success)
        val media = (outcomeRelay.result as MetaExtractionResult.Success).media
        assertEquals("https://instagram.com/cdn/provenance_target_video.mp4", media.progressiveVideoUrls.first())
        assertEquals("RELAY_PREFETCH_TARGET", outcomeRelay.diagnostics.stage)
    }
}
