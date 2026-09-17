package com.charleswoo1.videodownloader.domain.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextUrlExtractorTest {

    @Test
    fun extractFirstUrl_bareUrl_returnsUrl() {
        val input = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = SharedTextUrlExtractor.extractFirstUrl(input)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result)
    }

    @Test
    fun extractFirstUrl_embeddedInEnglishText_returnsUrl() {
        val input = "Hey check out this video: https://www.youtube.com/watch?v=dQw4w9WgXcQ it is awesome!"
        val result = SharedTextUrlExtractor.extractFirstUrl(input)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result)
    }

    @Test
    fun extractFirstUrl_embeddedInChineseText_returnsUrl() {
        val input = "看看這個影片 https://www.tiktok.com/@user/video/1234567890 太好笑了吧"
        val result = SharedTextUrlExtractor.extractFirstUrl(input)
        assertEquals("https://www.tiktok.com/@user/video/1234567890", result)
    }

    @Test
    fun extractFirstUrl_multipleUrls_returnsFirstUrlDeterministically() {
        val input = "首選連結 https://youtu.be/first 次選連結 https://youtu.be/second"
        val result = SharedTextUrlExtractor.extractFirstUrl(input)
        assertEquals("https://youtu.be/first", result)

        val allUrls = SharedTextUrlExtractor.extractUrls(input)
        assertEquals(2, allUrls.size)
        assertEquals("https://youtu.be/first", allUrls[0])
        assertEquals("https://youtu.be/second", allUrls[1])
    }

    @Test
    fun extractFirstUrl_surroundedByPunctuation_cleansPunctuationCorrectly() {
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("(https://example.com/video)")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("[https://example.com/video]")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("「https://example.com/video」")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("『https://example.com/video』")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("https://example.com/video,")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("https://example.com/video。")
        )
        assertEquals(
            "https://example.com/video",
            SharedTextUrlExtractor.extractFirstUrl("請看這個連結：https://example.com/video！")
        )
    }

    @Test
    fun extractFirstUrl_noUrl_returnsNull() {
        assertNull(SharedTextUrlExtractor.extractFirstUrl("這是一段完全沒有網址的純文字訊息"))
        assertNull(SharedTextUrlExtractor.extractFirstUrl(""))
        assertNull(SharedTextUrlExtractor.extractFirstUrl(null))
        assertTrue(SharedTextUrlExtractor.extractUrls("").isEmpty())
    }

    @Test
    fun extractFirstUrl_rejectsNonHttpSchemes() {
        assertNull(SharedTextUrlExtractor.extractFirstUrl("ftp://files.example.com/movie.mp4"))
        assertNull(SharedTextUrlExtractor.extractFirstUrl("content://media/external/video/media/1"))
        assertNull(SharedTextUrlExtractor.extractFirstUrl("file:///sdcard/video.mp4"))
    }
}
