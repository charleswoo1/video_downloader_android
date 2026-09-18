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
            val activeVer = DownloadRepository.refreshRuntimeVersion() ?: "bundled"
            Log.i(TAG, "[Diagnostics] YoutubeDL initialized successfully, active version: $activeVer")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DownloadRepository.updateRuntime()
                } catch (e: Exception) {
                    Log.w(TAG, "[Diagnostics] Background update of yt-dlp runtime encountered error", e)
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
