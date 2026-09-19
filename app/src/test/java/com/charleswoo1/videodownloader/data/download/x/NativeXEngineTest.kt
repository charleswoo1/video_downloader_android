package com.charleswoo1.videodownloader.data.download.x

import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
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

    @Test
    fun parseGraphQLTweet_distinguishableInternalReasons() {
        // Missing legacy
        val missingLegacyJson = JSONObject("""{"data":{"tweetResult":{"result":{"__typename":"Tweet"}}}}""")
        val resLegacy = engine.parseGraphQLTweet(missingLegacyJson, "https://x.com/i/status/1", "1")
        val errLegacy = resLegacy.exceptionOrNull() as? PlatformExtractionError
        assertEquals("GRAPHQL_MISSING_LEGACY", errLegacy?.internalReason)

        // Provisional unavailable
        val unavailJson = JSONObject("""{"data":{"tweetResult":{"result":{"__typename":"TweetUnavailable"}}}}""")
        val resUnavail = engine.parseGraphQLTweet(unavailJson, "https://x.com/i/status/2", "2")
        val errUnavail = resUnavail.exceptionOrNull() as? PlatformExtractionError
        assertEquals("GRAPHQL_PROVISIONAL_UNAVAILABLE:TweetUnavailable", errUnavail?.internalReason)

        // Target wrapper unsupported
        val wrapperJson = JSONObject("""{"data":{}}""")
        val resWrapper = engine.parseGraphQLTweet(wrapperJson, "https://x.com/i/status/3", "3")
        val errWrapper = resWrapper.exceptionOrNull() as? PlatformExtractionError
        assertEquals("GRAPHQL_TARGET_WRAPPER_UNSUPPORTED", errWrapper?.internalReason)

        // No direct media
        val noMediaJson = JSONObject("""{"data":{"tweetResult":{"result":{"legacy":{"full_text":"Hi"}}}}}""")
        val resNoMedia = engine.parseGraphQLTweet(noMediaJson, "https://x.com/i/status/4", "4")
        val errNoMedia = resNoMedia.exceptionOrNull() as? PlatformExtractionError
        assertEquals("GRAPHQL_NO_DIRECT_MEDIA", errNoMedia?.internalReason)
    }

    @Test
    fun parseGraphQLTweet_quotedTweetMedia_extractsSuccessfully() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "legacy": {"full_text": "Check out this quoted tweet"},
                    "quoted_status_result": {
                      "result": {
                        "__typename": "Tweet",
                        "legacy": {
                          "extended_entities": {
                            "media": [
                              {
                                "type": "video",
                                "video_info": {
                                  "variants": [
                                    {"content_type": "video/mp4", "url": "https://video.twimg.com/quoted.mp4", "bitrate": 500000}
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
              }
            }
        """.trimIndent())

        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/555", "555")
        assertTrue("Quoted tweet media must be extracted successfully", result.isSuccess)
        val media = result.getOrThrow()
        assertEquals("https://video.twimg.com/quoted.mp4", media.renditions.first().url)
    }

    @Test
    fun parseGraphQLTweet_unifiedCardMedia_extractsSuccessfully() {
        val unifiedCardJsonStr = JSONObject().apply {
            put("media_entities", JSONObject().apply {
                put("card_media_1", JSONObject().apply {
                    put("type", "video")
                    put("video_info", JSONObject().apply {
                        put("variants", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("content_type", "video/mp4")
                                put("url", "https://video.twimg.com/card_video.mp4")
                                put("bitrate", 800000)
                            })
                        })
                    })
                })
            })
        }.toString()

        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "legacy": {"full_text": "Card tweet"},
                    "card": {
                      "legacy": {
                        "binding_values": [
                          {
                            "key": "unified_card",
                            "value": {
                              "string_value": ${JSONObject.quote(unifiedCardJsonStr)}
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

        val result = engine.parseGraphQLTweet(json, "https://x.com/i/status/777", "777")
        assertTrue("Card media must be extracted successfully", result.isSuccess)
        val media = result.getOrThrow()
        assertEquals("https://video.twimg.com/card_video.mp4", media.renditions.first().url)
    }

    @Test
    fun parseHtmlFallback_openGraphVideoFallback_extractsSuccessfully() = runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta property="og:title" content="OpenGraph Video Title" />
              <meta property="og:image" content="https://pbs.twimg.com/media/og_thumb.jpg" />
              <meta property="og:video:url" content="https://video.twimg.com/ext_tw_video/og_stream.mp4" />
            </head>
            <body>
            </body>
            </html>
        """.trimIndent()

        val result = engine.parseHtmlFallback(html, "https://x.com/i/status/999", "999")
        assertTrue("OpenGraph stream should be extracted successfully", result.isSuccess)
        val media = result.getOrThrow()
        assertEquals("OpenGraph Video Title", media.title)
        assertEquals("https://video.twimg.com/ext_tw_video/og_stream.mp4", media.renditions.first().url)
    }

    @Test
    fun parseHtmlFallback_validInitialStateWithoutTargetTweet_returnsTargetNotFoundNotMissing() = runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>window.__INITIAL_STATE__={"entities":{"tweets":{"entities":{"other_id_123":{"full_text":"Other tweet"}}}}};</script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val result = engine.parseHtmlFallback(html, "https://x.com/i/status/999999", "999999")
        assertTrue("Expected failure when statusId not in __INITIAL_STATE__", result.isFailure)
        val err = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(err)
        assertEquals(PlatformErrorCode.TARGET_NOT_IN_PAGE_DATA, err?.code)
        assertEquals("HTML_TARGET_NOT_FOUND", err?.internalReason)
    }

    @Test
    fun parseHtmlFallback_malformedInitialState_returnsParseError() = runBlocking {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <script>window.__INITIAL_STATE__={unclosed json;</script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val result = engine.parseHtmlFallback(html, "https://x.com/i/status/999999", "999999")
        assertTrue("Expected failure on malformed __INITIAL_STATE__", result.isFailure)
        val err = result.exceptionOrNull() as? PlatformExtractionError
        assertNotNull(err)
        assertEquals(PlatformErrorCode.PARSE_ERROR, err?.code)
        assertEquals("HTML_INITIAL_STATE_PARSE_ERROR", err?.internalReason)
    }

    @Test
    fun fetchPostViaGraphQL_403Response_clearsStaleGtCookieAndForceRefreshes() = runBlocking {
        val fakeSession = object : com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession() {
            var graphqlCallCount = 0
            var guestTokenCallCount = 0

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
            ): Result<com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession.HttpResponse> {
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    guestTokenCallCount++
                    return Result.success(com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession.HttpResponse(200, url, "{}", mapOf("x-guest-token" to "new_gt_token_$guestTokenCallCount")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    graphqlCallCount++
                    if (graphqlCallCount == 1) {
                        assertEquals("stale_gt_cookie", customHeaders["x-guest-token"])
                        return Result.success(com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession.HttpResponse(403, url, "{\"errors\":[{\"message\":\"Forbidden\"}]}", emptyMap()))
                    } else {
                        assertEquals("new_gt_token_1", customHeaders["x-guest-token"])
                        return Result.success(com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession.HttpResponse(200, url, "{\"data\":{\"tweetResult\":{\"result\":{\"__typename\":\"Tweet\"}}}}", emptyMap()))
                    }
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val cookie = okhttp3.Cookie.Builder()
            .name("gt")
            .value("stale_gt_cookie")
            .domain("x.com")
            .build()
        fakeSession.cookieJar.putCookie("https://x.com/".toHttpUrl(), cookie)

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val res = testEngine.fetchPostViaGraphQL("123", "dummy_bearer", "stale_gt_cookie")

        assertTrue("Expected second attempt to succeed after token refresh", res.isSuccess)
    }

    @Test
    fun extractMediaInfo_graphql403Retry200_showsSequenceAndAuthRefreshInFingerprint() = runBlocking {
        val fakeSession = object : PlatformHttpSession() {
            var graphqlCallCount = 0
            var guestTokenCallCount = 0

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
                if (url == NativeXEngine.HASHFLAGS_ENDPOINT) {
                    guestTokenCallCount++
                    return Result.success(PlatformHttpSession.HttpResponse(200, url, "{}", mapOf("x-guest-token" to "new_gt_token_$guestTokenCallCount")))
                }
                if (url.startsWith(NativeXEngine.GRAPHQL_ENDPOINT)) {
                    graphqlCallCount++
                    if (graphqlCallCount == 1) {
                        return Result.success(PlatformHttpSession.HttpResponse(403, url, "{\"errors\":[{\"message\":\"Forbidden\"}]}", emptyMap()))
                    } else {
                        val validTweetJson = """
                            {
                              "data": {
                                "tweetResult": {
                                  "result": {
                                    "__typename": "Tweet",
                                    "rest_id": "123",
                                    "legacy": {
                                      "full_text": "Test tweet",
                                      "extended_entities": {
                                        "media": [
                                          {
                                            "type": "video",
                                            "video_info": {
                                              "variants": [
                                                {
                                                  "content_type": "video/mp4",
                                                  "url": "https://video.twimg.com/video.mp4",
                                                  "bitrate": 1000
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
                        """.trimIndent()
                        return Result.success(PlatformHttpSession.HttpResponse(200, url, validTweetJson, mapOf("content-type" to "application/json")))
                    }
                }
                return Result.failure(java.io.IOException("Unknown url $url"))
            }
        }

        val testEngine = NativeXEngine(context = null, httpSession = fakeSession)
        val result = testEngine.extractMediaInfo("https://x.com/i/status/123")
        assertTrue("Extraction should succeed on retry", result.isSuccess)

        val fp = testEngine.lastDiagnosticFingerprint
        assertNotNull(fp)
        assertTrue("Fingerprint must show 403→200 status sequence", fp!!.contains("http_status=403→200"))
        assertTrue("Fingerprint must indicate auth_refresh_attempted=true", fp.contains("auth_refresh_attempted=true"))
        assertTrue("Fingerprint must include content_type", fp.contains("content_type=application/json"))
        assertTrue("Fingerprint must include host_and_path", fp.contains("host_and_path=api.x.com/graphql"))
        assertTrue("Fingerprint must include redirect", fp.contains("redirect=no"))
    }

    @Test
    fun computeGraphQLDiagnostics_tweetWithVisibilityResults_checksUnwrappedTweetForQuotedStatus() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "TweetWithVisibilityResults",
                    "tweet": {
                      "rest_id": "999",
                      "quoted_status_result": {
                        "result": {
                          "__typename": "Tweet"
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "999", retryAttempted = false)
        assertTrue("hasQuotedStatus must be true when quoted_status_result is in unwrappedTweet", diag.hasQuotedStatus)
        assertTrue("targetRestIdPresent must be true", diag.targetRestIdPresent)
        assertEquals("TweetWithVisibilityResults", diag.resultTypename)
    }

    @Test
    fun computeGraphQLDiagnostics_legacyCardUnifiedCard_assertsHasCardAndHasUnifiedCardTrue() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "rest_id": "888777",
                    "legacy": {
                      "card": {
                        "legacy": {
                          "name": "unified_card",
                          "binding_values": [
                            {
                              "key": "unified_card",
                              "value": {
                                "string_value": "{\"media_entities\":{}}"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "888777", retryAttempted = false)
        assertTrue("hasCard must be true for legacy.card", diag.hasCard)
        assertTrue("hasUnifiedCard must be true when legacy.card.legacy contains unified_card", diag.hasUnifiedCard)
    }

    @Test
    fun computeGraphQLDiagnostics_legacyCardNonUnifiedCard_assertsHasCardTrueAndHasUnifiedCardFalse() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "rest_id": "888778",
                    "legacy": {
                      "card": {
                        "legacy": {
                          "name": "summary_large_image",
                          "binding_values": [
                            {
                              "key": "photo_image_full_size",
                              "value": {
                                "string_value": "https://pbs.twimg.com/media/sample.jpg"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "888778", retryAttempted = false)
        assertTrue("hasCard must be true for legacy.card", diag.hasCard)
        assertFalse("hasUnifiedCard must be false when legacy.card is not a unified_card", diag.hasUnifiedCard)
    }

    @Test
    fun parseGraphQLTweet_readsCardFromLegacyCardLegacy_succeeds() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "Tweet",
                    "rest_id": "888779",
                    "legacy": {
                      "full_text": "Tweet with legacy unified card",
                      "card": {
                        "legacy": {
                          "name": "unified_card",
                          "binding_values": [
                            {
                              "key": "unified_card",
                              "value": {
                                "string_value": "{\"media_entities\":{\"m1\":{\"type\":\"video\",\"video_info\":{\"variants\":[{\"content_type\":\"video/mp4\",\"url\":\"https://video.twimg.com/card_vid.mp4\",\"bitrate\":1200}]}}}}"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val result = engine.parseGraphQLTweet(json, "https://x.com/user/status/888779", "888779")
        assertTrue("parseGraphQLTweet must succeed for legacy.card.legacy media", result.isSuccess)
        val media = result.getOrNull()
        assertNotNull(media)
        assertEquals("https://video.twimg.com/card_vid.mp4", media?.renditions?.first()?.url)
    }

    @Test
    fun computeGraphQLDiagnostics_visibilityWrapperWithUnavailableTweet_recordsEffectiveAndProvisionalTypename() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "TweetWithVisibilityResults",
                    "tweet": {
                      "__typename": "TweetUnavailable"
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "111222", retryAttempted = false)
        assertEquals("TweetWithVisibilityResults", diag.outerTypename)
        assertEquals("TweetUnavailable", diag.effectiveTypename)
        assertEquals("tweetResult->TweetWithVisibilityResults->tweet", diag.wrapperChain)
        assertEquals("TweetUnavailable", diag.provisionalTypename)

        val fp = diag.toFingerprint(1)
        assertTrue("Fingerprint must contain outer_typename", fp.contains("outer_typename=TweetWithVisibilityResults"))
        assertTrue("Fingerprint must contain effective_typename", fp.contains("effective_typename=TweetUnavailable"))
        assertTrue("Fingerprint must contain provisional_typename", fp.contains("provisional_typename=TweetUnavailable"))

        val parseResult = engine.parseGraphQLTweet(json, "https://x.com/user/status/111222", "111222")
        assertTrue("Parser must return failure for TweetUnavailable", parseResult.isFailure)
        val err = parseResult.exceptionOrNull()
        assertTrue("Error must be ProvisionalUnavailable", err is PlatformExtractionError.ProvisionalUnavailable)
        assertEquals("TweetUnavailable", (err as PlatformExtractionError.ProvisionalUnavailable).typename)
    }

    @Test
    fun computeGraphQLDiagnostics_visibilityWrapperWithNormalTweet_recordsEffectiveTweetAndProvisionalNone() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "TweetWithVisibilityResults",
                    "tweet": {
                      "__typename": "Tweet",
                      "rest_id": "123",
                      "legacy": {
                        "full_text": "test"
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "123", retryAttempted = false)
        assertEquals("TweetWithVisibilityResults", diag.outerTypename)
        assertEquals("Tweet", diag.effectiveTypename)
        assertEquals("none", diag.provisionalTypename)
        assertTrue("targetRestIdPresent must be true", diag.targetRestIdPresent)

        val fp = diag.toFingerprint(1)
        assertTrue("Fingerprint must contain outer_typename", fp.contains("outer_typename=TweetWithVisibilityResults"))
        assertTrue("Fingerprint must contain effective_typename", fp.contains("effective_typename=Tweet"))
    }

    @Test
    fun computeGraphQLDiagnostics_directTweetTombstone_recordsOuterAndEffectiveTombstone() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "TweetTombstone"
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "333444", retryAttempted = false)
        assertEquals("TweetTombstone", diag.outerTypename)
        assertEquals("TweetTombstone", diag.effectiveTypename)
        assertEquals("TweetTombstone", diag.provisionalTypename)
        assertEquals("tweetResult->TweetTombstone", diag.wrapperChain)

        val fp = diag.toFingerprint(1)
        assertTrue("Fingerprint must contain outer_typename=TweetTombstone", fp.contains("outer_typename=TweetTombstone"))
        assertTrue("Fingerprint must contain effective_typename=TweetTombstone", fp.contains("effective_typename=TweetTombstone"))
        assertTrue("Fingerprint must contain provisional_typename=TweetTombstone", fp.contains("provisional_typename=TweetTombstone"))
    }

    @Test
    fun computeHtmlDiagnostics_fetchFailure_recordsTransportErrorClassName() {
        val failedResp = Result.failure<PlatformHttpSession.HttpResponse>(java.io.IOException("Connection reset"))
        val diag = engine.computeHtmlDiagnostics(failedResp, null, "12345", "https://x.com/user/status/12345")

        assertEquals("IOException", diag.transportError)
        val fp = diag.toFingerprint()
        assertTrue("Fingerprint must contain transport_error=IOException", fp.contains("transport_error=IOException"))
    }

    @Test
    fun computeGraphQLDiagnostics_visibilityWrapperWithoutTweetChild_outerAndEffectiveAreBothTweetWithVisibilityResults() {
        val json = JSONObject("""
            {
              "data": {
                "tweetResult": {
                  "result": {
                    "__typename": "TweetWithVisibilityResults"
                  }
                }
              }
            }
        """.trimIndent())

        val diag = engine.computeGraphQLDiagnostics(json, 200, "555666", retryAttempted = false)
        assertEquals("TweetWithVisibilityResults", diag.outerTypename)
        assertEquals("TweetWithVisibilityResults", diag.effectiveTypename)
        assertEquals("tweetResult->TweetWithVisibilityResults->tweet", diag.wrapperChain)
        assertEquals("none", diag.provisionalTypename)

        val fp = diag.toFingerprint(1)
        assertTrue("Fingerprint must contain outer_typename=TweetWithVisibilityResults", fp.contains("outer_typename=TweetWithVisibilityResults"))
        assertTrue("Fingerprint must contain effective_typename=TweetWithVisibilityResults", fp.contains("effective_typename=TweetWithVisibilityResults"))

        val parseResult = engine.parseGraphQLTweet(json, "https://x.com/user/status/555666", "555666")
        assertTrue("Parser must fail when legacy data is missing from unwrapped TweetWithVisibilityResults", parseResult.isFailure)
        val err = parseResult.exceptionOrNull()
        assertEquals("GRAPHQL_MISSING_LEGACY", (err as? PlatformExtractionError)?.internalReason)
    }
}
