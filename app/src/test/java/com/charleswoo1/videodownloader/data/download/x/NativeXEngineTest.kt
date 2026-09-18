package com.charleswoo1.videodownloader.data.download.x

import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeXEngineTest {

    private lateinit var engine: NativeXEngine

    @Before
    fun setUp() {
        engine = NativeXEngine(context = null)
    }

    private fun loadFixture(filename: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/x/$filename")
            ?: throw IllegalArgumentException("Fixture not found: fixtures/x/$filename")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun extractStatusId_parsesValidUrls() {
        assertEquals("1234567890", engine.extractStatusId("https://x.com/Twitter/status/1234567890"))
        assertEquals("1234567890", engine.extractStatusId("https://twitter.com/Twitter/status/1234567890?s=20&t=abc"))
        assertEquals("2099089385958645960", engine.extractStatusId("https://x.com/SmallQQQQQ/status/2099089385958645960#bottom"))
    }

    @Test
    fun extractBearerTokenFromHtml_findsValidToken() {
        val html = """<script>window.__SCRIPTS__ = {'authorization': 'Bearer AAAAAAAAAAAAAAAAAAAAAabc123def456ghi789jkl012mno345pqr678stu901vwx234yz5678901234567890'};</script>"""
        val token = NativeXEngine.extractBearerTokenFromHtml(html)
        assertNotNull(token)
        assertTrue(token!!.startsWith("AAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun parseGraphQLTweet_videoTweet_extractsHighestBitrate() {
        val jsonString = loadFixture("video_tweet_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/1234567890", "1234567890")

        assertTrue("Expected parse success for video tweet", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("1234567890", media?.statusId)
        assertEquals("TwitterVideoCreator", media?.author)
        assertEquals("https://pbs.twimg.com/media/thumb.jpg", media?.thumbnailUrl)

        val renditions = media?.renditions ?: emptyList()
        assertEquals(3, renditions.size) // 3 video/mp4 variants (m3u8 filtered out)
        assertEquals("https://video.twimg.com/ext_tw_video/123/pu/vid/1280x720/high.mp4", renditions[0].url)
        assertEquals(2176000, renditions[0].width) // Highest bitrate first
        assertFalse(media?.isGif ?: true)
    }

    @Test
    fun parseGraphQLTweet_animatedGif_extractsCorrectly() {
        val jsonString = loadFixture("animated_gif_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/2345678901", "2345678901")

        assertTrue("Expected parse success for animated gif", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertTrue("isGif should be true for animated_gif", media?.isGif ?: false)
        assertEquals("https://video.twimg.com/tweet_video/loop.mp4", media?.renditions?.firstOrNull()?.url)
    }

    @Test
    fun parseGraphQLTweet_photoOnly_failsWithNoVideoAndNoFallback() {
        val jsonString = loadFixture("photo_only_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/3456789012", "3456789012")

        assertTrue("Photo-only post must fail extraction", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals(PlatformErrorCode.NO_VIDEO, error?.code)
        assertFalse("Terminal NO_VIDEO must not allow fallback", error?.canFallback ?: true)
    }

    @Test
    fun parseGraphQLTweet_textOnly_failsWithNoVideoAndNoFallback() {
        val jsonString = loadFixture("text_only_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/4567890123", "4567890123")

        assertTrue("Text-only post must fail extraction", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals(PlatformErrorCode.NO_VIDEO, error?.code)
        assertFalse("Terminal NO_VIDEO must not allow fallback", error?.canFallback ?: true)
    }

    @Test
    fun parseGraphQLTweet_unavailable_failsWithDeletedOrNotFound() {
        val jsonString = loadFixture("tweet_unavailable_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/999", "999")

        assertTrue("Unavailable tweet must fail extraction", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals(PlatformErrorCode.DELETED_OR_NOT_FOUND, error?.code)
        assertFalse("DeletedOrNotFound must not allow fallback", error?.canFallback ?: true)
    }

    @Test
    fun parseHtmlFallback_extractsVideoFromInitialState() = runBlocking {
        val html = loadFixture("html_fallback_initial_state.html")
        val result = engine.parseHtmlFallback(html, "https://x.com/i/status/5678901234", "5678901234")

        assertTrue("HTML fallback must succeed for __INITIAL_STATE__", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("5678901234", media?.statusId)
        assertEquals("https://video.twimg.com/ext_tw_video/567/pu/vid/720x1280/fallback_video.mp4", media?.renditions?.firstOrNull()?.url)
        assertEquals(1280000, media?.renditions?.firstOrNull()?.width)
    }
}
