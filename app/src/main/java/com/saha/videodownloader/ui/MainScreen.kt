package com.saha.videodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.FfmpegJobTracker
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.LibraryDownload
import com.saha.videodownloader.model.VideoType
import com.saha.videodownloader.viewmodel.DetectedFilter
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
    val filteredVideos by viewModel.filteredVideos.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val isPageLoading by viewModel.isPageLoading.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val selectedUrl by viewModel.selectedUrl.collectAsStateWithLifecycle()
    val pendingNavigateUrl by viewModel.pendingNavigateUrl.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()
    val useDesktopUa by viewModel.useDesktopUa.collectAsStateWithLifecycle()
    val reloadToken by viewModel.reloadToken.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val ffmpegJobs by FfmpegJobTracker.snapshot.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: "https://") }
    var webViewLoadUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    BadgedBox(
                        badge = {
                            if (detectedVideos.isNotEmpty()) {
                                Badge { Text("${detectedVideos.size}") }
                            }
                        }
                    ) {
                        Text(
                            text = "saha Video Downloader",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenDownloads) {
                        Text("ดาวน์โหลด")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            UrlBar(
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
                onHistory = { showHistory = true },
                onReload = { viewModel.reloadPage() },
                onClearSiteData = {
                    clearWebViewData(webViewRef)
                    viewModel.clearDetectedUrls()
                    Toast.makeText(context, "ล้างคุกกี้/แคชแล้ว", Toast.LENGTH_SHORT).show()
                    viewModel.reloadPage()
                },
                onGo = {
                    val normalized = normalizeUrl(urlInput)
                    urlInput = normalized
                    viewModel.clearDetectedUrls()
                    viewModel.rememberUrl(normalized)
                    webViewLoadUrl = normalized
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (useDesktopUa) "Desktop site" else "Mobile site",
                    style = MaterialTheme.typography.labelLarge
                )
                Switch(
                    checked = useDesktopUa,
                    onCheckedChange = { viewModel.setUseDesktopUa(it) }
                )
            }

            if (isPageLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (isDownloading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = "กำลังดาวน์โหลด…",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

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
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

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

            HorizontalDivider()

            DetectedListSection(
                videos = filteredVideos,
                totalCount = detectedVideos.size,
                filter = filter,
                onFilterChange = { viewModel.setFilter(it) },
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                selectedUrl = selectedUrl,
                onSelect = { viewModel.selectUrl(it) },
                onCopy = { url ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("video-url", url))
                    Toast.makeText(context, "คัดลอก URL แล้ว", Toast.LENGTH_SHORT).show()
                },
                onDownload = {
                    val selected = detectedVideos.firstOrNull { it.url == selectedUrl }
                    if (selected != null) {
                        when (selected.type) {
                            VideoType.MP4 -> {
                                viewModel.setDownloading(true)
                                DownloadHelper.downloadMp4(context, selected.url)
                                viewModel.setDownloading(false)
                            }
                            VideoType.HLS -> DownloadHelper.handleHlsUrl(
                                context = context,
                                url = selected.url,
                                onDownloadStarted = { viewModel.setDownloading(true) },
                                onDownloadFinished = { viewModel.setDownloading(false) }
                            )
                            VideoType.UNKNOWN -> {
                                viewModel.setDownloading(true)
                                DownloadHelper.handleUnknownOrOther(context, selected.url)
                                viewModel.setDownloading(false)
                            }
                        }
                    }
                },
                onClear = { viewModel.clearDetectedUrls() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun UrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onReload: () -> Unit,
    onClearSiteData: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onBack,
                enabled = canGoBack
            ) {
                Text("←")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("URL") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onGo) {
                Text("ไป")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onHistory) {
                Text("ประวัติ")
            }
            OutlinedButton(onClick = onReload) {
                Text("รีเฟรช")
            }
            OutlinedButton(onClick = onClearSiteData) {
                Text("ล้างข้อมูลไซต์")
            }
        }
    }
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
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = userAgent
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

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
    totalCount: Int,
    filter: DetectedFilter,
    onFilterChange: (DetectedFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDownload: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "วิดีโอ (${videos.size}/$totalCount)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row {
                OutlinedButton(
                    onClick = onClear,
                    enabled = totalCount > 0
                ) {
                    Text("ล้าง")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onDownload,
                    enabled = selectedUrl != null
                ) {
                    Text("ดาวน์โหลด")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("ค้นหาในรายการ") }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DetectedFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = {
                        Text(
                            when (option) {
                                DetectedFilter.ALL -> "ทั้งหมด"
                                DetectedFilter.MP4 -> "MP4"
                                DetectedFilter.HLS -> "HLS"
                                DetectedFilter.UNKNOWN -> "อื่นๆ"
                            }
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (videos.isEmpty()) {
            Text(
                text = if (totalCount == 0) {
                    "ยังไม่พบวิดีโอ — เปิดหน้าเว็บแล้วรอให้รายการขึ้น"
                } else {
                    "ไม่มีรายการในตัวกรอง/คำค้นหานี้"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(videos, key = { it.url }) { item ->
                    DetectedVideoRow(
                        item = item,
                        selected = item.url == selectedUrl,
                        onSelect = { onSelect(item.url) },
                        onCopy = { onCopy(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectedVideoRow(
    item: DetectedVideoUrl,
    selected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(onClick = onCopy) {
            Text("คัดลอก")
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
