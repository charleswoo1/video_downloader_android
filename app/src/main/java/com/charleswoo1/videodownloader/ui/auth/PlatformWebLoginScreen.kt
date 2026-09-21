package com.charleswoo1.videodownloader.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.charleswoo1.videodownloader.data.download.http.AndroidCookieScrubber
import com.charleswoo1.videodownloader.data.download.http.PlatformWebLoginCatalog
import com.charleswoo1.videodownloader.data.download.http.PlatformWebLoginConfig
import com.charleswoo1.videodownloader.data.download.http.WebViewCookieCapture
import com.charleswoo1.videodownloader.domain.model.Platform
import com.charleswoo1.videodownloader.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen platform WebView login destination with hardware-backed encryption,
 * native CookieManager capture, FLAG_SECURE protection, and strict lifecycle cleanup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformWebLoginScreen(
    platform: Platform,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config: PlatformWebLoginConfig = remember(platform) {
        PlatformWebLoginCatalog.configFor(platform)
            ?: throw IllegalArgumentException("不支援的 Web 登入平台: ${platform.displayName}")
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val coordinator = remember(config) {
        PlatformWebLoginCoordinator(
            config = config,
            cookieScrubber = AndroidCookieScrubber(),
            importSessionAction = { header -> viewModel.importCapturedSession(config.platform, header) },
            validateSessionAction = { viewModel.validateSessionSuspending(config.platform) },
            cookieProbe = { url ->
                runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            },
            scope = scope
        )
    }

    val loginState by coordinator.state.collectAsState()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var progressPercent by remember { mutableStateOf(0) }

    // Enforce FLAG_SECURE on the Activity window while login screen is visible
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalFlags = activity?.window?.attributes?.flags ?: 0
        val wasSecure = (originalFlags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose {
            if (!wasSecure) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // Intercept system Back button / gesture: go back in WebView history if possible
    BackHandler(enabled = true) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            coordinator.onCancelOrExit {
                onDismiss()
            }
        }
    }

    // Comprehensive lifecycle cleanup on teardown
    DisposableEffect(Unit) {
        onDispose {
            coordinator.onCancelOrExit { }
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearCache(true)
                clearHistory()
                clearFormData()
                (parent as? ViewGroup)?.removeView(this)
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    fun handleSuccess() {
        scope.launch {
            delay(600)
            coordinator.onCancelOrExit {
                onLoginSuccess()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "登入 ${platform.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val statusText = when (loginState) {
                            PlatformWebLoginState.Preparing -> "正在初始化安全環境..."
                            PlatformWebLoginState.LoadingLogin -> "正在載入登入頁面..."
                            PlatformWebLoginState.AwaitingUser -> "等待登入操作"
                            PlatformWebLoginState.Validating -> "正在驗證 Session..."
                            is PlatformWebLoginState.Active -> "登入成功！"
                            is PlatformWebLoginState.Challenge -> "請完成頁面安全驗證"
                            is PlatformWebLoginState.Error -> "驗證異常"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        coordinator.onCancelOrExit {
                            onDismiss()
                        }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "關閉")
                    }
                },
                actions = {
                    if (loginState is PlatformWebLoginState.Validating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        IconButton(onClick = {
                            webViewRef?.reload()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新整理")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (progressPercent in 1..99 && loginState !is PlatformWebLoginState.Validating) {
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Security & Privacy notice card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "帳號密碼直接輸入平台官方網頁，本 App 絕不讀取或儲存密碼。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "建議使用 ${platform.displayName} 帳號密碼登入；第三方（Google 等）在內嵌視窗中可能受安全限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Status message banners for Active / Challenge / Error
            when (val state = loginState) {
                is PlatformWebLoginState.Active -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                is PlatformWebLoginState.Challenge -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                is PlatformWebLoginState.Error -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            if (state.canRetry) {
                                OutlinedButton(onClick = {
                                    coordinator.onPageFinishedOrHistory(webViewRef?.url) {
                                        handleSuccess()
                                    }
                                }) {
                                    Text("重試驗證", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                else -> {}
            }

            // Visible AndroidView hosting the platform login WebView
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webViewRef = this

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, config.allowThirdPartyCookies)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                userAgentString = WebViewCookieCapture.normalizeUserAgent(userAgentString)
                                setSupportZoom(false)
                                builtInZoomControls = false
                                displayZoomControls = false
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    progressPercent = newProgress
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (WebViewCookieCapture.isAllowedNavigation(url)) {
                                        return false
                                    }

                                    val fallback = WebViewCookieCapture.resolveFallbackUrl(url)
                                    if (fallback != null) {
                                        view?.loadUrl(fallback)
                                        return true
                                    }

                                    // Block non-web and custom schemes
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    coordinator.onPageStarted(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    coordinator.onPageFinishedOrHistory(url) {
                                        handleSuccess()
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    coordinator.onPageFinishedOrHistory(url) {
                                        handleSuccess()
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        val desc = error?.description?.toString().orEmpty()
                                        if (desc.contains("redirect", ignoreCase = true) ||
                                            error?.errorCode == -15
                                        ) {
                                            coordinator.onRedirectLoopDetected()
                                        }
                                    }
                                }
                            }

                            // Fresh login: clear stale cookies first, flush upon completion, then load login URL
                            coordinator.prepareFreshLogin { targetUrl ->
                                post {
                                    if (isAttachedToWindow) {
                                        clearCache(true)
                                        clearHistory()
                                        clearFormData()
                                        loadUrl(targetUrl)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (loginState is PlatformWebLoginState.Preparing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "正在準備安全的登入環境...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
