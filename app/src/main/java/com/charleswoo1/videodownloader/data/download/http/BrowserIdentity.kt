package com.charleswoo1.videodownloader.data.download.http

/**
 * Coherent browser identity definitions combining User-Agent and Client Hints.
 * Based on 2Xsave/2xsave_common and platform client references.
 */
data class BrowserIdentity(
    val userAgent: String,
    val secChUa: String,
    val secChUaMobile: String,
    val secChUaPlatform: String
) {
    companion object {
        val DESKTOP = BrowserIdentity(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            secChUa = "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\""
        )

        val MOBILE = BrowserIdentity(
            userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            secChUa = "\"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\""
        )

        val CRAWLER = BrowserIdentity(
            userAgent = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            secChUa = "",
            secChUaMobile = "",
            secChUaPlatform = ""
        )
    }
}

/**
 * Request profiles defining coherent HTTP headers for navigation, API, and media requests.
 */
enum class RequestProfile {
    DESKTOP_NAVIGATION,
    MOBILE_NAVIGATION,
    CRAWLER_NAVIGATION,
    API,
    MEDIA;

    fun buildHeaders(
        identity: BrowserIdentity,
        origin: String? = null,
        referer: String? = null,
        language: String = "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7"
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        when (this) {
            DESKTOP_NAVIGATION -> {
                headers["User-Agent"] = identity.userAgent
                if (identity.secChUa.isNotBlank()) {
                    headers["sec-ch-ua"] = identity.secChUa
                    headers["sec-ch-ua-mobile"] = identity.secChUaMobile
                    headers["sec-ch-ua-platform"] = identity.secChUaPlatform
                }
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                headers["Accept-Language"] = language
                headers["sec-fetch-dest"] = "document"
                headers["sec-fetch-mode"] = "navigate"
                headers["sec-fetch-site"] = "none"
                headers["sec-fetch-user"] = "?1"
                headers["upgrade-insecure-requests"] = "1"
                headers["priority"] = "u=0, i"
            }

            MOBILE_NAVIGATION -> {
                val mobileId = if (identity == BrowserIdentity.DESKTOP) BrowserIdentity.MOBILE else identity
                headers["User-Agent"] = mobileId.userAgent
                if (mobileId.secChUa.isNotBlank()) {
                    headers["sec-ch-ua"] = mobileId.secChUa
                    headers["sec-ch-ua-mobile"] = mobileId.secChUaMobile
                    headers["sec-ch-ua-platform"] = mobileId.secChUaPlatform
                }
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                headers["Accept-Language"] = language
                headers["sec-fetch-dest"] = "document"
                headers["sec-fetch-mode"] = "navigate"
                headers["sec-fetch-site"] = "none"
                headers["sec-fetch-user"] = "?1"
                headers["upgrade-insecure-requests"] = "1"
            }

            CRAWLER_NAVIGATION -> {
                headers["User-Agent"] = BrowserIdentity.CRAWLER.userAgent
                headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                headers["Accept-Language"] = "en-US,en;q=0.9"
            }

            API -> {
                headers["User-Agent"] = identity.userAgent
                if (identity.secChUa.isNotBlank()) {
                    headers["sec-ch-ua"] = identity.secChUa
                    headers["sec-ch-ua-mobile"] = identity.secChUaMobile
                    headers["sec-ch-ua-platform"] = identity.secChUaPlatform
                }
                headers["Accept"] = "*/*"
                headers["Accept-Language"] = language
                headers["content-type"] = "application/json"
                headers["sec-fetch-dest"] = "empty"
                headers["sec-fetch-mode"] = "cors"
                headers["sec-fetch-site"] = "same-site"
                headers["priority"] = "u=1, i"
                if (!origin.isNullOrBlank()) headers["origin"] = origin
                if (!referer.isNullOrBlank()) headers["referer"] = referer
            }

            MEDIA -> {
                headers["User-Agent"] = identity.userAgent
                headers["Accept"] = "*/*"
                headers["sec-fetch-dest"] = "empty"
                headers["sec-fetch-mode"] = "cors"
                headers["sec-fetch-site"] = "cross-site"
                headers["priority"] = "u=1, i"
                if (!origin.isNullOrBlank()) headers["origin"] = origin
                if (!referer.isNullOrBlank()) headers["referer"] = referer
            }
        }

        return headers
    }
}
