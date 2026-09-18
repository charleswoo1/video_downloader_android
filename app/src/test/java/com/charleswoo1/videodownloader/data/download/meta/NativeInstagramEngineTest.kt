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
}
