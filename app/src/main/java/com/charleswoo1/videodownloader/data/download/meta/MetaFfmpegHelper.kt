package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolved runtime paths and environment for FFmpeg (PR #361 layout).
 */
data class FfmpegRuntimePaths(
    val executable: File,
    val runtimePackagesDir: File,
    val runtimeUsrLibDir: File,
    val ldLibraryPath: String
)

/**
 * Helper to invoke the bundled FFmpeg binary for audio extraction and stream merging.
 *
 * Adheres strictly to the upstream youtubedl-android PR #361 runtime layout:
 * - FFmpeg executable is [applicationInfo.nativeLibraryDir]/libffmpeg.so
 * - Shared libraries are extracted by FFmpeg.init() to [noBackupFilesDir]/youtubedl-android/packages/ffmpeg/usr/lib
 * - LD_LIBRARY_PATH must include both directories
 */
object MetaFfmpegHelper {
    private const val TAG = "MetaFfmpegHelper"

    /**
     * Resolves the runtime paths using Android [Context].
     */
    fun resolveRuntimePaths(context: Context): FfmpegRuntimePaths {
        return resolveRuntimePaths(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            noBackupFilesDir = context.noBackupFilesDir
        )
    }

    /**
     * Resolves the runtime paths given the native library directory and noBackupFiles directory.
     * Pure file-based resolver enabling unit tests without Android mock context.
     */
    fun resolveRuntimePaths(nativeLibraryDir: File, noBackupFilesDir: File): FfmpegRuntimePaths {
        val executable = File(nativeLibraryDir, "libffmpeg.so")
        val runtimePackagesDir = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val runtimeUsrLibDir = File(runtimePackagesDir, "usr/lib")
        val ldLibraryPath = "${nativeLibraryDir.absolutePath}:${runtimeUsrLibDir.absolutePath}"
        return FfmpegRuntimePaths(
            executable = executable,
            runtimePackagesDir = runtimePackagesDir,
            runtimeUsrLibDir = runtimeUsrLibDir,
            ldLibraryPath = ldLibraryPath
        )
    }

    fun extractAudio(context: Context?, source: File, target: File): Boolean {
        val ctx = context ?: return false
        val paths = resolveRuntimePaths(ctx)
        if (!paths.executable.exists()) {
            safeLog("FFmpeg executable not found at: ${paths.executable.absolutePath}")
            return false
        }
        paths.executable.setExecutable(true)

        return try {
            val pb = ProcessBuilder(
                paths.executable.absolutePath,
                "-y",
                "-i", source.absolutePath,
                "-vn",
                "-c:a", "libmp3lame",
                "-q:a", "2",
                target.absolutePath
            )
            pb.environment()["LD_LIBRARY_PATH"] = paths.ldLibraryPath
            val process = pb.start()
            val exit = process.waitFor()
            exit == 0
        } catch (e: Exception) {
            safeLog("FFmpeg audio extraction failed: ${e.message}")
            false
        }
    }

    fun mergeVideoAndAudio(context: Context?, video: File, audio: File, target: File): Boolean {
        val ctx = context ?: return false
        val paths = resolveRuntimePaths(ctx)
        if (!paths.executable.exists()) {
            safeLog("FFmpeg executable not found at: ${paths.executable.absolutePath}")
            return false
        }
        paths.executable.setExecutable(true)

        return try {
            val pb = ProcessBuilder(
                paths.executable.absolutePath,
                "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-c:v", "copy",
                "-c:a", "aac",
                target.absolutePath
            )
            pb.environment()["LD_LIBRARY_PATH"] = paths.ldLibraryPath
            val process = pb.start()
            val exit = process.waitFor()
            exit == 0
        } catch (e: Exception) {
            safeLog("FFmpeg merge failed: ${e.message}")
            false
        }
    }

    private fun safeLog(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
