package com.saha.videodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.FfmpegJobTracker
import com.saha.videodownloader.download.WebViewCookieHelper
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.LibraryDownload
import com.saha.videodownloader.model.VideoType
import com.saha.videodownloader.viewmodel.VideoDownloaderViewModel
import com.saha.videodownloader.webview.VideoInterceptingWebViewClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: VideoDownloaderViewModel,
    onOpenDownloads: () -> Unit = {},
    initialUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val isPageLoading by viewModel.isPageLoading.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val pendingNavigateUrl by viewModel.pendingNavigateUrl.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()
    val useDesktopUa by viewModel.useDesktopUa.collectAsStateWithLifecycle()
    val reloadToken by viewModel.reloadToken.collectAsStateWithLifecycle()
    val ffmpegJobs by FfmpegJobTracker.snapshot.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: "https://") }
    var webViewLoadUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var listExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val keepScreenOn = isDownloading || ffmpegJobs.any {
        it.state == LibraryDownload.State.DOWNLOADING || it.state == LibraryDownload.State.QUEUED
    }

    DisposableEffect(keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(initialUrl) {
        val seed = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val normalized = normalizeUrl(seed)
        urlInput = normalized
        viewModel.clearDetectedUrls()
        viewModel.rememberUrl(normalized)
        webViewLoadUrl = normalized
    }

    LaunchedEffect(pendingNavigateUrl) {
        val target = pendingNavigateUrl ?: return@LaunchedEffect
        val normalized = normalizeUrl(target)
        urlInput = normalized
        viewModel.clearDetectedUrls()
        viewModel.rememberUrl(normalized)
        webViewLoadUrl = normalized
        viewModel.consumeNavigateRequest()
    }

    val previousCount = remember { mutableStateOf(0) }
    LaunchedEffect(detectedVideos.size) {
        if (detectedVideos.isNotEmpty()) {
            listExpanded = true
        }
        if (detectedVideos.size > previousCount.value) {
            snackbarHostState.showSnackbar("พบวิดีโอแล้ว (${detectedVideos.size})")
        }
        previousCount.value = detectedVideos.size
    }

    BackHandler(enabled = canGoBack) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
            canGoBack = webView.canGoBack()
        } else {
            canGoBack = false
        }
    }

    // Put chrome in Scaffold topBar/bottomBar so WebView cannot paint over them.
    // Custom bars must pad status/navigation bars themselves under enableEdgeToEdge().
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactTopChrome(
                detectedCount = detectedVideos.size,
                urlInput = urlInput,
                onUrlChange = { urlInput = it },
                canGoBack = canGoBack,
                onBack = {
                    webViewRef?.let { webView ->
                        if (webView.canGoBack()) {
                            webView.goBack()
                            canGoBack = webView.canGoBack()
                        }
                    }
                },
                onGo = {
                    val normalized = normalizeUrl(urlInput)
                    urlInput = normalized
                    viewModel.clearDetectedUrls()
                    viewModel.rememberUrl(normalized)
                    webViewLoadUrl = normalized
                },
                onOpenDownloads = onOpenDownloads,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                useDesktopUa = useDesktopUa,
                onToggleDesktopUa = { viewModel.setUseDesktopUa(!useDesktopUa) },
                onHistory = {
                    menuExpanded = false
                    showHistory = true
                },
                onReload = {
                    menuExpanded = false
                    viewModel.reloadPage()
                },
                onClearSiteData = {
                    menuExpanded = false
                    clearWebViewData(webViewRef)
                    viewModel.clearDetectedUrls()
                    Toast.makeText(context, "ล้างคุกกี้/แคชแล้ว", Toast.LENGTH_SHORT).show()
                    viewModel.reloadPage()
                },
                onClearDetected = {
                    menuExpanded = false
                    viewModel.clearDetectedUrls()
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp
            ) {
                DetectedListSection(
                    videos = detectedVideos,
                    expanded = listExpanded,
                    onExpandedChange = { listExpanded = it },
                    onDownloadItem = { item ->
                        when (item.type) {
                            VideoType.MP4 -> {
                                viewModel.setDownloading(true)
                                DownloadHelper.downloadMp4(context, item.url)
                                viewModel.setDownloading(false)
                            }
                            VideoType.HLS -> DownloadHelper.handleHlsUrl(
                                context = context,
                                url = item.url,
                                onDownloadStarted = { viewModel.setDownloading(true) },
                                onDownloadFinished = { viewModel.setDownloading(false) },
                                userAgent = viewModel.currentUserAgent(),
                                refererUrl = viewModel.currentPageUrl.value
                                    ?: urlInput.takeIf {
                                        it.startsWith("http://") || it.startsWith("https://")
                                    }
                            )
                            VideoType.UNKNOWN -> {
                                viewModel.setDownloading(true)
                                DownloadHelper.handleUnknownOrOther(context, item.url)
                                viewModel.setDownloading(false)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.background)
        ) {
            VideoWebView(
                loadUrl = webViewLoadUrl,
                userAgent = viewModel.currentUserAgent(),
                reloadToken = reloadToken,
                viewModel = viewModel,
                onWebViewReady = { webViewRef = it },
                onCanGoBackChanged = { canGoBack = it },
                onUrlChanged = { current ->
                    if (!current.isNullOrBlank() && current != "about:blank") {
                        urlInput = current
                        viewModel.setCurrentPageUrl(current)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            )

            if (webViewLoadUrl.isNullOrBlank() &&
                (webViewRef?.url.isNullOrBlank() || webViewRef?.url == "about:blank")
            ) {
                Text(
                    text = "พิมพ์ URL ด้านบน แล้วกด ไป",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            if (isPageLoading || isDownloading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = if (isDownloading) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        if (showHistory) {
            HistoryDialog(
                urls = recentUrls,
                onSelect = { selected ->
                    showHistory = false
                    urlInput = selected
                    viewModel.clearDetectedUrls()
                    viewModel.rememberUrl(selected)
                    webViewLoadUrl = selected
                },
                onClear = {
                    viewModel.clearHistory()
                },
                onDismiss = { showHistory = false }
            )
        }
    }
}

@Composable
private fun CompactTopChrome(
    detectedCount: Int,
    urlInput: String,
    onUrlChange: (String) -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onGo: () -> Unit,
    onOpenDownloads: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    useDesktopUa: Boolean,
    onToggleDesktopUa: () -> Unit,
    onHistory: () -> Unit,
    onReload: () -> Unit,
    onClearSiteData: () -> Unit,
    onClearDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Surface draws behind the status bar; row content is padded below it.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(
                onClick = onBack,
                enabled = canGoBack,
                modifier = Modifier.size(width = 36.dp, height = 36.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            CompactUrlField(
                value = urlInput,
                onValueChange = onUrlChange,
                onGo = onGo,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            )

            Button(
                onClick = onGo,
                modifier = Modifier.height(36.dp),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text("ไป", fontSize = 13.sp)
            }

            BadgedBox(
                badge = {
                    if (detectedCount > 0) {
                        Badge { Text("$detectedCount") }
                    }
                }
            ) {
                TextButton(
                    onClick = onOpenDownloads,
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(
                        "รายการ",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            TextButton(
                onClick = { onMenuExpandedChange(true) },
                modifier = Modifier.size(width = 36.dp, height = 36.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuExpandedChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("saha Video Downloader", fontWeight = FontWeight.SemiBold) },
                    onClick = { onMenuExpandedChange(false) },
                    enabled = false
                )
                DropdownMenuItem(
                    text = { Text("ประวัติ URL") },
                    onClick = onHistory
                )
                DropdownMenuItem(
                    text = { Text("รีเฟรช") },
                    onClick = onReload
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (useDesktopUa) "ใช้ Mobile site" else "ใช้ Desktop site"
                        )
                    },
                    onClick = {
                        onMenuExpandedChange(false)
                        onToggleDesktopUa()
                    }
                )
                DropdownMenuItem(
                    text = { Text("ล้างข้อมูลไซต์") },
                    onClick = onClearSiteData
                )
                DropdownMenuItem(
                    text = { Text("ล้างรายการวิดีโอ") },
                    onClick = onClearDetected
                )
            }
        }
    }
}

@Composable
private fun CompactUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldBg = MaterialTheme.colorScheme.surface
    val fieldFg = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = fieldFg,
            fontSize = 14.sp,
            lineHeight = 18.sp
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fieldBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text(
                    text = "พิมพ์ URL เว็บ…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
            inner()
        }
    )
}

@Composable
private fun HistoryDialog(
    urls: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ประวัติ URL") },
        text = {
            if (urls.isEmpty()) {
                Text("ยังไม่มีประวัติ")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(urls) { url ->
                        TextButton(onClick = { onSelect(url) }) {
                            Text(
                                text = url,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ปิด") }
        },
        dismissButton = {
            TextButton(
                onClick = onClear,
                enabled = urls.isNotEmpty()
            ) {
                Text("ล้างประวัติ")
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoWebView(
    loadUrl: String?,
    userAgent: String,
    reloadToken: Int,
    viewModel: VideoDownloaderViewModel,
    onWebViewReady: (WebView) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    onUrlChanged: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestCanGoBack by rememberUpdatedState(onCanGoBackChanged)
    val latestUrlChanged by rememberUpdatedState(onUrlChanged)
    var appliedReloadToken by remember { mutableStateOf(reloadToken) }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Prevent WebView from drawing outside its Compose slot on HyperOS.
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = userAgent
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // CDN / JW Player auth cookies are often third-party.
                WebViewCookieHelper.enableFor(this)

                webViewClient = object : VideoInterceptingWebViewClient(
                    onVideoUrlDetected = { url, type ->
                        latestViewModel.onVideoUrlDetected(url, type)
                    }
                ) {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        latestViewModel.setPageLoading(true)
                        latestUrlChanged(url)
                        latestCanGoBack(view?.canGoBack() == true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        WebViewCookieHelper.flush()
                        latestViewModel.setPageLoading(false)
                        latestUrlChanged(url)
                        latestCanGoBack(view?.canGoBack() == true)
                    }
                }

                webChromeClient = WebChromeClient()
                onWebViewReady(this)
            }
        },
        update = { webView ->
            onWebViewReady(webView)
            if (webView.settings.userAgentString != userAgent) {
                webView.settings.userAgentString = userAgent
            }
            val target = loadUrl
            if (target != null && webView.url != target) {
                webView.loadUrl(target)
            } else if (reloadToken != appliedReloadToken) {
                appliedReloadToken = reloadToken
                if (!webView.url.isNullOrBlank()) {
                    webView.reload()
                } else if (target != null) {
                    webView.loadUrl(target)
                }
            }
            onCanGoBackChanged(webView.canGoBack())
        }
    )
}

@Composable
private fun DetectedListSection(
    videos: List<DetectedVideoUrl>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDownloadItem: (DetectedVideoUrl) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                modifier = Modifier.padding(end = 6.dp),
                fontSize = 14.sp
            )
            Text(
                text = "วิดีโอ (${videos.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (videos.isNotEmpty() && !expanded) {
                Text(
                    text = "แตะเพื่อดาวน์โหลด",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
            ) {
                if (videos.isEmpty()) {
                    Text(
                        text = "ยังไม่พบวิดีโอ — เปิดหน้าเว็บแล้วรอ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 148.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(videos, key = { it.url }) { item ->
                            DetectedVideoRow(
                                item = item,
                                onClick = { onDownloadItem(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectedVideoRow(
    item: DetectedVideoUrl,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        TypeBadge(type = item.type)
    }
}

@Composable
private fun TypeBadge(type: VideoType) {
    val label = when (type) {
        VideoType.MP4 -> "MP4"
        VideoType.HLS -> "HLS"
        VideoType.UNKNOWN -> "UNKNOWN"
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        shape = RoundedCornerShape(8.dp)
    )
}

private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "https://"
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun clearWebViewData(webView: WebView?) {
    webView?.apply {
        stopLoading()
        clearCache(true)
        clearFormData()
        clearHistory()
    }
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
