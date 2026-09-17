package com.charleswoo1.videodownloader.data.download

import com.charleswoo1.videodownloader.domain.model.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadRepositoryTest {

    @Before
    fun setUp() {
        DownloadRepository.reset()
    }

    @After
    fun tearDown() {
        DownloadRepository.reset()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(DownloadState.Idle, DownloadRepository.downloadState.value)
        assertFalse(DownloadRepository.isDownloadActive())
    }

    @Test
    fun startDownload_activatesJobFlag() {
        assertTrue(DownloadRepository.tryStartDownload())
        assertTrue(DownloadRepository.isDownloadActive())

        // Concurrent start should fail
        assertFalse(DownloadRepository.tryStartDownload())
    }

    @Test
    fun requestCancel_transitionsToCancellingAndPreservesActiveFlag() {
        DownloadRepository.markDownloadStarted()
        DownloadRepository.updateState(DownloadState.Downloading(50f))

        DownloadRepository.requestCancel()

        assertEquals(DownloadState.Cancelling, DownloadRepository.downloadState.value)
        // Remains active during cancellation so other starts cannot slip in
        assertTrue(DownloadRepository.isDownloadActive())
    }

    @Test
    fun completeCancellation_transitionsToCancelledAndClearsActiveFlag() {
        DownloadRepository.markDownloadStarted()
        DownloadRepository.requestCancel()

        DownloadRepository.completeCancellation()

        assertEquals(DownloadState.Cancelled, DownloadRepository.downloadState.value)
        assertFalse(DownloadRepository.isDownloadActive())

        // Now a new download can start cleanly
        assertTrue(DownloadRepository.tryStartDownload())
    }

    @Test
    fun cancel_immediatelyCancelsAndAllowsRestart() {
        DownloadRepository.markDownloadStarted()
        DownloadRepository.cancel()

        assertEquals(DownloadState.Cancelled, DownloadRepository.downloadState.value)
        assertFalse(DownloadRepository.isDownloadActive())
        assertTrue(DownloadRepository.tryStartDownload())
    }

    @Test
    fun finishDownload_clearsActiveFlag() {
        DownloadRepository.markDownloadStarted()
        assertTrue(DownloadRepository.isDownloadActive())

        DownloadRepository.finishDownload()
        assertFalse(DownloadRepository.isDownloadActive())
    }

    @Test
    fun clearTerminalState_clearsTerminalStatesAndPreservesActiveStates() {
        // Completed -> Idle
        DownloadRepository.updateState(DownloadState.Completed("test.mp4"))
        DownloadRepository.clearTerminalState()
        assertEquals(DownloadState.Idle, DownloadRepository.downloadState.value)

        // Failed -> Idle
        DownloadRepository.updateState(DownloadState.Failed("some error"))
        DownloadRepository.clearTerminalState()
        assertEquals(DownloadState.Idle, DownloadRepository.downloadState.value)

        // Cancelled -> Idle
        DownloadRepository.updateState(DownloadState.Cancelled)
        DownloadRepository.clearTerminalState()
        assertEquals(DownloadState.Idle, DownloadRepository.downloadState.value)

        // Active state (Downloading) should be preserved
        val downloading = DownloadState.Downloading(42f)
        DownloadRepository.updateState(downloading)
        DownloadRepository.clearTerminalState()
        assertEquals(downloading, DownloadRepository.downloadState.value)

        // Active state (Preparing) should be preserved
        DownloadRepository.updateState(DownloadState.Preparing)
        DownloadRepository.clearTerminalState()
        assertEquals(DownloadState.Preparing, DownloadRepository.downloadState.value)
    }
}