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
            isDomainOrSubdomain(host, "youtube.com") || isDomainOrSubdomain(host, "youtu.be") -> Platform.YOUTUBE
            isDomainOrSubdomain(host, "facebook.com") || isDomainOrSubdomain(host, "fb.watch") || isDomainOrSubdomain(host, "fb.com") -> Platform.FACEBOOK
            isDomainOrSubdomain(host, "instagram.com") || isDomainOrSubdomain(host, "instagr.am") -> Platform.INSTAGRAM
            isDomainOrSubdomain(host, "threads.net") -> Platform.THREADS
            isDomainOrSubdomain(host, "twitter.com") || isDomainOrSubdomain(host, "x.com") || isDomainOrSubdomain(host, "t.co") -> Platform.X
            isDomainOrSubdomain(host, "tiktok.com") -> Platform.TIKTOK
            else -> Platform.GENERIC
        }
    }

    private fun isDomainOrSubdomain(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }
}
