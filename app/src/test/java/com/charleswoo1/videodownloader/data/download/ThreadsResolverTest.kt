package com.charleswoo1.videodownloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

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
        // Share slug must NOT be confused with post ID (strict isolation)
        assertNull(resolver.extractPostId("https://www.threads.com/share/DTEST999"))
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
    fun parseDashManifest_multipleRepresentations_selectsHighestResolutionAndPreservesLiterals() {
        val manifest = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet mimeType="video/mp4" contentType="video">
                  <Representation id="1" width="720" height="1280" bandwidth="2000000">
                    <BaseURL>https://video.threads.com/720.mp4?token=med+res&amp;stkn=tok+1</BaseURL>
                  </Representation>
                  <Representation id="2" width="1080" height="1920" bandwidth="4500000">
                    <BaseURL>https://video.threads.com/1080.mp4?token=high+res%2Bopt&amp;stkn=tok+2</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" contentType="audio">
                  <Representation id="3" bandwidth="128000">
                    <BaseURL>https://audio.threads.com/dash_audio.mp4?token=aud+128&amp;stkn=tok+3</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val (videoUrl, audioUrl) = resolver.parseDashManifest(manifest)
        assertEquals("https://video.threads.com/1080.mp4?token=high+res+opt&stkn=tok+2", videoUrl)
        assertEquals("https://audio.threads.com/dash_audio.mp4?token=aud+128&stkn=tok+3", audioUrl)
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
            assertTrue(e.message?.contains("TARGET_POST") == true)
            assertTrue(e.message?.contains("解析失敗") == true)
        }
    }

    @Test
    fun extractPostUrlFromShareHtml_resolvesFromCanonicalLink() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <link rel="canonical" href="https://www.threads.net/@creator_abc/post/REAL_POST_123" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val resolved = resolver.extractPostUrlFromShareHtml(html)
        assertEquals("https://www.threads.com/@creator_abc/post/REAL_POST_123", resolved)
    }

    @Test
    fun extractPostUrlFromShareHtml_resolvesFromOgUrl() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta property="og:url" content="https://www.threads.net/@creator_xyz/post/REAL_POST_456" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val resolved = resolver.extractPostUrlFromShareHtml(html)
        assertEquals("https://www.threads.com/@creator_xyz/post/REAL_POST_456", resolved)
    }

    @Test
    fun extractPostUrlFromShareHtml_resolvesFromHtmlContentFallback() {
        val html = """
            <div>Check out the discussion on <a href="/post/FALLBACK_789">this post</a></div>
        """.trimIndent()

        val resolved = resolver.extractPostUrlFromShareHtml(html)
        assertEquals("https://www.threads.com/post/FALLBACK_789", resolved)
    }

    @Test
    fun extractPostUrlFromShareHtml_returnsNullWhenNoPostLinkFound() {
        val html = "<html><body>Generic share page with no post link</body></html>"
        val resolved = resolver.extractPostUrlFromShareHtml(html)
        assertEquals(null, resolved)
    }

    private class TestThreadsResolver(
        private val mockHtml: String = ""
    ) : ThreadsResolver(context = null) {
        var simulateDownloadSuccess = true

        override fun fetchWebpage(targetUrl: String): String {
            if (mockHtml.isNotBlank()) return mockHtml
            return super.fetchWebpage(targetUrl)
        }

        override fun downloadStream(
            streamUrl: String,
            destination: File,
            onProgress: (Float, Long?, String?) -> Unit
        ): Boolean {
            if (!simulateDownloadSuccess) return false
            destination.parentFile?.mkdirs()
            destination.writeText("dummy stream data")
            return true
        }
    }

    @Test
    fun download_dashMergeFailure_returnsFailureAndCleansOutputFiles() = kotlinx.coroutines.runBlocking {
        val testResolver = TestThreadsResolver()
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "threads_dash_fail_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val dashOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "best",
                label = "最佳畫質",
                formatSelector = "https://cdn.threads.com/v.mp4|https://cdn.threads.com/a.mp4",
                isAudioOnly = false
            )
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@u/post/C_dash123",
                title = "Dash Video",
                qualityOption = dashOption
            )

            val result = testResolver.download(request, tempDir, { _, _, _ -> }, {})

            // FFmpeg merge fails because context is null
            assertTrue("DASH merge failure must return Result.failure", result.isFailure)
            val files = tempDir.listFiles() ?: emptyArray()
            assertTrue("No partial output or part files should remain after merge failure, found: ${files.map { it.name }}", files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun download_audioExtractionFailure_returnsFailureAndCleansOutputFiles() = kotlinx.coroutines.runBlocking {
        val testResolver = TestThreadsResolver()
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "threads_audio_fail_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val audioOption = com.charleswoo1.videodownloader.domain.model.QualityOption(
                id = "audio_only",
                label = "僅音訊",
                formatSelector = "https://cdn.threads.com/v.mp4",
                isAudioOnly = true
            )
            val request = com.charleswoo1.videodownloader.domain.model.DownloadRequest(
                url = "https://www.threads.com/@u/post/C_audio123",
                title = "Audio Post",
                qualityOption = audioOption
            )

            val result = testResolver.download(request, tempDir, { _, _, _ -> }, {})

            // FFmpeg audio conversion fails because context is null
            assertTrue("Audio extraction failure must return Result.failure", result.isFailure)
            val files = tempDir.listFiles() ?: emptyArray()
            assertTrue("No mp4 or invalid mp3 should remain after extraction failure, found: ${files.map { it.name }}", files.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractMediaInfo_onlyExposesGenuineBestAndAudioOptionsWithoutFakeResolutionTiers() = kotlinx.coroutines.runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script type="application/json">
            {
              "code": "C_distinctTiers",
              "user": { "username": "creator" },
              "caption": { "text": "Genuine options test" },
              "video_versions": [
                { "url": "https://cdn.threads.com/video.mp4", "height": 1080 }
              ]
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val testResolver = TestThreadsResolver(mockHtml = html)
        val result = testResolver.extractMediaInfo("https://www.threads.com/@creator/post/C_distinctTiers")

        assertTrue("Expected extractMediaInfo success", result.isSuccess)
        val options = result.getOrNull()?.qualityOptions ?: emptyList()

        // Should ONLY have "best" and "audio_only", NO fake "1080p", "720p", "480p", "360p"
        val optionIds = options.map { it.id }
        assertEquals(listOf("best", "audio_only"), optionIds)
        assertEquals("https://cdn.threads.com/video.mp4", options.first { it.id == "best" }.formatSelector)
    }
}
