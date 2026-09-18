package com.charleswoo1.videodownloader.data.download.meta

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Helper to invoke the bundled FFmpeg binary for audio extraction and stream merging.
 */
object MetaFfmpegHelper {
    private const val TAG = "MetaFfmpegHelper"

    fun extractAudio(context: Context?, source: File, target: File): Boolean {
        val ctx = context ?: return false
        return try {
            val ffmpegDir = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
            val ffmpegBin = File(ffmpegDir, "ffmpeg")
            if (!ffmpegBin.exists()) return false
            ffmpegBin.setExecutable(true)

            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(
                ffmpegBin.absolutePath,
                "-y",
                "-i", source.absolutePath,
                "-vn",
                "-c:a", "libmp3lame",
                "-q:a", "2",
                target.absolutePath
            )
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:${ffmpegDir.absolutePath}"
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
        return try {
            val ffmpegDir = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
            val ffmpegBin = File(ffmpegDir, "ffmpeg")
            if (!ffmpegBin.exists()) return false
            ffmpegBin.setExecutable(true)

            val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(
                ffmpegBin.absolutePath,
                "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-c:v", "copy",
                "-c:a", "aac",
                target.absolutePath
            )
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:${ffmpegDir.absolutePath}"
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
