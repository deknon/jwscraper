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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CompactTopChrome(
                title = "saha Video Downloader",
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
                }
            )

            if (isPageLoading || isDownloading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isDownloading) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
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
                expanded = listExpanded,
                onExpandedChange = { listExpanded = it },
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
                                onDownloadFinished = { viewModel.setDownloading(false) },
                                userAgent = viewModel.currentUserAgent()
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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactTopChrome(
    title: String,
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
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BadgedBox(
                    badge = {
                        if (detectedCount > 0) {
                            Badge { Text("$detectedCount") }
                        }
                    }
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onOpenDownloads,
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text("ดาวน์โหลด", fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    enabled = canGoBack,
                    modifier = Modifier.size(width = 40.dp, height = 36.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text("←", fontSize = 18.sp)
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

                TextButton(
                    onClick = { onMenuExpandedChange(true) },
                    modifier = Modifier.size(width = 40.dp, height = 36.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text("⋮", fontSize = 20.sp)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) }
                ) {
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
                }
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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                modifier = Modifier.padding(end = 6.dp),
                fontSize = 14.sp
            )
            Text(
                text = "วิดีโอ (${videos.size}/$totalCount)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onClear,
                enabled = totalCount > 0,
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("ล้าง", fontSize = 13.sp)
            }
            Button(
                onClick = onDownload,
                enabled = selectedUrl != null,
                modifier = Modifier.height(32.dp),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text("ดาวน์โหลด", fontSize = 13.sp)
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    singleLine = true,
                    placeholder = { Text("ค้นหาในรายการ", fontSize = 13.sp) },
                    textStyle = TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetectedFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilterChange(option) },
                            label = {
                                Text(
                                    text = when (option) {
                                        DetectedFilter.ALL -> "ทั้งหมด"
                                        DetectedFilter.MP4 -> "MP4"
                                        DetectedFilter.HLS -> "HLS"
                                        DetectedFilter.UNKNOWN -> "อื่นๆ"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                if (videos.isEmpty()) {
                    Text(
                        text = if (totalCount == 0) {
                            "ยังไม่พบวิดีโอ — เปิดหน้าเว็บแล้วรอ"
                        } else {
                            "ไม่มีรายการในตัวกรอง/คำค้นหานี้"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
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
