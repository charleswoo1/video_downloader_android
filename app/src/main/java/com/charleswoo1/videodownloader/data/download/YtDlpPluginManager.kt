package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

object YtDlpPluginManager {

    private const val TAG = "YtDlpPluginManager"

    const val PINNED_COMMIT = "c4c44141cb10715f94296a808f5d89a0d24dfe94"
    private const val ASSET_BASE_DIR = "yt-dlp-plugins"
    const val COMMIT_MARKER_FILE = ".commit"
    const val RELATIVE_PLUGIN_PATH = "yt_dlp_plugins/extractor/threads.py"

    private val lock = Any()

    private fun logD(msg: String) {
        try { Log.d(TAG, msg) } catch (_: Throwable) {}
    }
    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }
    private fun logW(msg: String, tr: Throwable? = null) {
        try { Log.w(TAG, msg, tr) } catch (_: Throwable) {}
    }
    private fun logE(msg: String, tr: Throwable? = null) {
        try { Log.e(TAG, msg, tr) } catch (_: Throwable) {}
    }

    fun getPluginCommit(): String = PINNED_COMMIT

    /**
     * Returns the root directory containing `yt_dlp_plugins/` suitable to pass as `--plugin-dirs`.
     */
    fun getPluginDir(context: Context): File {
        return File(context.noBackupFilesDir, "yt-dlp-plugins/$PINNED_COMMIT")
    }

    /**
     * Checks whether the pinned plugin is extracted, verified with the commit marker, and ready to use in a directory.
     */
    fun isPluginAvailable(pluginDir: File): Boolean {
        val commitMarker = File(pluginDir, COMMIT_MARKER_FILE)
        val threadsExtractor = File(pluginDir, RELATIVE_PLUGIN_PATH)

        return pluginDir.isDirectory &&
                commitMarker.isFile &&
                commitMarker.readText().trim() == PINNED_COMMIT &&
                threadsExtractor.isFile &&
                threadsExtractor.length() > 0L
    }

    /**
     * Checks whether the pinned plugin is extracted, verified with the commit marker, and ready to use.
     */
    fun isPluginAvailable(context: Context): Boolean {
        return isPluginAvailable(getPluginDir(context))
    }

    /**
     * Internal extraction logic that operates on any base directory and asset copy action.
     * Guaranteed thread-safe with atomic directory replacement.
     */
    internal fun installToDir(
        baseDir: File,
        targetCommit: String = PINNED_COMMIT,
        copyAssets: (targetPluginDir: File) -> Unit
    ): Result<File> = synchronized(lock) {
        val targetDir = File(baseDir, targetCommit)
        val commitMarker = File(targetDir, COMMIT_MARKER_FILE)
        val threadsExtractor = File(targetDir, RELATIVE_PLUGIN_PATH)

        if (targetDir.isDirectory &&
            commitMarker.isFile &&
            commitMarker.readText().trim() == targetCommit &&
            threadsExtractor.isFile &&
            threadsExtractor.length() > 0L) {
            logD("Plugin $targetCommit is already installed and verified at ${targetDir.name}")
            return Result.success(targetDir)
        }

        if (!baseDir.exists() && !baseDir.mkdirs()) {
            return Result.failure(IOException("Failed to create plugins base directory: ${baseDir.absolutePath}"))
        }

        val tmpDir = File(baseDir, "tmp_${UUID.randomUUID()}")
        try {
            if (!tmpDir.mkdirs()) {
                return Result.failure(IOException("Failed to create temporary directory for plugin extraction: ${tmpDir.absolutePath}"))
            }

            val targetPluginDir = File(tmpDir, "yt_dlp_plugins")
            copyAssets(targetPluginDir)

            val extractedExtractor = File(tmpDir, RELATIVE_PLUGIN_PATH)
            if (!extractedExtractor.isFile || extractedExtractor.length() == 0L) {
                tmpDir.deleteRecursively()
                return Result.failure(IOException("Plugin extraction validation failed: $RELATIVE_PLUGIN_PATH not found or empty"))
            }

            val markerFile = File(tmpDir, COMMIT_MARKER_FILE)
            markerFile.writeText(targetCommit)

            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }

            val renamed = tmpDir.renameTo(targetDir)
            if (!renamed) {
                copyDirectory(tmpDir, targetDir)
                tmpDir.deleteRecursively()
            }

            cleanStaleVersions(baseDir, keepDirName = targetDir.name)

            val finalMarker = File(targetDir, COMMIT_MARKER_FILE)
            val finalExtractor = File(targetDir, RELATIVE_PLUGIN_PATH)
            if (!targetDir.isDirectory ||
                !finalMarker.isFile ||
                finalMarker.readText().trim() != targetCommit ||
                !finalExtractor.isFile ||
                finalExtractor.length() == 0L) {
                return Result.failure(IOException("Failed to verify plugin after installation in ${targetDir.absolutePath}"))
            }

            logI("Successfully installed pinned Threads plugin ($targetCommit) to ${targetDir.name}")
            return Result.success(targetDir)
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            logE("Error installing pinned Threads plugin", e)
            return Result.failure(e)
        }
    }

    /**
     * Ensures the pinned bundled plugin is extracted to the app-private filesystem.
     * Uses an atomic directory swap to guarantee consistency.
     */
    fun ensureInstalled(context: Context): Result<File> {
        val baseDir = File(context.noBackupFilesDir, "yt-dlp-plugins")
        return installToDir(baseDir, PINNED_COMMIT) { targetPluginDir ->
            copyAssetFolder(context, "$ASSET_BASE_DIR/yt_dlp_plugins", targetPluginDir)
        }
    }

    private fun cleanStaleVersions(baseDir: File, keepDirName: String) {
        try {
            val children = baseDir.listFiles() ?: return
            for (child in children) {
                if (child.name != keepDirName) {
                    logD("Removing stale plugin directory: ${child.name}")
                    child.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            logW("Failed cleaning stale plugin directories", e)
        }
    }

    private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: emptyArray()

        if (files.isEmpty()) {
            copyAssetFile(context, assetPath, targetDir)
            return
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (filename in files) {
            val childAssetPath = "$assetPath/$filename"
            val childTargetFile = File(targetDir, filename)
            val subFiles = assetManager.list(childAssetPath)
            if (!subFiles.isNullOrEmpty()) {
                copyAssetFolder(context, childAssetPath, childTargetFile)
            } else {
                copyAssetFile(context, childAssetPath, childTargetFile)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        val files = source.listFiles() ?: return
        for (file in files) {
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                file.copyTo(dest, overwrite = true)
            }
        }
    }
}
