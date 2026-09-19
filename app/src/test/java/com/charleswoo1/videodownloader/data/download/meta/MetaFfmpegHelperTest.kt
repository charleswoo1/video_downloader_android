package com.charleswoo1.videodownloader.data.download.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetaFfmpegHelperTest {

    @Test
    fun resolveRuntimePaths_resolvesExecutableToNativeLibraryDirLibffmpegSo() {
        val nativeLibDir = File("/data/app/com.charleswoo1.videodownloader/lib/arm64")
        val noBackupDir = File("/data/user/0/com.charleswoo1.videodownloader/no_backup")

        val paths = MetaFfmpegHelper.resolveRuntimePaths(
            nativeLibraryDir = nativeLibDir,
            noBackupFilesDir = noBackupDir
        )

        // 1. Executable resolves to nativeLibraryDir/libffmpeg.so
        assertEquals("libffmpeg.so", paths.executable.name)
        assertEquals(nativeLibDir.absolutePath, paths.executable.parentFile?.absolutePath)
        assertEquals(File(nativeLibDir, "libffmpeg.so").absolutePath, paths.executable.absolutePath)
    }

    @Test
    fun resolveRuntimePaths_neverLooksForPackagesFfmpegFfmpeg() {
        val nativeLibDir = File("/data/app/com.charleswoo1.videodownloader/lib/arm64")
        val noBackupDir = File("/data/user/0/com.charleswoo1.videodownloader/no_backup")

        val paths = MetaFfmpegHelper.resolveRuntimePaths(
            nativeLibraryDir = nativeLibDir,
            noBackupFilesDir = noBackupDir
        )

        // 2. Must NOT look for packages/ffmpeg/ffmpeg
        assertNotEquals("ffmpeg", paths.executable.name)
        assertFalse(
            "Executable path must not point to packages/ffmpeg/ffmpeg",
            paths.executable.absolutePath.contains("packages/ffmpeg/ffmpeg") ||
            paths.executable.absolutePath.contains("packages\\ffmpeg\\ffmpeg")
        )
    }

    @Test
    fun resolveRuntimePaths_constructsCorrectLdLibraryPath() {
        val nativeLibDir = File("/data/app/com.charleswoo1.videodownloader/lib/arm64")
        val noBackupDir = File("/data/user/0/com.charleswoo1.videodownloader/no_backup")

        val paths = MetaFfmpegHelper.resolveRuntimePaths(
            nativeLibraryDir = nativeLibDir,
            noBackupFilesDir = noBackupDir
        )

        val expectedUsrLib = File(noBackupDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath
        val expectedNativeLib = nativeLibDir.absolutePath

        // 3. LD_LIBRARY_PATH must contain both nativeLibraryDir and noBackupFilesDir/youtubedl-android/packages/ffmpeg/usr/lib
        assertTrue(
            "LD_LIBRARY_PATH must contain nativeLibraryDir: ${paths.ldLibraryPath}",
            paths.ldLibraryPath.contains(expectedNativeLib)
        )
        assertTrue(
            "LD_LIBRARY_PATH must contain packages/ffmpeg/usr/lib: ${paths.ldLibraryPath}",
            paths.ldLibraryPath.contains(expectedUsrLib)
        )
        assertEquals(
            "$expectedNativeLib:$expectedUsrLib",
            paths.ldLibraryPath
        )
    }

    @Test
    fun extractAudio_whenContextNull_returnsFalse() {
        assertFalse(
            MetaFfmpegHelper.extractAudio(
                context = null,
                source = File("/tmp/source.mp4"),
                target = File("/tmp/target.mp3")
            )
        )
    }

    @Test
    fun mergeVideoAndAudio_whenContextNull_returnsFalse() {
        assertFalse(
            MetaFfmpegHelper.mergeVideoAndAudio(
                context = null,
                video = File("/tmp/video.mp4"),
                audio = File("/tmp/audio.mp4"),
                target = File("/tmp/output.mp4")
            )
        )
    }
}
