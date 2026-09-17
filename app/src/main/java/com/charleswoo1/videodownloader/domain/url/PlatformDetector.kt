package com.charleswoo1.videodownloader.domain.url

import com.charleswoo1.videodownloader.domain.model.Platform
import java.net.URI

object PlatformDetector {
    fun detect(url: String?): Platform {
        if (url.isNullOrBlank()) return Platform.GENERIC

        val host = try {
            val uri = URI(url.trim())
            uri.host?.lowercase() ?: ""
        } catch (_: Exception) {
            val withoutScheme = url.substringAfter("://").substringBefore('/')
            withoutScheme.substringBefore(':').lowercase()
        }

        return when {
            host.contains("youtube.com") || host == "youtu.be" -> Platform.YOUTUBE
            host.contains("facebook.com") || host == "fb.watch" || host.endsWith(".facebook.com") || host == "fb.com" -> Platform.FACEBOOK
            host.contains("instagram.com") || host == "instagr.am" -> Platform.INSTAGRAM
            host.contains("threads.net") -> Platform.THREADS
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") || host == "t.co" -> Platform.X
            host.contains("tiktok.com") -> Platform.TIKTOK
            else -> Platform.GENERIC
        }
    }
}
