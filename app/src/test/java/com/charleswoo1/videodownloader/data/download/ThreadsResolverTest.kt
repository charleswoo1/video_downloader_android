package com.charleswoo1.videodownloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ThreadsResolverTest {

    private lateinit var resolver: ThreadsResolver

    @Before
    fun setUp() {
        resolver = ThreadsResolver()
    }

    @Test
    fun normalizeUrl_normalizesThreadsNetAndQueries() {
        val raw1 = "https://www.threads.net/@user_test/post/C_abc123?xmt=AQTEST123"
        val expected1 = "https://www.threads.com/@user_test/post/C_abc123"
        assertEquals(expected1, resolver.normalizeUrl(raw1))

        val raw2 = "https://threads.net/@user_test/post/C_abc123/media"
        val expected2 = "https://www.threads.com/@user_test/post/C_abc123"
        assertEquals(expected2, resolver.normalizeUrl(raw2))

        val raw3 = "https://threads.com/@user_test/post/C_abc123#comments"
        val expected3 = "https://www.threads.com/@user_test/post/C_abc123"
        assertEquals(expected3, resolver.normalizeUrl(raw3))

        val raw4 = "https://www.threads.com/share/DTEST12345/"
        val expected4 = "https://www.threads.com/share/DTEST12345"
        assertEquals(expected4, resolver.normalizeUrl(raw4))
    }

    @Test
    fun extractPostId_extractsFromDifferentPatterns() {
        assertEquals("C_abc123", resolver.extractPostId("https://www.threads.com/@user/post/C_abc123"))
        assertEquals("C_abc123", resolver.extractPostId("https://www.threads.com/t/C_abc123"))
        assertEquals("DTEST999", resolver.extractPostId("https://www.threads.com/share/DTEST999"))
        assertEquals("C_abc123", resolver.extractPostId("https://www.threads.net/@user/post/C_abc123?xmt=AQ"))
    }

    @Test
    fun parseDashManifest_extractsVideoAndAudioStreams() {
        val manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4" contentType="video">
                  <Representation id="1" bandwidth="2000000">
                    <BaseURL>https://video.threads.com/dash_video.mp4?token=1\u0026tag=vid</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" contentType="audio">
                  <Representation id="2" bandwidth="128000">
                    <BaseURL>https://audio.threads.com/dash_audio.mp4?token=2\u0026tag=aud</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val (videoUrl, audioUrl) = resolver.parseDashManifest(manifest)
        assertEquals("https://video.threads.com/dash_video.mp4?token=1&tag=vid", videoUrl)
        assertEquals("https://audio.threads.com/dash_audio.mp4?token=2&tag=aud", audioUrl)
    }

    @Test
    fun parseThreadsPage_extractsPostWithProgressiveVideo() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Threads</title></head>
            <body>
            <script type="application/json">
            {
              "code": "C_testPost1",
              "user": {
                "username": "awesome_creator"
              },
              "caption": {
                "text": "Check out this amazing nature video!"
              },
              "video_versions": [
                {
                  "url": "https://cdn.threads.com/v/1080p.mp4?sig=abc\u0026exp=123",
                  "height": 1080
                },
                {
                  "url": "https://cdn.threads.com/v/720p.mp4?sig=abc\u0026exp=123",
                  "height": 720
                }
              ],
              "image_versions2": {
                "candidates": [
                  {
                    "url": "https://cdn.threads.com/thumb.jpg"
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = resolver.parseThreadsPage(html, "C_testPost1", "https://www.threads.com/@awesome_creator/post/C_testPost1")

        assertEquals("C_testPost1", result.postId)
        assertEquals("@awesome_creator", result.uploader)
        assertTrue(result.title.contains("awesome_creator"))
        assertTrue(result.title.contains("Check out this amazing nature video"))
        assertEquals("https://cdn.threads.com/thumb.jpg", result.thumbnailUrl)
        assertEquals(2, result.progressiveVideoUrls.size)
        assertEquals("https://cdn.threads.com/v/1080p.mp4?sig=abc&exp=123", result.progressiveVideoUrls[0])
        assertEquals(listOf(1080, 720), result.heights)
    }

    @Test
    fun parseThreadsPage_extractsNestedPostAndDashManifest() {
        val html = """
            <html>
            <body>
            <script type="application/json">
            {
              "data": {
                "feed": [
                  {
                    "post": {
                      "code": "NESTED_999",
                      "user": { "username": "nested_user" },
                      "caption": { "text": "Nested post video" },
                      "video_dash_manifest": "<AdaptationSet mimeType=\"video/mp4\"><BaseURL>https://cdn.threads.com/dash_v.mp4</BaseURL></AdaptationSet><AdaptationSet mimeType=\"audio/mp4\"><BaseURL>https://cdn.threads.com/dash_a.mp4</BaseURL></AdaptationSet>"
                    }
                  }
                ]
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = resolver.parseThreadsPage(html, "NESTED_999", "https://www.threads.com/@nested_user/post/NESTED_999")

        assertEquals("NESTED_999", result.postId)
        assertEquals("@nested_user", result.uploader)
        assertEquals("https://cdn.threads.com/dash_v.mp4", result.dashVideoUrl)
        assertEquals("https://cdn.threads.com/dash_a.mp4", result.dashAudioUrl)
    }

    @Test
    fun parseThreadsPage_throwsWhenPostNotFound() {
        val html = """
            <html><body>
            <script type="application/json">{"code": "OTHER_POST"}</script>
            </body></html>
        """.trimIndent()

        try {
            resolver.parseThreadsPage(html, "TARGET_POST", "https://www.threads.com/@u/post/TARGET_POST")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Threads 貼文解析失敗") == true)
        }
    }
}
