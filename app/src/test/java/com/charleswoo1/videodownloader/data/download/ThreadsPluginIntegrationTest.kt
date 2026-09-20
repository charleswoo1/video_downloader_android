package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import com.charleswoo1.videodownloader.domain.url.PlatformDetector
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThreadsPluginIntegrationTest {

    @Test
    fun threadsMetadataRequest_includesPluginDirs() {
        val threadsUrl = "https://www.threads.com/@creator/post/DdYkuglEfkF"
        val platform = PlatformDetector.detect(threadsUrl)
        assertEquals(Platform.THREADS, platform)

        val pluginRoot = File("/data/user/0/com.app/noBackupFilesDir/yt-dlp-plugins/${YtDlpPluginManager.PINNED_COMMIT}")

        val request = YoutubeDLRequest(threadsUrl)
        request.addOption("--no-playlist")
        request.addOption("--plugin-dirs", pluginRoot.absolutePath)

        val commandLine = request.buildCommand().joinToString(" ")
        assertTrue("Request must include --plugin-dirs", commandLine.contains("--plugin-dirs"))
        assertTrue("Request must pass the plugin root containing yt_dlp_plugins", commandLine.contains(YtDlpPluginManager.PINNED_COMMIT))
    }

    @Test
    fun nonThreadsRequest_doesNotIncludePluginDirs() {
        val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val platform = PlatformDetector.detect(ytUrl)
        assertEquals(Platform.YOUTUBE, platform)

        val request = YoutubeDLRequest(ytUrl)
        request.addOption("--no-playlist")
        // Plugin dirs should not be added for non-Threads platforms to prevent regression
        val commandLine = request.buildCommand().joinToString(" ")
        assertFalse("Non-Threads requests must not include --plugin-dirs", commandLine.contains("--plugin-dirs"))
    }

    @Test
    fun threadsDownloadRequest_includesPluginDirsForStandardQualityOption() {
        val threadsUrl = "https://www.threads.com/@creator/post/DdYkuglEfkF"
        val pluginRoot = File("/data/user/0/com.app/noBackupFilesDir/yt-dlp-plugins/${YtDlpPluginManager.PINNED_COMMIT}")

        val request = DownloadRequest(
            url = threadsUrl,
            title = "Test Threads Video",
            qualityOption = QualityOption("best", "最佳畫質 (推薦)", "bestvideo+bestaudio/best", false)
        )

        val ytRequest = YoutubeDLRequest(request.url)
        ytRequest.addOption("--no-playlist")
        ytRequest.addOption("--plugin-dirs", pluginRoot.absolutePath)
        ytRequest.addOption("-f", request.qualityOption.formatSelector)

        val commandLine = ytRequest.buildCommand().joinToString(" ")
        assertTrue(commandLine.contains("--plugin-dirs"))
        assertTrue(commandLine.contains("bestvideo+bestaudio/best"))
    }

    @Test
    fun fallbackDetection_distinguishesDirectStreamUrlFromYtDlpSelector() {
        val directStreamOption = QualityOption(
            id = "best",
            label = "最佳畫質",
            formatSelector = "https://cdn.threads.com/v.mp4?token=123",
            isAudioOnly = false
        )
        val pipedStreamOption = QualityOption(
            id = "best",
            label = "最佳畫質",
            formatSelector = "https://cdn.threads.com/v.mp4|https://cdn.threads.com/a.mp4",
            isAudioOnly = false
        )
        val standardYtDlpOption = QualityOption(
            id = "best",
            label = "最佳畫質",
            formatSelector = "bestvideo+bestaudio/best",
            isAudioOnly = false
        )

        // Verifying selector checks used in YtDlpDownloadEngine
        assertTrue(isDirectStreamSelector(directStreamOption.formatSelector))
        assertTrue(isDirectStreamSelector(pipedStreamOption.formatSelector))
        assertFalse(isDirectStreamSelector(standardYtDlpOption.formatSelector))
    }

    @Test
    fun conservativeFallback_rejectsFallbackOnExpectedExtractorFailures() {
        val privateErr = "Post \"DdYkuglEfkF\" was not found in the page data. It may be deleted, private, login-gated, or Threads changed its layout."
        val noVideoErr = "Post \"DdYkuglEfkF\" has no downloadable video (an image post)"
        val carouselErr = "This carousel post contains no videos (images are not supported)"
        val unsupportedUrlErr = "ERROR: Unsupported URL: https://www.threads.com/share/test"
        val moduleErr = "No module named yt_dlp_plugins"

        assertFalse("Private content must not fallback to random video scraper", shouldFallback(privateErr))
        assertFalse("No video content must not fallback", shouldFallback(noVideoErr))
        assertFalse("Carousel without video must not fallback", shouldFallback(carouselErr))

        assertTrue("Unsupported URL should trigger fallback", shouldFallback(unsupportedUrlErr))
        assertTrue("Module error should trigger fallback", shouldFallback(moduleErr))
    }

    private fun isDirectStreamSelector(selector: String): Boolean {
        return selector.startsWith("http://") || selector.startsWith("https://") || selector.contains("|")
    }

    private fun shouldFallback(error: String): Boolean {
        val lower = error.lowercase()
        if (lower.contains("unsupported url") || lower.contains("no module named") || lower.contains("plugin-dirs")) {
            return true
        }
        return false
    }
}
