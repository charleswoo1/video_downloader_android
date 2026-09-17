package com.charleswoo1.videodownloader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelMappingTest {

    @Test
    fun qualityOption_creationAndProperties() {
        val videoBest = QualityOption(
            id = "best",
            label = "最佳畫質",
            formatSelector = "bestvideo+bestaudio/best",
            isAudioOnly = false
        )
        assertEquals("best", videoBest.id)
        assertFalse(videoBest.isAudioOnly)

        val audioOnly = QualityOption(
            id = "audio_only",
            label = "僅音訊",
            formatSelector = "bestaudio/best",
            isAudioOnly = true
        )
        assertTrue(audioOnly.isAudioOnly)
    }

    @Test
    fun downloadRequest_creation() {
        val option = QualityOption("1080p", "1080p Full HD", "bestvideo[height<=1080]+bestaudio/best")
        val request = DownloadRequest(
            url = "https://www.youtube.com/watch?v=test",
            title = "Test Video",
            qualityOption = option
        )

        assertEquals("https://www.youtube.com/watch?v=test", request.url)
        assertEquals("Test Video", request.title)
        assertEquals("1080p", request.qualityOption.id)
    }

    @Test
    fun downloadState_transitions() {
        var state: DownloadState = DownloadState.Idle
        assertEquals(DownloadState.Idle, state)

        state = DownloadState.Preparing
        assertEquals(DownloadState.Preparing, state)

        state = DownloadState.Downloading(progress = 50.5f, etaSeconds = 12, speedText = "3.2MiB/s")
        assertTrue(state is DownloadState.Downloading)
        val downloading = state as DownloadState.Downloading
        assertEquals(50.5f, downloading.progress, 0.01f)
        assertEquals(12L, downloading.etaSeconds)
        assertEquals("3.2MiB/s", downloading.speedText)

        state = DownloadState.PostProcessing
        assertEquals(DownloadState.PostProcessing, state)

        state = DownloadState.Completed(fileName = "test.mp4")
        assertTrue(state is DownloadState.Completed)
        assertEquals("test.mp4", (state as DownloadState.Completed).fileName)

        state = DownloadState.Cancelled
        assertEquals(DownloadState.Cancelled, state)

        state = DownloadState.Failed("網路逾時")
        assertTrue(state is DownloadState.Failed)
        assertEquals("網路逾時", (state as DownloadState.Failed).errorMessage)
    }
}
