package com.charleswoo1.videodownloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.charleswoo1.videodownloader.MainActivity
import com.charleswoo1.videodownloader.R
import com.charleswoo1.videodownloader.data.download.DownloadRepository
import com.charleswoo1.videodownloader.domain.model.DownloadRequest
import com.charleswoo1.videodownloader.domain.model.DownloadState
import com.charleswoo1.videodownloader.domain.model.QualityOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_DOWNLOAD = "com.charleswoo1.videodownloader.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.charleswoo1.videodownloader.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_QUALITY_ID = "extra_quality_id"
        const val EXTRA_QUALITY_LABEL = "extra_quality_label"
        const val EXTRA_FORMAT_SELECTOR = "extra_format_selector"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"

        fun startDownload(
            context: Context,
            request: DownloadRequest
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, request.url)
                putExtra(EXTRA_TITLE, request.title)
                putExtra(EXTRA_QUALITY_ID, request.qualityOption.id)
                putExtra(EXTRA_QUALITY_LABEL, request.qualityOption.label)
                putExtra(EXTRA_FORMAT_SELECTOR, request.qualityOption.formatSelector)
                putExtra(EXTRA_IS_AUDIO_ONLY, request.qualityOption.isAudioOnly)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var notificationManager: NotificationManager
    private var currentTitle: String = ""
    private var downloadJob: Job? = null
    private var currentExecutionId: Long = 0

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                handleCancel(startId)
            }
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "影片"
                val qualityId = intent.getStringExtra(EXTRA_QUALITY_ID) ?: "best"
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY_LABEL) ?: getString(R.string.quality_best)
                val formatSelector = intent.getStringExtra(EXTRA_FORMAT_SELECTOR) ?: "best"
                val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)

                val qualityOption = QualityOption(qualityId, qualityLabel, formatSelector, isAudioOnly)
                val request = DownloadRequest(url, title, qualityOption)
                currentTitle = title

                startOrRestartDownload(request, startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startOrRestartDownload(request: DownloadRequest, startId: Int) {
        val currentState = DownloadRepository.downloadState.value
        if (DownloadRepository.isDownloadActive() ||
            downloadJob?.isActive == true ||
            currentState is DownloadState.Cancelling
        ) {
            Log.w(TAG, "Download lifecycle is still active; ignoring duplicate/restart request")
            return
        }

        if (!DownloadRepository.tryStartDownload()) {
            Log.w(TAG, "Download repository rejected duplicate start request")
            return
        }

        val executionId = ++currentExecutionId
        downloadJob = serviceScope.launch {
            executeDownload(request, executionId, startId)
        }
    }

    private fun handleCancel(startId: Int? = null) {
        if (DownloadRepository.downloadState.value is DownloadState.Cancelling) {
            Log.d(TAG, "Cancellation already in progress; ignoring duplicate cancel request")
            return
        }

        val jobToCancel = downloadJob
        if (jobToCancel == null || !DownloadRepository.isDownloadActive()) {
            Log.d(TAG, "No active download job to cancel")
            return
        }

        val cancellationId = ++currentExecutionId
        DownloadRepository.requestCancel()

        serviceScope.launch {
            try {
                jobToCancel.cancelAndJoin()
            } finally {
                if (cancellationId == currentExecutionId) {
                    if (downloadJob === jobToCancel) {
                        downloadJob = null
                    }
                    DownloadRepository.completeCancellation()
                    stopForegroundSafely()
                    if (startId != null) {
                        stopSelf(startId)
                    } else {
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "Foreground service timed out for fgsType $fgsType, startId $startId")
        val timeoutId = ++currentExecutionId
        val jobToCancel = downloadJob
        DownloadRepository.getEngine(this@DownloadService).cancelDownload()

        serviceScope.launch {
            try {
                jobToCancel?.cancelAndJoin()
            } finally {
                if (timeoutId == currentExecutionId) {
                    if (downloadJob === jobToCancel) {
                        downloadJob = null
                    }
                    DownloadRepository.finishDownload()
                    DownloadRepository.updateState(
                        DownloadState.Failed(getString(R.string.error_download_timeout))
                    )
                    showFailureNotification(currentTitle, getString(R.string.error_download_timeout))
                    stopForegroundSafely()
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun executeDownload(
        request: DownloadRequest,
        executionId: Long,
        startId: Int
    ) {
        val sessionDir = File(File(cacheDir, "temp_downloads"), UUID.randomUUID().toString())
        sessionDir.mkdirs()

        startForegroundWithNotification(
            buildProgressNotification(
                request.title,
                0,
                null,
                getString(R.string.status_preparing)
            )
        )

        try {
            if (executionId != currentExecutionId) return

                DownloadRepository.updateState(DownloadState.Preparing)
                val engine = DownloadRepository.getEngine(this@DownloadService)
                val storage = DownloadRepository.getStorage(this@DownloadService)

                val downloadResult = engine.download(
                    request = request,
                    destDir = sessionDir,
                    onProgress = { progress, etaSeconds, speedText ->
                        if (executionId == currentExecutionId) {
                            DownloadRepository.updateState(
                                DownloadState.Downloading(progress, etaSeconds, speedText)
                            )
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildProgressNotification(
                                    request.title,
                                    progress.toInt(),
                                    speedText,
                                    getString(R.string.progress_format, progress)
                                )
                            )
                        }
                    },
                    onStatus = { statusText ->
                        if (executionId == currentExecutionId) {
                            if (statusText.contains("合併") || statusText.contains("後製")) {
                                DownloadRepository.updateState(DownloadState.PostProcessing)
                            }
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildIndeterminateNotification(request.title, statusText)
                            )
                        }
                    }
                )

                if (executionId != currentExecutionId) return@launch

                downloadResult.onSuccess { tempFile ->
                    if (executionId != currentExecutionId) return@onSuccess

                    DownloadRepository.updateState(DownloadState.PostProcessing)
                    val saveResult = storage.saveToDownloads(tempFile, request.title)
                    saveResult.onSuccess { saved ->
                        if (executionId == currentExecutionId) {
                            DownloadRepository.updateState(
                                DownloadState.Completed(
                                    fileName = saved.fileName,
                                    contentUri = saved.uri,
                                    filePath = saved.absolutePath
                                )
                            )
                            showCompletionNotification(request.title, saved.fileName)
                        }
                    }.onFailure { error ->
                        if (executionId == currentExecutionId) {
                            DownloadRepository.updateState(
                                DownloadState.Failed("儲存檔案失敗: ${error.message}")
                            )
                            showFailureNotification(request.title, "儲存失敗: ${error.message}")
                        }
                    }
                }.onFailure { error ->
                    if (executionId == currentExecutionId) {
                        if (error is InterruptedException ||
                            DownloadRepository.downloadState.value is DownloadState.Cancelling ||
                            DownloadRepository.downloadState.value is DownloadState.Cancelled
                        ) {
                            Log.d(TAG, "Download $executionId was cancelled; skipping error alert")
                        } else {
                            DownloadRepository.updateState(
                                DownloadState.Failed(error.message ?: "下載失敗")
                            )
                            showFailureNotification(request.title, error.message ?: "下載失敗")
                        }
                    }
                }
        } finally {
            sessionDir.deleteRecursively()
            if (executionId == currentExecutionId) {
                DownloadRepository.finishDownload()
                downloadJob = null
                stopForegroundSafely()
                stopSelf(startId)
            }
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getCancelIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(
        title: String,
        progress: Int,
        speed: String?,
        status: String
    ): Notification {
        val speedInfo = if (!speed.isNullOrBlank()) " ($speed)" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$status$speedInfo")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setContentIntent(getContentIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                getCancelIntent()
            )
            .build()
    }

    private fun buildIndeterminateNotification(title: String, status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setContentIntent(getContentIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel),
                getCancelIntent()
            )
            .build()
    }

    private fun showCompletionNotification(title: String, fileName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_completed))
            .setContentText(getString(R.string.notification_completed_desc, fileName))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(getContentIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showFailureNotification(title: String, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_failed))
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(getContentIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentExecutionId++
        DownloadRepository.finishDownload()
        notificationManager.cancel(NOTIFICATION_ID)
        serviceScope.cancel()
    }
}
