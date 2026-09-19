package com.charleswoo1.videodownloader.data.download.x

import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    fun parseGraphQLTweet_unavailable_returnsProvisionalUnavailableWithFallbackAllowed() {
        val jsonString = loadFixture("tweet_unavailable_graphql.json")
        val json = JSONObject(jsonString)
        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/999", "999")

        assertTrue("Unavailable tweet must fail extraction with provisional error", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError.ProvisionalUnavailable
        assertNotNull("Expected ProvisionalUnavailable, got ${result.exceptionOrNull()}", error)
        assertEquals("TweetUnavailable", error?.typename)
        assertTrue("ProvisionalUnavailable MUST allow fallback", error?.canFallback ?: false)
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

    @Test
    fun generateTransactionId_returnsSlashSeparatedBase64Parts() {
        val txId = NativeXEngine.generateTransactionId()
        assertEquals("Transaction ID must be 34 characters (22 base64 + '/' + 11 base64)", 34, txId.length)
        assertEquals("Separator at index 22 must be '/'", '/', txId[22])

        val part1 = txId.substring(0, 22)
        val part2 = txId.substring(23)
        assertEquals(22, part1.length)
        assertEquals(11, part2.length)

        val decoded1 = java.util.Base64.getDecoder().decode(part1)
        val decoded2 = java.util.Base64.getDecoder().decode(part2)
        assertEquals(16, decoded1.size)
        assertEquals(8, decoded2.size)
    }

    @Test
    fun ensureGuestToken_hashflagsMissing_recoversViaAuthenticatedNavigationFallback() = runBlocking {
        val testSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    // hashflags returns empty headers and dummy json without guest token
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    // Authenticated x.com navigation fallback sets/returns guest token
                    return Result.success(
                        HttpResponse(
                            200,
                            url,
                            "<html></html>",
                            mapOf("x-guest-token" to "nav_fallback_guest_token_999")
                        )
                    )
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = testSession)
        val guestToken = testEngine.ensureGuestToken("dummy_bearer_token")

        assertNotNull("Guest token must be recovered via navigation fallback", guestToken)
        assertEquals("nav_fallback_guest_token_999", guestToken)
    }

    @Test
    fun fetchPostViaGraphQL_sendsTwitterActiveUserAndLanguageHeaders() = runBlocking {
        var capturedActiveUser: String? = null
        var capturedLanguage: String? = null
        var capturedOrigin: String? = null

        val testSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                capturedActiveUser = customHeaders["x-twitter-active-user"]
                capturedLanguage = customHeaders["x-twitter-client-language"]
                capturedOrigin = origin
                return Result.success(HttpResponse(200, url, "{\"data\":{\"tweetResult\":{\"result\":{\"__typename\":\"TweetUnavailable\"}}}}", emptyMap()))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = testSession)
        testEngine.fetchPostViaGraphQL("12345", "test_bearer", "test_guest")

        assertEquals("yes", capturedActiveUser)
        assertEquals("zh-tw", capturedLanguage)
        assertEquals("https://x.com", capturedOrigin)
    }

    @Test
    fun extractMediaInfo_recoverySequence_provisionalOnAttempt1_retriesWithNewGuestTokenAndSucceeds() = runBlocking {
        val videoJson = loadFixture("video_tweet_graphql.json")
        val unavailableJson = loadFixture("tweet_unavailable_graphql.json")
        var graphqlCallCount = 0

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    return Result.success(HttpResponse(200, url, "<html></html>", mapOf("x-guest-token" to "gt_$graphqlCallCount")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    graphqlCallCount++
                    val payload = if (graphqlCallCount == 1) unavailableJson else videoJson
                    return Result.success(HttpResponse(200, url, payload, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/TwitterVideoCreator/status/1234567890")

        assertTrue("Expected extraction to succeed after retry on attempt 2", result.isSuccess)
        val mediaInfo = result.getOrNull()
        assertNotNull(mediaInfo)
        assertEquals(2, graphqlCallCount)
        assertEquals(listOf("GRAPHQL"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_fallbackSequence_bothGraphQLAttemptsProvisional_htmlFallbackSucceeds() = runBlocking {
        val unavailableJson = loadFixture("tweet_unavailable_graphql.json")
        val htmlFallback = loadFixture("html_fallback_initial_state.html")
        var graphqlCallCount = 0
        var htmlFetched = false

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    return Result.success(HttpResponse(200, url, "<html></html>", mapOf("x-guest-token" to "gt_test")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    graphqlCallCount++
                    return Result.success(HttpResponse(200, url, unavailableJson, emptyMap()))
                }
                if (url.contains("5678901234")) {
                    htmlFetched = true
                    return Result.success(HttpResponse(200, url, htmlFallback, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/user/status/5678901234")

        assertTrue("Expected extraction to succeed via HTML fallback", result.isSuccess)
        val mediaInfo = result.getOrNull()
        assertNotNull(mediaInfo)
        assertEquals(2, graphqlCallCount)
        assertTrue("HTML must have been fetched", htmlFetched)
        assertEquals(listOf("GRAPHQL", "HTML_FALLBACK"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_terminalFailure_htmlFallbackUncorroborated_returnsTechnicalAllowingFallback() = runBlocking {
        val unavailableJson = loadFixture("tweet_unavailable_graphql.json")
        val uncorroboratedHtml = "<html><head><title>X</title></head><body><div>Generic layout without tweet or tombstone</div></body></html>"

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    return Result.success(HttpResponse(200, url, "<html></html>", mapOf("x-guest-token" to "gt_test")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    return Result.success(HttpResponse(200, url, unavailableJson, emptyMap()))
                }
                if (url.contains("1234567890")) {
                    return Result.success(HttpResponse(200, url, uncorroboratedHtml, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/user/status/1234567890")

        assertTrue("Expected failure", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertTrue("Error must allow fallback for uncorroborated unavailable", error?.canFallback ?: false)
        assertFalse("Must NOT be false DeletedOrNotFound", error?.code == PlatformErrorCode.DELETED_OR_NOT_FOUND)
        assertEquals(listOf("GRAPHQL", "HTML_FALLBACK"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_corroboratedDeleted_htmlContainsTombstone_returnsTerminalDeletedOrNotFound() = runBlocking {
        val unavailableJson = loadFixture("tweet_unavailable_graphql.json")
        val tombstoneHtml = "<html><body><div>This Post was deleted by the Post author</div></body></html>"

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    return Result.success(HttpResponse(200, url, "<html></html>", mapOf("x-guest-token" to "gt_test")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    return Result.success(HttpResponse(200, url, unavailableJson, emptyMap()))
                }
                if (url.contains("1234567890")) {
                    return Result.success(HttpResponse(200, url, tombstoneHtml, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/user/status/1234567890")

        assertTrue("Expected failure", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals(PlatformErrorCode.DELETED_OR_NOT_FOUND, error?.code)
        assertFalse("Terminal DeletedOrNotFound must not allow fallback", error?.canFallback ?: true)
        assertEquals(listOf("GRAPHQL", "HTML_FALLBACK"), testEngine.lastProfileSequence)
    }

    @Test
    fun extractMediaInfo_provisionalUnavailable_withPreloadedCookieJar_invalidatesCookieAndRefreshes() = runBlocking {
        val videoJson = loadFixture("video_tweet_graphql.json")
        val unavailableJson = loadFixture("tweet_unavailable_graphql.json")
        var graphqlCallCount = 0
        val capturedGuestTokens = mutableListOf<String?>()

        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    return Result.success(HttpResponse(200, url, "{}", emptyMap()))
                }
                if (url == NativeXEngine.TWITTER_HOME_URL) {
                    return Result.success(HttpResponse(200, url, "<html></html>", mapOf("x-guest-token" to "fresh_guest_token_retry")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    graphqlCallCount++
                    capturedGuestTokens.add(customHeaders["x-guest-token"])
                    val payload = if (graphqlCallCount == 1) unavailableJson else videoJson
                    return Result.success(HttpResponse(200, url, payload, emptyMap()))
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        // Preload stale gt cookie in cookieJar
        val staleCookie = okhttp3.Cookie.Builder()
            .domain("x.com")
            .name("gt")
            .value("stale_cookie_gt_123")
            .path("/")
            .build()
        fakeSession.cookieJar.putCookie("https://x.com/".toHttpUrl(), staleCookie)

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/TwitterVideoCreator/status/1234567890")

        assertTrue("Expected extraction to succeed after refresh", result.isSuccess)
        assertEquals(2, graphqlCallCount)
        assertEquals("stale_cookie_gt_123", capturedGuestTokens[0])
        assertEquals("fresh_guest_token_retry", capturedGuestTokens[1])
        // Verify stale cookie was purged from cookie jar
        assertEquals(null, fakeSession.cookieJar.getCookieValue("x.com", "gt"))
    }

    @Test
    fun parseGraphQLTweet_explicitVideoMediaWithoutSupportedVariants_returnsMediaUrlUnsupportedWithFallbackAllowed() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "rest_id": "999999",
                    "legacy": {
                      "full_text": "Tweet with unsupported video stream",
                      "extended_entities": {
                        "media": [
                          {
                            "type": "video",
                            "media_url_https": "https://pbs.twimg.com/media/thumb.jpg",
                            "video_info": {
                              "variants": [
                                {
                                  "content_type": "application/x-mpegURL",
                                  "url": "https://video.twimg.com/ext_tw_video/m3u8/unsupported.m3u8"
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/999999", "999999")
        assertTrue("Expected failure for video without supported variants", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals("Expected MEDIA_URL_UNSUPPORTED, NEVER NO_VIDEO", PlatformErrorCode.MEDIA_URL_UNSUPPORTED, error?.code)
        assertTrue("Must allow fallback to HTML or secondary engines", error?.canFallback ?: false)
    }

    @Test
    fun parseHtmlFallback_explicitVideoMediaWithoutSupportedVariants_returnsMediaUrlUnsupportedWithFallbackAllowed() = runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <script>
            window.__INITIAL_STATE__ = {
              "entities": {
                "tweets": {
                  "entities": {
                    "888888": {
                      "id_str": "888888",
                      "full_text": "HTML fallback tweet with unsupported video variants",
                      "extended_entities": {
                        "media": [
                          {
                            "type": "video",
                            "media_url_https": "https://pbs.twimg.com/media/thumb.jpg",
                            "video_info": {
                              "variants": [
                                {
                                  "content_type": "application/x-mpegURL",
                                  "url": "https://video.twimg.com/m3u8/unsupported.m3u8"
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              }
            };
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseHtmlFallback(html, "https://x.com/i/status/888888", "888888")
        assertTrue("Expected failure for HTML video without supported variants", result.isFailure)
        val error = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(error)
        assertEquals("Expected MEDIA_URL_UNSUPPORTED, NEVER NO_VIDEO", PlatformErrorCode.MEDIA_URL_UNSUPPORTED, error?.code)
        assertTrue("Must allow fallback to secondary engines", error?.canFallback ?: false)
    }
}
