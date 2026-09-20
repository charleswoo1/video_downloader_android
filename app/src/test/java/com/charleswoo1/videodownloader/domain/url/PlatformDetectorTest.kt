package com.charleswoo1.videodownloader.domain.url

import com.charleswoo1.videodownloader.domain.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformDetectorTest {

    @Test
    fun detect_youtubeUrls() {
        assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://m.youtube.com/shorts/abc123xyz"))
        assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://music.youtube.com/watch?v=abc"))
    }

    @Test
    fun detect_facebookUrls() {
        assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://www.facebook.com/watch/?v=123456789"))
        assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://fb.watch/xyz123/"))
        assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://m.facebook.com/story.php?story_fbid=123&id=456"))
    }

    @Test
    fun detect_instagramUrls() {
        assertEquals(Platform.INSTAGRAM, PlatformDetector.detect("https://www.instagram.com/reel/C123abc/"))
        assertEquals(Platform.INSTAGRAM, PlatformDetector.detect("https://instagr.am/p/C123abc/"))
    }

    @Test
    fun detect_threadsUrls() {
        assertEquals(Platform.THREADS, PlatformDetector.detect("https://www.threads.net/@user/post/C123abc"))
        assertEquals(Platform.THREADS, PlatformDetector.detect("https://threads.net/@user/post/ABC123"))
        assertEquals(Platform.THREADS, PlatformDetector.detect("https://www.threads.com/@user/post/ABC123"))
        assertEquals(Platform.THREADS, PlatformDetector.detect("https://threads.com/share/BATAx_4uRb/"))
        assertEquals(Platform.THREADS, PlatformDetector.detect("https://www.threads.com/share/BATAx_4uRb/"))
    }

    @Test
    fun detect_xAndTwitterUrls() {
        assertEquals(Platform.X, PlatformDetector.detect("https://x.com/user/status/1234567890"))
        assertEquals(Platform.X, PlatformDetector.detect("https://twitter.com/user/status/1234567890"))
        assertEquals(Platform.X, PlatformDetector.detect("https://t.co/shortlink"))
    }

    @Test
    fun detect_tiktokUrls() {
        assertEquals(Platform.TIKTOK, PlatformDetector.detect("https://www.tiktok.com/@user/video/1234567890"))
        assertEquals(Platform.TIKTOK, PlatformDetector.detect("https://vt.tiktok.com/ZS123456/"))
        assertEquals(Platform.TIKTOK, PlatformDetector.detect("https://vm.tiktok.com/ZS123456/"))
    }

    @Test
    fun detect_genericUrls() {
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://vimeo.com/12345678"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://bilibili.com/video/BV123456789"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://example.com/stream.mp4"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect(null))
        assertEquals(Platform.GENERIC, PlatformDetector.detect(""))
    }

    @Test
    fun detect_doesNotMatchLookalikeDomains() {
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://notyoutube.com/watch?v=123"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://fakefacebook.com/video"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://notinstagram.com/reel"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://fake-threads.net/post"))
        assertEquals(Platform.GENERIC, PlatformDetector.detect("https://faketiktok.com/video"))
    }
}
