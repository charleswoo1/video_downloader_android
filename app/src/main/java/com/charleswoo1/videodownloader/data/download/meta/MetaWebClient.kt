package com.charleswoo1.videodownloader.data.download.meta

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reusable OkHttp-based local HTTP client for Meta extraction (Instagram and Threads).
 *
 * Implements deterministic timeouts, request profiles, redirect resolution,
 * body size limits, sanitized logging, and direct media stream downloads.
 */
open class MetaWebClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "MetaWebClient"
        const val MAX_BODY_BYTES: Long = 10 * 1024 * 1024 // 10 MB

        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val CRAWLER_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        private val SENSITIVE_PARAM_REGEX = Regex("""(?i)(sessionid|token|key|sig|auth)=[^&]+""")

        fun sanitizeUrl(url: String): String {
            return SENSITIVE_PARAM_REGEX.replace(url, "$1=[REDACTED]")
        }

        fun formatSpeed(bytesPerSec: Float): String {
            return when {
                bytesPerSec >= 1024 * 1024 -> String.format("%.1f MiB/s", bytesPerSec / (1024 * 1024))
                bytesPerSec >= 1024 -> String.format("%.1f KiB/s", bytesPerSec / 1024)
                else -> String.format("%.0f B/s", bytesPerSec)
            }
        }
    }

    enum class RequestProfile {
        BROWSER,
        CRAWLER
    }

    data class HttpResponse(
        val code: Int,
        val finalUrl: String,
        val body: String,
        val headers: Map<String, String>
    )

    open fun fetch(
        url: String,
        profile: RequestProfile = RequestProfile.BROWSER,
        customHeaders: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true
    ): Result<HttpResponse> {
        return try {
            val effectiveClient = if (followRedirects == client.followRedirects) {
                client
            } else {
                client.newBuilder()
                    .followRedirects(followRedirects)
                    .followSslRedirects(followRedirects)
                    .build()
            }

            val requestBuilder = Request.Builder().url(url)

            when (profile) {
                RequestProfile.BROWSER -> {
                    requestBuilder.header("User-Agent", BROWSER_UA)
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    requestBuilder.header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Site", "none")
                    requestBuilder.header("Sec-Fetch-User", "?1")
                    requestBuilder.header("Upgrade-Insecure-Requests", "1")
                }
                RequestProfile.CRAWLER -> {
                    requestBuilder.header("User-Agent", CRAWLER_UA)
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
                }
            }

            customHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

            val call = effectiveClient.newCall(requestBuilder.build())
            call.execute().use { response ->
                val code = response.code
                val finalUrl = response.request.url.toString()
                val responseBody = response.body
                    ?: return Result.failure(IOException("Empty response body (HTTP $code)"))

                val contentLength = responseBody.contentLength()
                if (contentLength > MAX_BODY_BYTES) {
                    return Result.failure(IOException("Response body exceeded sanity limit ($contentLength bytes)"))
                }

                val bodyString = responseBody.string()
                val headersMap = response.headers.names().associateWith { name ->
                    response.header(name) ?: ""
                }

                safeLog(TAG, "Fetched ${sanitizeUrl(url)} -> HTTP $code (${bodyString.length} chars, final: ${sanitizeUrl(finalUrl)})")

                Result.success(HttpResponse(code, finalUrl, bodyString, headersMap))
            }
        } catch (e: Exception) {
            safeLog(TAG, "Request failed for ${sanitizeUrl(url)}: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Resolves redirects for share URLs (e.g. threads.com/share/... or instagram.com/share/...)
     * Returning the final canonical location URL.
     */
    open fun resolveRedirectUrl(url: String, profile: RequestProfile = RequestProfile.CRAWLER): Result<String> {
        val nonRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = url
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount < maxRedirects) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .head()

            when (profile) {
                RequestProfile.BROWSER -> requestBuilder.header("User-Agent", BROWSER_UA)
                RequestProfile.CRAWLER -> requestBuilder.header("User-Agent", CRAWLER_UA)
            }

            try {
                nonRedirectClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location")
                        if (!location.isNullOrBlank()) {
                            val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                            safeLog(TAG, "Redirect $redirectCount: ${sanitizeUrl(currentUrl)} -> ${sanitizeUrl(nextUrl)}")
                            currentUrl = nextUrl
                            redirectCount++
                        } else {
                            return Result.success(currentUrl)
                        }
                    } else {
                        return Result.success(currentUrl)
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.success(currentUrl)
    }

    /**
     * Streams a media file directly to disk with progress callback, speed calculation,
     * cancellation checks, and atomic file renaming upon completion.
     */
    open fun downloadMediaStream(
        streamUrl: String,
        destination: File,
        referer: String? = null,
        onProgress: (Float, Long?, String?) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val partFile = File(destination.parentFile, "${destination.name}.part")
        try {
            val reqBuilder = Request.Builder().url(streamUrl)
            reqBuilder.header("User-Agent", BROWSER_UA)
            reqBuilder.header("Accept", "*/*")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }

            val call = client.newCall(reqBuilder.build())
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    safeLog(TAG, "Stream download failed with HTTP ${response.code} for ${sanitizeUrl(streamUrl)}")
                    return false
                }

                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(partFile)

                val buffer = ByteArray(64 * 1024)
                var bytesDownloaded = 0L
                val startTime = System.currentTimeMillis()

                outputStream.use { out ->
                    inputStream.use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled()) {
                                partFile.delete()
                                return false
                            }
                            out.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000f
                            val progress = if (totalBytes > 0) (bytesDownloaded * 100f / totalBytes) else 0f
                            val speedBytesPerSec = if (elapsedSeconds > 0) (bytesDownloaded / elapsedSeconds) else 0f
                            val speedText = formatSpeed(speedBytesPerSec)
                            val eta = if (speedBytesPerSec > 0 && totalBytes > bytesDownloaded) {
                                ((totalBytes - bytesDownloaded) / speedBytesPerSec).toLong()
                            } else null

                            onProgress(progress, eta, speedText)
                        }
                    }
                }

                if (destination.exists()) destination.delete()
                return partFile.renameTo(destination)
            }
        } catch (e: Exception) {
            partFile.delete()
            if (isCancelled()) return false
            safeLog(TAG, "Exception during media download: ${e.message}")
            return false
        }
    }

    private fun safeLog(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Exception) {
            // JVM unit test fallback
            println("[$tag] $msg")
        }
    }
}
