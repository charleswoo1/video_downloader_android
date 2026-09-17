package com.charleswoo1.videodownloader

import android.app.Application
import android.util.Log
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

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
            Log.i(TAG, "YoutubeDL initialized successfully")
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
