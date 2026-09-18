package com.charleswoo1.videodownloader.data.download.meta

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
}
