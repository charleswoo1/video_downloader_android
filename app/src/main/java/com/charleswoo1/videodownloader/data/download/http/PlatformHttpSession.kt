package com.charleswoo1.videodownloader.data.download.http

import android.util.Log
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP and session engine for platform-native extractors.
 *
 * Implements deterministic browser profiles, app-private cookie storage,
 * bounded retries with jitter backoff, streaming downloads with atomic renaming,
 * and strict redaction of sensitive tokens from logs.
 */
open class PlatformHttpSession(
    val cookieJar: PlatformCookieJar = PlatformCookieJar(),
    val sessionProvider: PlatformSessionProvider = AnonymousSessionProvider(),
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    customClientBuilder: (OkHttpClient.Builder.() -> Unit)? = null
) {

    companion object {
        private const val TAG = "PlatformHttpSession"
        const val MAX_BODY_BYTES: Long = 10 * 1024 * 1024 // 10 MB

        private val SIGNED_MEDIA_URL_REGEX = Regex(
            """(https?://[^\s"'<>]+\.(?:mp4|m4a|m3u8|mpd|webm|ts|mov))\?[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        )

        private val SENSITIVE_QUERY_PARAM_REGEX = Regex(
            """(?i)([?&])(stkn|sig|signature|token|guest[_-]?token|gt|sessionid|csrftoken|auth_token|auth|key|secret)=[^&\s"'<>]+"""
        )

        private val REPLACEMENTS = listOf(
            Regex("""(?i)(cookie[s]?|sessionid|csrftoken|auth_token|auth|bearer|token|key|stkn|sig|signature|gt|guest[_-]?token)=[^&\s"'<>]+""") to "$1=[REDACTED]",
            Regex("""(?i)bearer\s+[a-zA-Z0-9_.-]+""") to "Bearer [REDACTED]"
        )

        fun sanitizeLogText(text: String): String {
            var sanitized = text
            // First reduce signed media URLs to host+path?[REDACTED_QUERY]
            sanitized = SIGNED_MEDIA_URL_REGEX.replace(sanitized) { mr ->
                "${mr.groupValues[1]}?[REDACTED_QUERY]"
            }
            // Redact any sensitive query params in other URLs
            sanitized = SENSITIVE_QUERY_PARAM_REGEX.replace(sanitized) { mr ->
                "${mr.groupValues[1]}${mr.groupValues[2]}=[REDACTED]"
            }
            // Redact any remaining sensitive key=value pairs or tokens
            for ((pattern, replacement) in REPLACEMENTS) {
                sanitized = pattern.replace(sanitized, replacement)
            }
            return sanitized
        }

        fun formatSpeed(bytesPerSec: Float): String {
            return when {
                bytesPerSec >= 1024 * 1024 -> String.format("%.1f MiB/s", bytesPerSec / (1024 * 1024))
                bytesPerSec >= 1024 -> String.format("%.1f KiB/s", bytesPerSec / 1024)
                else -> String.format("%.0f B/s", bytesPerSec)
            }
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .apply { customClientBuilder?.invoke(this) }
        .build()

    data class HttpResponse(
        val code: Int,
        val finalUrl: String,
        val body: String,
        val headers: Map<String, String>
    ) {
        fun getHeader(name: String): String? = headers[name]
    }

    open fun fetch(
        url: String,
        profile: RequestProfile = RequestProfile.DESKTOP_NAVIGATION,
        identity: BrowserIdentity = when (profile) {
            RequestProfile.MOBILE_NAVIGATION -> BrowserIdentity.MOBILE
            RequestProfile.CRAWLER_NAVIGATION -> BrowserIdentity.CRAWLER
            else -> BrowserIdentity.DESKTOP
        },
        origin: String? = null,
        referer: String? = null,
        customHeaders: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        body: ByteArray? = null,
        contentType: String? = "application/json",
        method: String = "GET"
    ): Result<HttpResponse> {
        val effectiveClient = if (followRedirects == okHttpClient.followRedirects) {
            okHttpClient
        } else {
            okHttpClient.newBuilder()
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)
                .build()
        }

        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= retryPolicy.maxRetries) {
            try {
                if (attempt > 0) {
                    val delayMs = retryPolicy.computeDelayMs(attempt)
                    Thread.sleep(delayMs)
                }

                val requestBuilder = Request.Builder().url(url)
                val baseHeaders = profile.buildHeaders(identity, origin, referer)
                baseHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
                customHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

                val requestBody = if (body != null) {
                    body.toRequestBody(contentType?.toMediaTypeOrNull())
                } else if (method == "POST") {
                    ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
                } else {
                    null
                }
                requestBuilder.method(method, requestBody)

                effectiveClient.newCall(requestBuilder.build()).execute().use { response ->
                    val code = response.code
                    val finalUrl = response.request.url.toString()

                    // Retry on transient 5xx server errors
                    if (code in 500..599 && attempt < retryPolicy.maxRetries) {
                        attempt++
                        return@use // continue retry loop
                    }

                    val respBody = response.body
                        ?: return Result.failure(IOException("Empty response body (HTTP $code)"))

                    val contentLength = respBody.contentLength()
                    if (contentLength > MAX_BODY_BYTES) {
                        return Result.failure(IOException("Response body exceeded sanity limit ($contentLength bytes)"))
                    }

                    val bodyString = respBody.string()
                    val headersMap = java.util.TreeMap<String, String>(java.lang.String.CASE_INSENSITIVE_ORDER).apply {
                        for (name in response.headers.names()) {
                            put(name, response.header(name) ?: "")
                        }
                    }

                    safeLog("Fetched ${sanitizeLogText(url)} -> HTTP $code (${bodyString.length} chars)")
                    return Result.success(HttpResponse(code, finalUrl, bodyString, headersMap))
                }
            } catch (e: Exception) {
                lastException = e
                safeLog("Attempt $attempt failed for ${sanitizeLogText(url)}: ${e.message}")
                attempt++
            }
        }

        return Result.failure(lastException ?: IOException("Request failed after $attempt attempts"))
    }

    open fun resolveRedirectUrl(
        url: String,
        profile: RequestProfile = RequestProfile.DESKTOP_NAVIGATION,
        identity: BrowserIdentity = BrowserIdentity.DESKTOP,
        maxRedirects: Int = 10
    ): Result<String> {
        val nonRedirectClient = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < maxRedirects) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .head()

            profile.buildHeaders(identity).forEach { (k, v) -> requestBuilder.header(k, v) }

            try {
                nonRedirectClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location")
                        if (!location.isNullOrBlank()) {
                            val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                            safeLog("Redirect $redirectCount: ${sanitizeLogText(currentUrl)} -> ${sanitizeLogText(nextUrl)}")
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

    open fun downloadMediaStream(
        streamUrl: String,
        destination: File,
        referer: String? = null,
        origin: String? = null,
        onProgress: (Float, Long?, String?) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val partFile = File(destination.parentFile, "${destination.name}.part")
        try {
            val reqBuilder = Request.Builder().url(streamUrl)
            val mediaHeaders = RequestProfile.MEDIA.buildHeaders(BrowserIdentity.DESKTOP, origin = origin, referer = referer)
            mediaHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

            val call = okHttpClient.newCall(reqBuilder.build())
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    safeLog("Stream download failed with HTTP ${response.code} for ${sanitizeLogText(streamUrl)}")
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
            safeLog("Exception during media download: ${e.message}")
            return false
        }
    }

    private fun safeLog(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
