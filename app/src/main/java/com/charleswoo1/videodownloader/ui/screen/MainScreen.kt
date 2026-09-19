package com.charleswoo1.videodownloader.ui.screen

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.charleswoo1.videodownloader.R
import com.charleswoo1.videodownloader.data.download.http.SessionState
import com.charleswoo1.videodownloader.domain.model.DownloadState
import com.charleswoo1.videodownloader.domain.model.MediaInfo
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.domain.model.QualityOption
import com.charleswoo1.videodownloader.ui.AnalysisState
import com.charleswoo1.videodownloader.ui.MainViewModel
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val urlInput by viewModel.urlInput.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val engineTrace by viewModel.engineTrace.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var importPlatform by remember { mutableStateOf<Platform?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "平台登入 Session 設定"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // URL Input Section
            UrlInputSection(
                url = urlInput,
                onUrlChanged = { viewModel.onUrlInputChanged(it) },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (clipboard.hasPrimaryClip() &&
                        (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
                         clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true)
                    ) {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        val text = item?.text?.toString() ?: ""
                        if (text.isNotBlank()) {
                            viewModel.onUrlInputChanged(text)
                        }
                    }
                },
                onAnalyze = { viewModel.analyzeCurrentUrl() },
                isAnalyzing = analysisState is AnalysisState.Analyzing
            )

            // Analysis State Card / Feedback
            when (val state = analysisState) {
                is AnalysisState.Analyzing -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.status_analyzing))
                        }
                    }
                }
                is AnalysisState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                is AnalysisState.Success -> {
                    MediaResultCard(
                        mediaInfo = state.mediaInfo,
                        selectedQuality = selectedQuality,
                        onSelectQuality = { viewModel.onQualitySelected(it) },
                        onDownload = { viewModel.startDownload(context) },
                        isDownloading = downloadState is DownloadState.Downloading ||
                                downloadState is DownloadState.Preparing ||
                                downloadState is DownloadState.PostProcessing ||
                                downloadState is DownloadState.Cancelling
                    )
                }
                AnalysisState.Idle -> {
                    // Show introductory guidance
                    Text(
                        text = stringResource(R.string.status_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Active Download State Section
            DownloadProgressSection(
                downloadState = downloadState,
                onCancel = { viewModel.cancelDownload(context) }
            )

            if (engineTrace != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = engineTrace!!.toDisplaySummary(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        engineTrace?.diagnosticFingerprint?.takeIf { it.isNotBlank() }?.let { diag ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = diag,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            val runtimeVersion by viewModel.runtimeVersion.collectAsState()
            val runtimeDiagnostics by viewModel.runtimeDiagnostics.collectAsState()

            RuntimeDiagnosticsSection(
                diagnostics = runtimeDiagnostics,
                runtimeVersion = runtimeVersion
            )
        }
    }

    if (showSettingsDialog) {
        PlatformSessionsDialog(
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false },
            onOpenImport = { targetPlatform ->
                importPlatform = targetPlatform
            }
        )
    }

    if (importPlatform != null) {
        val target = importPlatform!!
        ImportSessionDialog(
            platform = target,
            onDismiss = { importPlatform = null },
            onImport = { rawInput ->
                val result = if (target == Platform.INSTAGRAM || target == Platform.THREADS) {
                    viewModel.importMetaSession(rawInput)
                } else {
                    viewModel.importSession(target, rawInput)
                }
                result.onSuccess {
                    Toast.makeText(context, "${target.displayName} Session 已成功匯入並儲存", Toast.LENGTH_SHORT).show()
                    viewModel.validateSession(target)
                    importPlatform = null
                }.onFailure { err ->
                    Toast.makeText(context, "匯入失敗: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@Composable
fun RuntimeDiagnosticsSection(
    diagnostics: com.charleswoo1.videodownloader.data.download.RuntimeDiagnostics?,
    runtimeVersion: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "執行階段診斷 (Runtime Diagnostics)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ytVer = diagnostics?.ytdlpVersion?.takeIf { it != "unknown" } ?: runtimeVersion ?: "loading..."
            val pyVer = diagnostics?.pythonVersion ?: "loading..."
            val curlStatus = if (diagnostics?.curlCffiAvailable == true) "Available (${diagnostics.curlCffiVersion ?: "installed"})" else "Not present"
            val ffmpegVer = diagnostics?.ffmpegVersion ?: "loading..."

            Text(
                text = "yt-dlp: $ytVer | Python: $pyVer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "curl_cffi: $curlStatus | FFmpeg: $ffmpegVer | 16KB Page: Unverified / known FFmpeg limitation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UrlInputSection(
    url: String,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onAnalyze: () -> Unit,
    isAnalyzing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.url_input_hint)) },
                singleLine = false,
                maxLines = 3,
                trailingIcon = {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.action_paste))
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onAnalyze,
                    enabled = url.isNotBlank() && !isAnalyzing
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_analyze))
                }
            }
        }
    }
}

@Composable
fun MediaResultCard(
    mediaInfo: MediaInfo,
    selectedQuality: QualityOption?,
    onSelectQuality: (QualityOption) -> Unit,
    onDownload: () -> Unit,
    isDownloading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail
            if (!mediaInfo.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(mediaInfo.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = mediaInfo.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            // Title
            Text(
                text = mediaInfo.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Platform & Duration Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(mediaInfo.platform.displayName) }
                )
                if (mediaInfo.durationSeconds != null && mediaInfo.durationSeconds > 0) {
                    val minutes = mediaInfo.durationSeconds / 60
                    val seconds = mediaInfo.durationSeconds % 60
                    SuggestionChip(
                        onClick = {},
                        label = { Text(String.format("%02d:%02d", minutes, seconds)) }
                    )
                }
            }

            // Quality Options
            Text(
                text = stringResource(R.string.label_quality),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Column {
                mediaInfo.qualityOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (option.id == selectedQuality?.id),
                                enabled = !isDownloading,
                                onClick = { onSelectQuality(option) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (option.id == selectedQuality?.id),
                            enabled = !isDownloading,
                            onClick = { onSelectQuality(option) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Download Button
            Button(
                onClick = onDownload,
                enabled = !isDownloading && selectedQuality != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_download))
            }
        }
    }
}

@Composable
fun DownloadProgressSection(
    downloadState: DownloadState,
    onCancel: () -> Unit
) {
    when (downloadState) {
        is DownloadState.Preparing -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.status_preparing), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is DownloadState.Downloading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.progress_format, downloadState.progress),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!downloadState.speedText.isNullOrBlank()) {
                            Text(
                                text = downloadState.speedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { downloadState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
        is DownloadState.PostProcessing -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.status_post_processing), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is DownloadState.Completed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.status_completed),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = downloadState.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        is DownloadState.Cancelling -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.status_cancelling), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is DownloadState.Cancelled -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.status_cancelled))
                }
            }
        }
        is DownloadState.Failed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.status_failed, downloadState.errorMessage),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        DownloadState.Idle -> {
            // Idle, do nothing
        }
    }
}

@Composable
fun PlatformSessionsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onOpenImport: (Platform) -> Unit
) {
    val igSession by (viewModel.instagramSession?.collectAsState() ?: remember { mutableStateOf(null) })
    val thSession by (viewModel.threadsSession?.collectAsState() ?: remember { mutableStateOf(null) })
    val xSession by (viewModel.xSession?.collectAsState() ?: remember { mutableStateOf(null) })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Platform Sessions (平台登入設定)", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "登入狀態儲存於本機硬體安全環境 (Keystore)，絕不上傳至任何外部伺服器。用於下載受限或年齡限制內容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SessionPlatformCard(
                    title = "Instagram",
                    status = igSession?.state ?: SessionState.NOT_CONFIGURED,
                    cookieCount = igSession?.cookieCount ?: 0,
                    details = igSession?.details,
                    onImport = { onOpenImport(Platform.INSTAGRAM) },
                    onValidate = { viewModel.validateSession(Platform.INSTAGRAM) },
                    onClear = { viewModel.clearSession(Platform.INSTAGRAM) }
                )

                SessionPlatformCard(
                    title = "Threads",
                    status = thSession?.state ?: SessionState.NOT_CONFIGURED,
                    cookieCount = thSession?.cookieCount ?: 0,
                    details = thSession?.details,
                    onImport = { onOpenImport(Platform.THREADS) },
                    onValidate = { viewModel.validateSession(Platform.THREADS) },
                    onClear = { viewModel.clearSession(Platform.THREADS) }
                )

                SessionPlatformCard(
                    title = "X (Twitter)",
                    status = xSession?.state ?: SessionState.NOT_CONFIGURED,
                    cookieCount = xSession?.cookieCount ?: 0,
                    details = xSession?.details,
                    onImport = { onOpenImport(Platform.X) },
                    onValidate = { viewModel.validateSession(Platform.X) },
                    onClear = { viewModel.clearSession(Platform.X) }
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("關閉")
            }
        }
    )
}

@Composable
fun SessionPlatformCard(
    title: String,
    status: SessionState,
    cookieCount: Int,
    details: String?,
    onImport: () -> Unit,
    onValidate: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                val (badgeText, badgeColor) = when (status) {
                    SessionState.ACTIVE -> "已連線" to MaterialTheme.colorScheme.primary
                    SessionState.CONFIGURED -> "待驗證" to MaterialTheme.colorScheme.tertiary
                    SessionState.EXPIRED -> "已過期" to MaterialTheme.colorScheme.error
                    SessionState.NOT_CONFIGURED -> "未設定" to MaterialTheme.colorScheme.outline
                }
                Text(
                    text = badgeText,
                    color = badgeColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (status != SessionState.NOT_CONFIGURED) {
                Text(
                    text = if (details.isNullOrBlank()) "已儲存 $cookieCount 個 Cookie 憑證" else "憑證: $cookieCount 個 Cookie ($details)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onImport,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(if (status == SessionState.NOT_CONFIGURED) "匯入 Cookie" else "更新 Cookie", style = MaterialTheme.typography.labelSmall)
                }

                if (status != SessionState.NOT_CONFIGURED) {
                    OutlinedButton(
                        onClick = onValidate,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("驗證狀態", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = onClear,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("清除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ImportSessionDialog(
    platform: Platform,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var rawInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯入 ${platform.displayName} Session", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (platform) {
                        Platform.X -> "請貼上含有 auth_token 與 ct0 的 Cookie 字串、Netscape 格式文字或 JSON。"
                        else -> "請貼上含有 sessionid 的 Cookie 字串、Netscape 格式文字或 JSON（將同時套用至 Instagram 與 Threads）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = rawInput,
                    onValueChange = {
                        rawInput = it
                        errorText = null
                    },
                    label = { Text("Cookie 內容") },
                    placeholder = { Text("sessionid=... 或 auth_token=...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    maxLines = 6
                )

                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (rawInput.isBlank()) {
                        errorText = "請輸入有效的 Cookie 內容"
                    } else {
                        onImport(rawInput)
                    }
                }
            ) {
                Text("儲存並套用")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

