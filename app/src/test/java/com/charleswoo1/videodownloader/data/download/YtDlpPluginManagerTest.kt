package com.charleswoo1.videodownloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YtDlpPluginManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("yt-dlp-plugins")
    }

    @Test
    fun firstInstall_copiesAssetAndSetsCommitMarker() {
        val commit = YtDlpPluginManager.PINNED_COMMIT
        val result = YtDlpPluginManager.installToDir(baseDir, commit) { targetPluginDir ->
            val extractorDir = File(targetPluginDir, "extractor")
            extractorDir.mkdirs()
            File(extractorDir, "threads.py").writeText("# mock threads.py")
        }

        assertTrue("Installation should succeed", result.isSuccess)
        val installedDir = result.getOrThrow()
        assertEquals(commit, installedDir.name)

        val marker = File(installedDir, YtDlpPluginManager.COMMIT_MARKER_FILE)
        assertTrue(marker.exists())
        assertEquals(commit, marker.readText().trim())

        val extractor = File(installedDir, YtDlpPluginManager.RELATIVE_PLUGIN_PATH)
        assertTrue(extractor.exists())
        assertTrue(extractor.length() > 0)

        assertTrue(YtDlpPluginManager.isPluginAvailable(installedDir))
    }

    @Test
    fun sameCommit_doesNotReinstallUnnecessarily() {
        val commit = YtDlpPluginManager.PINNED_COMMIT
        var copyCount = 0

        val r1 = YtDlpPluginManager.installToDir(baseDir, commit) { targetPluginDir ->
            copyCount++
            val extractorDir = File(targetPluginDir, "extractor")
            extractorDir.mkdirs()
            File(extractorDir, "threads.py").writeText("# content v1")
        }
        assertTrue(r1.isSuccess)
        assertEquals(1, copyCount)

        val r2 = YtDlpPluginManager.installToDir(baseDir, commit) {
            copyCount++
        }
        assertTrue(r2.isSuccess)
        assertEquals(1, copyCount) // Not called again
    }

    @Test
    fun changedMarker_triggersRefreshAndCleansStaleVersions() {
        val oldCommit = "old_commit_11111111"
        val newCommit = "new_commit_22222222"

        val r1 = YtDlpPluginManager.installToDir(baseDir, oldCommit) { targetPluginDir ->
            val extractorDir = File(targetPluginDir, "extractor")
            extractorDir.mkdirs()
            File(extractorDir, "threads.py").writeText("# old extractor")
        }
        assertTrue(r1.isSuccess)
        assertTrue(File(baseDir, oldCommit).exists())

        val r2 = YtDlpPluginManager.installToDir(baseDir, newCommit) { targetPluginDir ->
            val extractorDir = File(targetPluginDir, "extractor")
            extractorDir.mkdirs()
            File(extractorDir, "threads.py").writeText("# new extractor")
        }
        assertTrue(r2.isSuccess)
        val newInstalled = r2.getOrThrow()
        assertEquals(newCommit, newInstalled.name)

        // Stale old version must be cleaned up
        assertFalse("Old version directory must be removed", File(baseDir, oldCommit).exists())
        assertTrue("New version directory must exist", File(baseDir, newCommit).exists())
    }

    @Test
    fun invalidOrMissingExtractor_returnsFailureSafely() {
        val commit = YtDlpPluginManager.PINNED_COMMIT

        val result = YtDlpPluginManager.installToDir(baseDir, commit) { targetPluginDir ->
            // Simulate broken copy that doesn't create threads.py
            targetPluginDir.mkdirs()
        }

        assertTrue("Result must be failure when extractor is missing", result.isFailure)
        assertFalse("Corrupted target directory must not remain installed", File(baseDir, commit).exists())
    }
}
