package com.charleswoo1.videodownloader

import android.app.Application
import android.util.Log
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        private const val TAG = "SocialVideoDownloaderApp"
    }

    override fun onCreate() {
        super.onCreate()
        DownloadRepository.initialize(this)

        try {
            YoutubeDL.getInstance().init(this)
            DownloadRepository.isInitialized = true
            val activeVer = YoutubeDL.getInstance().version(this) ?: "2026.08.30.232658"
            Log.i(TAG, "[Diagnostics] YoutubeDL initialized successfully, packaged version: $activeVer")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.charleswoo1.videodownloader.data.download.RuntimeDiagnosticsHelper.inspect(this@App)
                } catch (e: Exception) {
                    Log.w(TAG, "[Diagnostics] Runtime diagnostics inspection error", e)
                }
            }
        } catch (e: Exception) {
            DownloadRepository.isInitialized = false
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }

        try {
            FFmpeg.getInstance().init(this)
            Log.i(TAG, "FFmpeg initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FFmpeg", e)
        }
    }
}
