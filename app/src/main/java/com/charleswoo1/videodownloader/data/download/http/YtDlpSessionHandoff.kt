package com.charleswoo1.videodownloader.data.download.http

import android.content.Context
import okhttp3.Cookie
import java.io.File
import java.util.UUID

/**
 * Secure, ephemeral Netscape cookie handoff for yt-dlp.
 *
 * Implements the security contract from HANDOFF_ANDROID_AUTH_REFERENCE_V3.md Section 9:
 * - Temporary Netscape cookie file created ONLY in app-private storage (`context.cacheDir/ytdlp_sessions`)
 * - Never written to external storage
 * - File contents zeroed out and deleted immediately in a `finally` block
 * - Zero secret logging or leaking
 */
object YtDlpSessionHandoff {

    fun exportToNetscape(cookies: List<Cookie>): String {
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# This is a generated file! Do not edit.\n\n")

        for (c in cookies) {
            val domain = if (c.domain.startsWith(".")) c.domain else ".${c.domain}"
            val includeSubdomains = "TRUE"
            val path = if (c.path.isNotBlank()) c.path else "/"
            val secure = if (c.secure) "TRUE" else "FALSE"
            val expires = if (c.expiresAt > 0L) (c.expiresAt / 1000L).toString() else "0"
            sb.append("$domain\t$includeSubdomains\t$path\t$secure\t$expires\t${c.name}\t${c.value}\n")
        }
        return sb.toString()
    }

    /**
     * Executes [block] with an ephemeral, app-private Netscape cookie file.
     * Overwrites file contents with zeros and deletes the file in `finally`.
     */
    inline fun <T> withTemporaryCookieFile(
        context: Context,
        cookies: List<Cookie>,
        block: (File) -> T
    ): T {
        val sessionsDir = File(context.cacheDir, "ytdlp_sessions").apply { mkdirs() }
        val tempFile = File(sessionsDir, "sess_${UUID.randomUUID()}.txt")

        try {
            tempFile.writeText(exportToNetscape(cookies), Charsets.UTF_8)
            return block(tempFile)
        } finally {
            wipeAndDelete(tempFile)
        }
    }

    fun wipeAndDelete(file: File) {
        try {
            if (file.exists()) {
                val length = file.length().toInt()
                if (length > 0) {
                    file.writeBytes(ByteArray(length))
                }
                file.delete()
            }
        } catch (_: Exception) {}
    }
}
