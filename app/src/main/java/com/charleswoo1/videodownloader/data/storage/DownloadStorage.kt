package com.charleswoo1.videodownloader.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DownloadStorage(private val context: Context) {

    companion object {
        private const val TAG = "DownloadStorage"
        private const val SUB_DIR = "SocialVideoDownloader"
    }

    /**
     * Saves a temporary downloaded file to the public Downloads/SocialVideoDownloader directory.
     * Returns a Pair of the final display name and the URI (or file path).
     */
    suspend fun saveToDownloads(sourceFile: File, desiredName: String?): Result<SavedMediaResult> =
        withContext(Dispatchers.IO) {
            try {
                val originalName = desiredName ?: sourceFile.name
                val sanitizedBase = sanitizeFileName(originalName.substringBeforeLast('.'))
                val ext = sourceFile.extension.ifEmpty { "mp4" }
                val mimeType = getMimeType(ext)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(sourceFile, sanitizedBase, ext, mimeType)
                } else {
                    saveViaLegacyStorage(sourceFile, sanitizedBase, ext, mimeType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file to Downloads", e)
                Result.failure(e)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        sourceFile: File,
        baseName: String,
        ext: String,
        mimeType: String
    ): Result<SavedMediaResult> {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"
        val uniqueName = resolveUniqueDisplayName(baseName, ext, relativePath)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return Result.failure(IllegalStateException("Failed to create MediaStore entry"))

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return Result.failure(IllegalStateException("Failed to open MediaStore output stream"))

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Safely delete temp source
            sourceFile.delete()

            return Result.success(SavedMediaResult(uniqueName, uri, null))
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveUniqueDisplayName(baseName: String, ext: String, relativePath: String): String {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$relativePath%")
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val existingNames = mutableSetOf<String>()
        try {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameIndex)?.let { existingNames.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query existing downloads for conflict resolution", e)
        }

        var candidate = "$baseName.$ext"
        var counter = 1
        while (existingNames.contains(candidate)) {
            candidate = "$baseName ($counter).$ext"
            counter++
        }
        return candidate
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyStorage(
        sourceFile: File,
        baseName: String,
        ext: String,
        mimeType: String
    ): Result<SavedMediaResult> {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, SUB_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var candidate = File(targetDir, "$baseName.$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "$baseName ($counter).$ext")
            counter++
        }

        FileInputStream(sourceFile).use { input ->
            FileOutputStream(candidate).use { output ->
                input.copyTo(output)
            }
        }

        sourceFile.delete()

        MediaScannerConnection.scanFile(
            context,
            arrayOf(candidate.absolutePath),
            arrayOf(mimeType),
            null
        )

        val uri = Uri.fromFile(candidate)
        return Result.success(SavedMediaResult(candidate.name, uri, candidate.absolutePath))
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .take(150)
            .ifEmpty { "video" }
    }

    private fun getMimeType(extension: String): String {
        val ext = extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }
}

data class SavedMediaResult(
    val fileName: String,
    val uri: Uri,
    val absolutePath: String?
)
