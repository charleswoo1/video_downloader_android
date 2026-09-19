package com.charleswoo1.videodownloader.data.download.x

import android.content.Context
import android.util.Log
import com.charleswoo1.videodownloader.data.download.PlatformErrorCode
import com.charleswoo1.videodownloader.data.download.PlatformExtractionError
import com.charleswoo1.videodownloader.data.download.PlatformMediaEngine
import com.charleswoo1.videodownloader.data.download.http.BrowserIdentity
import com.charleswoo1.videodownloader.data.download.http.PlatformHttpSession
import com.charleswoo1.videodownloader.data.download.http.RequestProfile
import com.charleswoo1.videodownloader.data.download.meta.MetaFfmpegHelper
import com.charleswoo1.videodownloader.data.download.meta.NativeMediaRendition
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Native X (Twitter) extraction and download engine.
 *
 * Reference implementation ported from 2Xsave/twsave (MIT):
 * - Status ID extraction from x.com / twitter.com URLs
 * - Layered Bearer token discovery (HTML -> JS bundle -> fallback)
 * - Guest token acquisition via cookie or public web client flow
 * - GraphQL TweetResultByRestId query with standard features and variables
 * - Bounded 401/403 token refresh
 * - HTML window.__INITIAL_STATE__ fallback
 * - Explicit NO_VIDEO classification for text-only / photo-only tweets
 */
class NativeXEngine(
    private val context: Context? = null,
    private val httpSession: PlatformHttpSession = PlatformHttpSession()
) : PlatformMediaEngine {

    companion object {
        private const val TAG = "NativeXEngine"
        const val ENGINE_NAME = "NativeXEngine"

        const val FALLBACK_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val GRAPHQL_ENDPOINT =
            "https://api.x.com/graphql/kLXoXTloWpv9d2FSXRg-Tg/TweetResultByRestId"
        const val HASHFLAGS_ENDPOINT =
            "https://api.x.com/1.1/hashflags.json"
        const val TWITTER_HOME_URL =
            "https://x.com"

        private val STATUS_ID_PATTERN = Pattern.compile("""/status/(\d+)""")
        private val BEARER_TOKEN_RE = Pattern.compile("""(?i)["']authorization["']\s*[:=]\s*["']Bearer\s+([^"']+)""")
        private val BEARER_TOKEN_FALLBACK_RE = Pattern.compile("""AAAAAAAAAAAAAAAAAAAAA[A-Za-z0-9%_-]{80,}""")
        private val JS_BUNDLE_RE = Pattern.compile("""https://abs\.twimg\.com/responsive-web/client-web/[^"']+\.js""")
        private val INITIAL_STATE_RE = Pattern.compile("""window\.__INITIAL_STATE__\s*=\s*(\{.+?\});""", Pattern.DOTALL)
        private val OG_VIDEO_URL_PATTERN = Pattern.compile(
            """<meta\b(?=[^>]*\b(?:property|name)=["'](?:og:video:url|og:video:secure_url|og:video|twitter:player:stream)["'])(?=[^>]*\bcontent=["']([^"']+)["'])[^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        private val OG_TITLE_PATTERN = Pattern.compile(
            """<meta\b(?=[^>]*\b(?:property|name)=["'](?:og:title|twitter:title)["'])(?=[^>]*\bcontent=["']([^"']+)["'])[^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        private val OG_IMAGE_PATTERN = Pattern.compile(
            """<meta\b(?=[^>]*\b(?:property|name)=["'](?:og:image|twitter:image)["'])(?=[^>]*\bcontent=["']([^"']+)["'])[^>]*>""",
            Pattern.CASE_INSENSITIVE
        )

        fun unescapeHtml(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
        }

        private val SECURE_RANDOM = SecureRandom()

        fun generateTransactionId(): String {
            val b1 = ByteArray(16).apply { SECURE_RANDOM.nextBytes(this) }
            val part1 = java.util.Base64.getEncoder().withoutPadding().encodeToString(b1)
            val b2 = ByteArray(8).apply { SECURE_RANDOM.nextBytes(this) }
            val part2 = java.util.Base64.getEncoder().withoutPadding().encodeToString(b2)
            return "$part1/$part2"
        }

        fun extractBearerTokenFromHtml(html: String): String? {
            val m1 = BEARER_TOKEN_RE.matcher(html)
            if (m1.find()) {
                val token = m1.group(1)
                if (token != null && token.length > 80) return token
            }
            val m2 = BEARER_TOKEN_FALLBACK_RE.matcher(html)
            if (m2.find()) {
                return m2.group()
            }
            return null
        }

        fun getGraphqlFeatures(): String {
            return JSONObject().apply {
                put("creator_subscriptions_tweet_preview_api_enabled", true)
                put("premium_content_api_read_enabled", false)
                put("communities_web_enable_tweet_community_results_fetch", true)
                put("c9s_tweet_anatomy_moderator_badge_enabled", true)
                put("responsive_web_grok_analyze_button_fetch_trends_enabled", false)
                put("responsive_web_grok_analyze_post_followups_enabled", false)
                put("responsive_web_jetfuel_frame", true)
                put("responsive_web_grok_share_attachment_enabled", true)
                put("articles_preview_enabled", true)
                put("responsive_web_edit_tweet_api_enabled", true)
                put("graphql_is_translatable_rweb_tweet_is_translatable_enabled", true)
                put("view_counts_everywhere_api_enabled", true)
                put("longform_notetweets_consumption_enabled", true)
                put("responsive_web_twitter_article_tweet_consumption_enabled", true)
                put("tweet_awards_web_tipping_enabled", false)
                put("responsive_web_grok_show_grok_translated_post", false)
                put("responsive_web_grok_analysis_button_from_backend", true)
                put("creator_subscriptions_quote_tweet_preview_enabled", false)
                put("freedom_of_speech_not_reach_fetch_enabled", true)
                put("standardized_nudges_misinfo", true)
                put("tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled", true)
                put("longform_notetweets_rich_text_read_enabled", true)
                put("longform_notetweets_inline_media_enabled", true)
                put("profile_label_improvements_pcf_label_in_post_enabled", true)
                put("responsive_web_profile_redirect_enabled", false)
                put("rweb_tipjar_consumption_enabled", true)
                put("verified_phone_label_enabled", false)
                put("responsive_web_grok_image_annotation_enabled", true)
                put("responsive_web_grok_imagine_annotation_enabled", true)
                put("responsive_web_grok_community_note_auto_translation_is_enabled", false)
                put("responsive_web_graphql_skip_user_profile_image_extensions_enabled", false)
                put("responsive_web_graphql_timeline_navigation_enabled", true)
                put("responsive_web_enhance_cards_enabled", false)
            }.toString()
        }
    }

    override val name: String = ENGINE_NAME

    private val isCancelled = AtomicBoolean(false)
    private var cachedBearerToken: String? = null
    private var cachedGuestToken: String? = null

    var lastProfileSequence: List<String> = emptyList()
        private set

    var lastAuthFlow: String = "none"
        private set

    var lastGuestTokenStatus: String = "none"
        private set

    override var lastDiagnosticFingerprint: String? = null
        private set

    override fun supports(platform: Platform): Boolean = platform == Platform.X

    override fun cancelDownload() {
        isCancelled.set(true)
    }

    fun extractStatusId(url: String): String? {
        val clean = url.substringBefore('?').substringBefore('#')
        val matcher = STATUS_ID_PATTERN.matcher(clean)
        return if (matcher.find()) matcher.group(1) else null
    }

    suspend fun ensureBearerToken(): String = withContext(Dispatchers.IO) {
        cachedBearerToken?.let { return@withContext it }

        // 1. Inspect Twitter home HTML
        val homeResp = httpSession.fetch(TWITTER_HOME_URL, RequestProfile.DESKTOP_NAVIGATION)
        val homeHtml = homeResp.getOrNull()?.body ?: ""
        val tokenFromHtml = extractBearerTokenFromHtml(homeHtml)
        if (!tokenFromHtml.isNullOrBlank()) {
            cachedBearerToken = tokenFromHtml
            lastAuthFlow = "html"
            return@withContext tokenFromHtml
        }

        // 2. Inspect responsive-web client JS bundles
        val jsMatcher = JS_BUNDLE_RE.matcher(homeHtml)
        var count = 0
        while (jsMatcher.find() && count < 3) {
            val jsUrl = jsMatcher.group()
            val jsResp = httpSession.fetch(jsUrl, RequestProfile.DESKTOP_NAVIGATION)
            val jsBody = jsResp.getOrNull()?.body ?: ""
            val tokenFromJs = extractBearerTokenFromHtml(jsBody)
            if (!tokenFromJs.isNullOrBlank()) {
                cachedBearerToken = tokenFromJs
                lastAuthFlow = "bundle"
                return@withContext tokenFromJs
            }
            count++
        }

        // 3. Fallback token
        cachedBearerToken = FALLBACK_BEARER_TOKEN
        lastAuthFlow = "fallback"
        FALLBACK_BEARER_TOKEN
    }

    suspend fun ensureGuestToken(bearerToken: String, forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            cachedGuestToken = null
            httpSession.cookieJar.removeCookie("x.com", "gt")
            httpSession.cookieJar.removeCookie("twitter.com", "gt")
        } else {
            cachedGuestToken?.let {
                lastGuestTokenStatus = "cached"
                return@withContext it
            }

            // 1. Check cookie jar first
            val cookieGt = httpSession.cookieJar.getCookieValue("x.com", "gt")
            if (!cookieGt.isNullOrBlank()) {
                cachedGuestToken = cookieGt
                lastGuestTokenStatus = "cached"
                return@withContext cookieGt
            }
        }

        // 2. Request hashflags endpoint with Bearer, transaction id, and active user/language headers
        val apiHeaders = mutableMapOf(
            "Authorization" to "Bearer $bearerToken",
            "x-client-transaction-id" to generateTransactionId(),
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "zh-tw"
        )
        val hashResp = httpSession.fetch(
            url = HASHFLAGS_ENDPOINT,
            profile = RequestProfile.API,
            origin = "https://x.com",
            referer = "https://x.com/",
            customHeaders = apiHeaders
        )

        // Check response header x-guest-token (case-insensitive)
        val headerGt = hashResp.getOrNull()?.getHeader("x-guest-token")
        if (!headerGt.isNullOrBlank()) {
            cachedGuestToken = headerGt
            lastGuestTokenStatus = "acquired"
            return@withContext headerGt
        }

        // Re-check cookie jar after request
        val postCookieGt = httpSession.cookieJar.getCookieValue("x.com", "gt")
        if (!postCookieGt.isNullOrBlank()) {
            cachedGuestToken = postCookieGt
            lastGuestTokenStatus = "acquired"
            return@withContext postCookieGt
        }

        // 3. Final authenticated x.com navigation fallback used by twsave
        val navAuthHeaders = mutableMapOf(
            "Authorization" to "Bearer $bearerToken",
            "x-twitter-active-user" to "yes"
        )
        val navResp = httpSession.fetch(
            url = TWITTER_HOME_URL,
            profile = RequestProfile.DESKTOP_NAVIGATION,
            origin = "https://x.com",
            referer = "https://x.com/",
            customHeaders = navAuthHeaders
        )

        val navCookieGt = httpSession.cookieJar.getCookieValue("x.com", "gt")
        if (!navCookieGt.isNullOrBlank()) {
            cachedGuestToken = navCookieGt
            lastGuestTokenStatus = "acquired"
            return@withContext navCookieGt
        }

        val navHeaderGt = navResp.getOrNull()?.getHeader("x-guest-token")
        if (!navHeaderGt.isNullOrBlank()) {
            cachedGuestToken = navHeaderGt
            lastGuestTokenStatus = "acquired"
            return@withContext navHeaderGt
        }

        lastGuestTokenStatus = "failed"
        null
    }

    suspend fun fetchPostViaGraphQL(tweetId: String, bearerToken: String, guestToken: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val variables = JSONObject().apply {
            put("tweetId", tweetId)
            put("includePromotedContent", true)
            put("withBirdwatchNotes", true)
            put("withVoice", true)
            put("withCommunity", true)
        }.toString()

        val fieldToggles = JSONObject().apply {
            put("withArticleRichContentState", true)
            put("withArticlePlainText", false)
        }.toString()

        val queryParams = "variables=${URLEncoder.encode(variables, "UTF-8")}" +
                "&features=${URLEncoder.encode(getGraphqlFeatures(), "UTF-8")}" +
                "&fieldToggles=${URLEncoder.encode(fieldToggles, "UTF-8")}"

        val url = "$GRAPHQL_ENDPOINT?$queryParams"

        val headers = mutableMapOf(
            "Authorization" to "Bearer $bearerToken",
            "x-client-transaction-id" to generateTransactionId(),
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "zh-tw"
        )
        if (!guestToken.isNullOrBlank()) {
            headers["x-guest-token"] = guestToken
        }

        val respResult = httpSession.fetch(
            url = url,
            profile = RequestProfile.API,
            origin = "https://x.com",
            referer = "https://x.com/",
            customHeaders = headers
        )

        val resp = respResult.getOrElse { return@withContext Result.failure(it) }

        if (resp.code == 401 || resp.code == 403) {
            // Token expired or invalid: refresh bearer and guest token, retry once
            cachedBearerToken = null
            cachedGuestToken = null
            val newBearer = ensureBearerToken()
            val newGuest = ensureGuestToken(newBearer)
            val retryHeaders = mutableMapOf(
                "Authorization" to "Bearer $newBearer",
                "x-client-transaction-id" to generateTransactionId(),
                "x-twitter-active-user" to "yes",
                "x-twitter-client-language" to "zh-tw"
            )
            if (!newGuest.isNullOrBlank()) {
                retryHeaders["x-guest-token"] = newGuest
            }

            val retryResult = httpSession.fetch(
                url = url,
                profile = RequestProfile.API,
                origin = "https://x.com",
                referer = "https://x.com/",
                customHeaders = retryHeaders
            )
            val retryResp = retryResult.getOrElse { return@withContext Result.failure(it) }
            if (retryResp.code !in 200..299) {
                return@withContext Result.failure(
                    PlatformExtractionError.ApiError(retryResp.code, "X GraphQL API returned error code ${retryResp.code}")
                )
            }
            return@withContext parseJsonResult(retryResp.body)
        }

        if (resp.code !in 200..299) {
            return@withContext Result.failure(
                PlatformExtractionError.ApiError(resp.code, "X GraphQL API returned error code ${resp.code}")
            )
        }

        parseJsonResult(resp.body)
    }

    private fun parseJsonResult(body: String): Result<JSONObject> {
        return try {
            val json = JSONObject(body)
            Result.success(json)
        } catch (e: Exception) {
            Result.failure(PlatformExtractionError.ParseError("無法解析 X GraphQL 回應內容", cause = e))
        }
    }

    private fun extractMediaArrayRenditions(mediaArray: JSONArray?): Pair<List<NativeMediaRendition>, Pair<Boolean, String?>> {
        if (mediaArray == null || mediaArray.length() == 0) return Pair(emptyList(), Pair(false, null))
        val renditions = mutableListOf<NativeMediaRendition>()
        var isGif = false
        var thumb: String? = null
        for (i in 0 until mediaArray.length()) {
            val media = mediaArray.optJSONObject(i) ?: continue
            val type = media.optString("type")
            if (type == "video" || type == "animated_gif") {
                isGif = (type == "animated_gif")
                if (thumb == null) {
                    thumb = media.optString("media_url_https").ifBlank { media.optString("media_url") }
                }
                val videoInfo = media.optJSONObject("video_info")
                val variants = videoInfo?.optJSONArray("variants")
                if (variants != null) {
                    for (vIdx in 0 until variants.length()) {
                        val variant = variants.optJSONObject(vIdx) ?: continue
                        val contentType = variant.optString("content_type")
                        val rawUrl = variant.optString("url")
                        val bitrate = variant.optInt("bitrate", 0)
                        if (contentType.startsWith("video/") && rawUrl.isNotBlank()) {
                            renditions.add(NativeMediaRendition(url = rawUrl, width = bitrate, height = 0))
                        }
                    }
                }
            }
        }
        return Pair(renditions, Pair(isGif, thumb))
    }

    private fun extractUnifiedCardMedia(cardLegacy: JSONObject?): Pair<List<NativeMediaRendition>, Pair<Boolean, String?>> {
        if (cardLegacy == null) return Pair(emptyList(), Pair(false, null))
        val bindingValues = cardLegacy.opt("binding_values") ?: return Pair(emptyList(), Pair(false, null))
        var unifiedCardString: String? = null
        if (bindingValues is JSONArray) {
            for (i in 0 until bindingValues.length()) {
                val item = bindingValues.optJSONObject(i) ?: continue
                if (item.optString("key") == "unified_card") {
                    unifiedCardString = item.optJSONObject("value")?.optString("string_value")
                    break
                }
            }
        } else if (bindingValues is JSONObject) {
            unifiedCardString = bindingValues.optJSONObject("unified_card")?.optString("string_value")
        }
        if (unifiedCardString.isNullOrBlank()) return Pair(emptyList(), Pair(false, null))
        return try {
            val uCardJson = JSONObject(unifiedCardString)
            val mediaEntities = uCardJson.optJSONObject("media_entities")
            if (mediaEntities != null) {
                val mediaArr = JSONArray()
                val keys = mediaEntities.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val m = mediaEntities.optJSONObject(k)
                    if (m != null) mediaArr.put(m)
                }
                extractMediaArrayRenditions(mediaArr)
            } else {
                Pair(emptyList(), Pair(false, null))
            }
        } catch (_: Exception) {
            Pair(emptyList(), Pair(false, null))
        }
    }

    private fun hasAnyVideoType(mediaArray: JSONArray?): Boolean {
        if (mediaArray == null) return false
        for (i in 0 until mediaArray.length()) {
            val media = mediaArray.optJSONObject(i) ?: continue
            val type = media.optString("type")
            if (type == "video" || type == "animated_gif") return true
        }
        return false
    }

    fun parseGraphQLTweet(data: JSONObject, pageUrl: String, statusId: String): Result<ExtractedXMedia> {
        val tweetResult = data.optJSONObject("data")?.optJSONObject("tweetResult")
            ?: return Result.failure(PlatformExtractionError.ParseError("GraphQL 回應遺漏 tweetResult 物件", internalReason = "GRAPHQL_TARGET_WRAPPER_UNSUPPORTED"))

        var resultObj = tweetResult.optJSONObject("result")
            ?: return Result.failure(PlatformExtractionError.ParseError("GraphQL 回應遺漏 result 物件", internalReason = "GRAPHQL_TARGET_WRAPPER_UNSUPPORTED"))

        if (resultObj.optString("__typename") == "TweetWithVisibilityResults") {
            resultObj = resultObj.optJSONObject("tweet") ?: resultObj
        }

        // Check if tweet was removed, suspended, or not found
        val typename = resultObj.optString("__typename")
        if (typename == "TweetUnavailable" || typename == "TweetTombstone") {
            return Result.failure(PlatformExtractionError.ProvisionalUnavailable(typename, internalReason = "GRAPHQL_PROVISIONAL_UNAVAILABLE:$typename"))
        }

        val legacy = resultObj.optJSONObject("legacy")
            ?: resultObj.optJSONObject("tweet")?.optJSONObject("legacy")
            ?: return Result.failure(PlatformExtractionError.ParseError("找不到貼文 legacy 資料", internalReason = "GRAPHQL_MISSING_LEGACY"))

        val text = legacy.optString("full_text").ifBlank { legacy.optString("text") }
        val userObj = resultObj.optJSONObject("core")?.optJSONObject("user_results")?.optJSONObject("result")?.optJSONObject("legacy")
            ?: legacy.optJSONObject("user")
        val author = userObj?.optString("screen_name") ?: "Twitter User"

        var mediaArray = legacy.optJSONObject("extended_entities")?.optJSONArray("media")

        // Quoted tweet media
        if (mediaArray == null || mediaArray.length() == 0) {
            val quotedResult = resultObj.optJSONObject("quoted_status_result")?.optJSONObject("result")
                ?: legacy.optJSONObject("quoted_status_result")?.optJSONObject("result")
            val unwrappedQuoted = if (quotedResult?.optString("__typename") == "TweetWithVisibilityResults") {
                quotedResult.optJSONObject("tweet") ?: quotedResult
            } else quotedResult
            val quotedMedia = unwrappedQuoted?.optJSONObject("legacy")?.optJSONObject("extended_entities")?.optJSONArray("media")
            if (quotedMedia != null && quotedMedia.length() > 0) {
                mediaArray = quotedMedia
            }
        }

        // Retweeted tweet media
        if (mediaArray == null || mediaArray.length() == 0) {
            val retweetedResult = legacy.optJSONObject("retweeted_status_result")?.optJSONObject("result")
            val unwrappedRetweeted = if (retweetedResult?.optString("__typename") == "TweetWithVisibilityResults") {
                retweetedResult.optJSONObject("tweet") ?: retweetedResult
            } else retweetedResult
            val retweetedMedia = unwrappedRetweeted?.optJSONObject("legacy")?.optJSONObject("extended_entities")?.optJSONArray("media")
            if (retweetedMedia != null && retweetedMedia.length() > 0) {
                mediaArray = retweetedMedia
            }
        }

        // Note tweet media
        if (mediaArray == null || mediaArray.length() == 0) {
            val noteTweetMedia = resultObj.optJSONObject("note_tweet")?.optJSONObject("note_tweet_results")?.optJSONObject("result")?.optJSONObject("media")?.optJSONArray("inline_media")
            if (noteTweetMedia != null && noteTweetMedia.length() > 0) {
                mediaArray = noteTweetMedia
            }
        }

        val renditions = mutableListOf<NativeMediaRendition>()
        var isGif = false
        var thumbnailUrl: String? = null
        var hasExplicitVideoMedia = false

        if (mediaArray != null && mediaArray.length() > 0) {
            val (extractedRenditions, extra) = extractMediaArrayRenditions(mediaArray)
            renditions.addAll(extractedRenditions)
            isGif = extra.first
            thumbnailUrl = extra.second
            if (hasAnyVideoType(mediaArray)) {
                hasExplicitVideoMedia = true
            }
        }

        // Card / unified card media
        if (renditions.isEmpty()) {
            val card = resultObj.optJSONObject("card")?.optJSONObject("legacy")
                ?: legacy.optJSONObject("card")?.optJSONObject("legacy")
            if (card != null) {
                val (cardRenditions, extra) = extractUnifiedCardMedia(card)
                if (cardRenditions.isNotEmpty()) {
                    renditions.addAll(cardRenditions)
                    isGif = extra.first
                    if (thumbnailUrl == null) thumbnailUrl = extra.second
                    hasExplicitVideoMedia = true
                }
            }
        }

        if (renditions.isEmpty()) {
            if (hasExplicitVideoMedia) {
                return Result.failure(
                    PlatformExtractionError.MediaUrlUnsupported(
                        "此 X (Twitter) 貼文包含影片媒體，但未解析出相容的下載串流格式",
                        internalReason = "GRAPHQL_MEDIA_VARIANT_UNSUPPORTED"
                    )
                )
            }
            // Target tweet has no video (e.g. photo-only or text-only)
            return Result.failure(
                PlatformExtractionError.NoVideo(
                    "此 X (Twitter) 貼文未包含可下載的影片內容（可能為純文字或純圖片）",
                    internalReason = "GRAPHQL_NO_DIRECT_MEDIA"
                )
            )
        }

        // Sort by bitrate descending
        val sortedRenditions = renditions.sortedByDescending { it.width }
        val title = if (text.isNotBlank()) text.take(100) else "X 影片 ($statusId)"

        return Result.success(
            ExtractedXMedia(
                statusId = statusId,
                pageUrl = pageUrl,
                title = title,
                author = author,
                thumbnailUrl = thumbnailUrl,
                renditions = sortedRenditions,
                isGif = isGif
            )
        )
    }

    fun extractOpenGraphVideo(html: String, pageUrl: String, statusId: String): ExtractedXMedia? {
        val videoMatcher = OG_VIDEO_URL_PATTERN.matcher(html)
        var streamUrl: String? = null
        while (videoMatcher.find()) {
            val raw = videoMatcher.group(1)?.trim() ?: continue
            val unescaped = unescapeHtml(raw)
            if (unescaped.startsWith("http") && (unescaped.contains("video.twimg.com") || unescaped.contains("twimg.com") || unescaped.contains(".mp4"))) {
                streamUrl = unescaped
                break
            }
        }
        if (streamUrl == null) return null

        var title = "X 影片 ($statusId)"
        val titleMatcher = OG_TITLE_PATTERN.matcher(html)
        if (titleMatcher.find()) {
            val t = titleMatcher.group(1)?.trim()
            if (!t.isNullOrBlank()) title = unescapeHtml(t)
        }

        var thumb: String? = null
        val imageMatcher = OG_IMAGE_PATTERN.matcher(html)
        if (imageMatcher.find()) {
            val img = imageMatcher.group(1)?.trim()
            if (!img.isNullOrBlank()) thumb = unescapeHtml(img)
        }

        return ExtractedXMedia(
            statusId = statusId,
            pageUrl = pageUrl,
            title = title,
            author = "Twitter User",
            thumbnailUrl = thumb,
            renditions = listOf(NativeMediaRendition(url = streamUrl, width = 0, height = 0)),
            isGif = false
        )
    }

    suspend fun parseHtmlFallback(html: String, pageUrl: String, statusId: String): Result<ExtractedXMedia> = withContext(Dispatchers.IO) {
        // Corroborate explicit deleted/private tombstone in public HTML
        if (html.contains("This Post was deleted by the Post author", ignoreCase = true) ||
            html.contains("This account’s posts are protected", ignoreCase = true) ||
            html.contains("This account's posts are protected", ignoreCase = true) ||
            html.contains("This Post is from a suspended account", ignoreCase = true) ||
            html.contains("Hmm...this page doesn’t exist", ignoreCase = true)
        ) {
            return@withContext Result.failure(PlatformExtractionError.DeletedOrNotFound("此 X 貼文已被作者刪除或原始內容已不存在", internalReason = "HTML_TOMBSTONE"))
        }

        val matcher = INITIAL_STATE_RE.matcher(html)
        if (matcher.find()) {
            val rawJson = matcher.group(1) ?: ""
            try {
                val state = JSONObject(rawJson)
                val tweetEntities = state.optJSONObject("entities")?.optJSONObject("tweets")?.optJSONObject("entities")
                val tweetData = tweetEntities?.optJSONObject(statusId)
                if (tweetData != null) {
                    val text = tweetData.optString("full_text").ifBlank { tweetData.optString("text") }
                    val mediaArray = tweetData.optJSONObject("extended_entities")?.optJSONArray("media")

                    val renditions = mutableListOf<NativeMediaRendition>()
                    var isGif = false
                    var thumb: String? = null
                    var hasExplicitVideoMedia = false

                    if (mediaArray != null) {
                        val (extractedRenditions, extra) = extractMediaArrayRenditions(mediaArray)
                        renditions.addAll(extractedRenditions)
                        isGif = extra.first
                        thumb = extra.second
                        if (hasAnyVideoType(mediaArray)) hasExplicitVideoMedia = true
                    }

                    if (renditions.isNotEmpty()) {
                        val sorted = renditions.sortedByDescending { it.width }
                        val title = if (text.isNotBlank()) text.take(100) else "X 影片 ($statusId)"
                        return@withContext Result.success(
                            ExtractedXMedia(
                                statusId = statusId,
                                pageUrl = pageUrl,
                                title = title,
                                author = "Twitter User",
                                thumbnailUrl = thumb,
                                renditions = sorted,
                                isGif = isGif
                            )
                        )
                    }

                    if (hasExplicitVideoMedia) {
                        return@withContext Result.failure(
                            PlatformExtractionError.MediaUrlUnsupported("HTML 狀態資料中包含影片媒體，但未解析出相容的下載串流格式", internalReason = "HTML_MEDIA_VARIANT_UNSUPPORTED")
                        )
                    }
                    return@withContext Result.failure(
                        PlatformExtractionError.NoVideo("此 X (Twitter) 貼文未包含可下載的影片內容（可能為純文字或純圖片）", internalReason = "HTML_NO_DIRECT_MEDIA")
                    )
                }
            } catch (_: Exception) {}
        }

        // OpenGraph HTML stream fallback
        val ogVideo = extractOpenGraphVideo(html, pageUrl, statusId)
        if (ogVideo != null) {
            return@withContext Result.success(ogVideo)
        }

        if (matcher.find()) {
            return@withContext Result.failure(PlatformExtractionError.TargetNotInPageData("HTML 狀態資料中找不到貼文 $statusId", internalReason = "HTML_TARGET_NOT_FOUND"))
        }

        Result.failure(PlatformExtractionError.ParseError("HTML 備援頁面未包含 __INITIAL_STATE__", internalReason = "HTML_INITIAL_STATE_MISSING"))
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val statusId = extractStatusId(url)
            ?: return@withContext Result.failure(
                PlatformExtractionError.PageVariantUnsupported("無法從網址中識別 X (Twitter) 貼文代碼", internalReason = "PAGE_VARIANT_UNSUPPORTED")
            )

        val cleanUrl = "https://x.com/i/status/$statusId"
        val profileSteps = mutableListOf<String>()

        profileSteps.add("GRAPHQL")
        safeLog("[Resolver] platform=X")

        val bearer = ensureBearerToken()
        val guest = ensureGuestToken(bearer)
        val guestTokenPresent = !guest.isNullOrBlank()

        var extractedMedia: ExtractedXMedia? = null
        var lastError: PlatformExtractionError? = null
        var lastTypename: String = "none"
        var restIdStatus: String = "not_attempted"
        var htmlFallbackUsed = false
        var internalReason: String = "none"

        safeLog("[X] graphql_attempt=1")
        val gqlResult = fetchPostViaGraphQL(statusId, bearer, guest)
        if (gqlResult.isSuccess) {
            val json = gqlResult.getOrThrow()
            val resultObj = json.optJSONObject("data")?.optJSONObject("tweetResult")?.optJSONObject("result")
            lastTypename = resultObj?.optString("__typename")?.ifBlank { "none" } ?: "none"
            val parseResult = parseGraphQLTweet(json, cleanUrl, statusId)
            if (parseResult.isSuccess) {
                safeLog("[X] graphql_attempt=1 result=SUCCESS")
                extractedMedia = parseResult.getOrThrow()
                restIdStatus = "success"
            } else {
                restIdStatus = "fail"
                val err = parseResult.exceptionOrNull() as? PlatformExtractionError
                internalReason = err?.internalReason ?: "none"
                if (err is PlatformExtractionError.ProvisionalUnavailable) {
                    safeLog("[X] graphql_attempt=1 typename=${err.typename}")
                    safeLog("[X] guest_refresh=true")
                    cachedGuestToken = null
                    httpSession.cookieJar.removeCookie("x.com", "gt")
                    httpSession.cookieJar.removeCookie("twitter.com", "gt")
                    val newBearer = ensureBearerToken()
                    val newGuest = ensureGuestToken(newBearer, forceRefresh = true)
                    safeLog("[X] graphql_attempt=2")
                    val retryGql = fetchPostViaGraphQL(statusId, newBearer, newGuest)
                    if (retryGql.isSuccess) {
                        val retryJson = retryGql.getOrThrow()
                        val retryResultObj = retryJson.optJSONObject("data")?.optJSONObject("tweetResult")?.optJSONObject("result")
                        lastTypename = retryResultObj?.optString("__typename")?.ifBlank { "none" } ?: "none"
                        val retryParse = parseGraphQLTweet(retryJson, cleanUrl, statusId)
                        if (retryParse.isSuccess) {
                            safeLog("[X] graphql_attempt=2 result=SUCCESS")
                            extractedMedia = retryParse.getOrThrow()
                            restIdStatus = "success"
                            internalReason = "none"
                        } else {
                            val retryErr = retryParse.exceptionOrNull() as? PlatformExtractionError
                            internalReason = retryErr?.internalReason ?: internalReason
                            safeLog("[X] graphql_attempt=2 result=${retryErr?.code?.name ?: "FAILURE"}")
                            lastError = retryErr
                        }
                    } else {
                        val retryErr = retryGql.exceptionOrNull() as? PlatformExtractionError
                        internalReason = retryErr?.internalReason ?: internalReason
                        safeLog("[X] graphql_attempt=2 result=${retryErr?.code?.name ?: "FAILURE"}")
                        lastError = retryErr
                    }
                } else if (err != null && !err.canFallback) {
                    lastProfileSequence = profileSteps
                    val fp = "X: rest_id_present=true typename=$lastTypename guest_token_present=$guestTokenPresent auth_flow=$lastAuthFlow stream_url_present=false internal_reason=$internalReason"
                    lastDiagnosticFingerprint = fp
                    safeLog("platform=X status_id_present=true auth_flow=$lastAuthFlow guest_token=$lastGuestTokenStatus rest_id_status=$restIdStatus html_fallback_used=false final=${err.code.name} internal_reason=$internalReason")
                    safeLog("[X] final=${err.code.name}")
                    return@withContext Result.failure(err)
                } else {
                    lastError = err
                }
            }
        } else {
            restIdStatus = "fail"
            val err = gqlResult.exceptionOrNull() as? PlatformExtractionError
            internalReason = err?.internalReason ?: "GRAPHQL_FETCH_FAILED"
            lastError = err
        }

        // HTML fallback if GraphQL did not resolve media
        if (extractedMedia == null) {
            htmlFallbackUsed = true
            profileSteps.add("HTML_FALLBACK")
            safeLog("[X] html_fallback=true")
            val htmlResp = httpSession.fetch(cleanUrl, RequestProfile.DESKTOP_NAVIGATION)
            val html = htmlResp.getOrNull()?.body ?: ""
            if (html.isNotBlank()) {
                val htmlResult = parseHtmlFallback(html, cleanUrl, statusId)
                if (htmlResult.isSuccess) {
                    extractedMedia = htmlResult.getOrThrow()
                    internalReason = if (extractedMedia.renditions.firstOrNull()?.width == 0) "OPENGRAPH_FALLBACK_SUCCESS" else "none"
                } else {
                    val err = htmlResult.exceptionOrNull() as? PlatformExtractionError
                    internalReason = err?.internalReason ?: internalReason
                    if (err != null && !err.canFallback) {
                        lastProfileSequence = profileSteps
                        val fp = "X: rest_id_present=${restIdStatus != "not_attempted"} typename=$lastTypename guest_token_present=$guestTokenPresent auth_flow=$lastAuthFlow stream_url_present=false internal_reason=$internalReason"
                        lastDiagnosticFingerprint = fp
                        safeLog("platform=X status_id_present=true auth_flow=$lastAuthFlow guest_token=$lastGuestTokenStatus rest_id_status=$restIdStatus html_fallback_used=true final=${err.code.name} internal_reason=$internalReason")
                        safeLog("[X] final=${err.code.name}")
                        return@withContext Result.failure(err)
                    }
                    if (lastError == null) lastError = err
                }
            }
        }

        lastProfileSequence = profileSteps

        val streamUrlPresent = extractedMedia?.renditions?.isNotEmpty() == true
        val fp = "X: rest_id_present=${restIdStatus != "not_attempted"} typename=$lastTypename guest_token_present=$guestTokenPresent auth_flow=$lastAuthFlow stream_url_present=$streamUrlPresent internal_reason=$internalReason"
        lastDiagnosticFingerprint = fp

        if (extractedMedia == null) {
            val finalErr = lastError ?: PlatformExtractionError.ParseError("無法解析 X 貼文影片資訊", internalReason = internalReason)
            safeLog("platform=X status_id_present=true auth_flow=$lastAuthFlow guest_token=$lastGuestTokenStatus rest_id_status=$restIdStatus html_fallback_used=$htmlFallbackUsed final=${finalErr.code.name} internal_reason=$internalReason")
            safeLog("[X] final=${finalErr.code.name}")
            return@withContext Result.failure(finalErr)
        }

        safeLog("platform=X status_id_present=true auth_flow=$lastAuthFlow guest_token=$lastGuestTokenStatus rest_id_status=$restIdStatus html_fallback_used=$htmlFallbackUsed final=SUCCESS internal_reason=$internalReason")
        safeLog("[X] final=SUCCESS")
        val qualityOptions = buildQualityOptions(extractedMedia)
        val info = MediaInfo(
            sourceUrl = cleanUrl,
            title = extractedMedia.title,
            platform = Platform.X,
            extractor = ENGINE_NAME,
            thumbnailUrl = extractedMedia.thumbnailUrl,
            durationSeconds = null,
            qualityOptions = qualityOptions
        )

        Result.success(info)
    }

    private fun safeLog(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }

    private fun buildQualityOptions(media: ExtractedXMedia): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        val sorted = media.renditions.sortedByDescending { it.width }

        val best = sorted.firstOrNull()
        if (best != null) {
            options.add(
                QualityOption(
                    id = "best",
                    label = "最佳畫質 (推薦)",
                    formatSelector = best.url
                )
            )
        }

        // Additional bitrate variants if available
        sorted.drop(1).take(2).forEachIndexed { idx, r ->
            val bitrateKbps = r.width / 1000
            val label = if (bitrateKbps > 0) "${bitrateKbps} kbps" else "標準畫質"
            options.add(QualityOption(id = "variant_$idx", label = label, formatSelector = r.url))
        }

        val distinct = options.distinctBy { it.id }.toMutableList()

        if (best != null) {
            distinct.add(
                QualityOption(
                    id = "audio_only",
                    label = "僅下載音訊 (MP3)",
                    formatSelector = "audio:${best.url}",
                    isAudioOnly = true
                )
            )
        }

        return distinct
    }

    override suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val format = request.qualityOption.formatSelector
        val isAudioOnly = request.qualityOption.isAudioOnly || format.startsWith("audio:")
        val streamUrl = format.removePrefix("audio:")

        val sanitizedTitle = request.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        val finalFileName = if (isAudioOnly) "$sanitizedTitle.mp3" else "$sanitizedTitle.mp4"
        val destinationFile = File(destDir, finalFileName)

        onStatus("正在透過原生 X 引擎下載串流...")

        val downloadTarget = if (isAudioOnly) {
            File(destDir, "${sanitizedTitle}_temp_${UUID.randomUUID().toString().take(6)}.mp4")
        } else {
            destinationFile
        }

        val success = httpSession.downloadMediaStream(
            streamUrl = streamUrl,
            destination = downloadTarget,
            referer = "https://x.com/",
            origin = "https://x.com",
            onProgress = onProgress,
            isCancelled = { isCancelled.get() }
        )

        if (!success) {
            if (isCancelled.get()) {
                downloadTarget.delete()
                return@withContext Result.failure(InterruptedException("下載已被使用者取消"))
            }
            return@withContext Result.failure(PlatformExtractionError.NetworkError("X 串流下載失敗"))
        }

        if (isAudioOnly) {
            onStatus("正在轉檔音訊為 MP3...")
            val converted = MetaFfmpegHelper.extractAudio(context, downloadTarget, destinationFile)
            downloadTarget.delete()
            if (!converted) {
                return@withContext Result.failure(PlatformExtractionError.FfmpegError("FFmpeg 音訊轉檔失敗"))
            }
        }

        Result.success(destinationFile)
    }
}

data class ExtractedXMedia(
    val statusId: String,
    val pageUrl: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val renditions: List<NativeMediaRendition>,
    val isGif: Boolean = false
)
