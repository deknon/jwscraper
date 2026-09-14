package com.saha.videodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.saha.videodownloader.download.DownloadFilenameResolver
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.FfmpegJobTracker
import com.saha.videodownloader.download.WebViewCookieHelper
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.LibraryDownload
import com.saha.videodownloader.model.VideoMetaState
import com.saha.videodownloader.model.VideoType
import com.saha.videodownloader.util.MediaUrlActions
import com.saha.videodownloader.viewmodel.BrowserTab
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
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val pendingNavigateUrl by viewModel.pendingNavigateUrl.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()
    val useDesktopUa by viewModel.useDesktopUa.collectAsStateWithLifecycle()
    val adBlockEnabled by viewModel.adBlockEnabled.collectAsStateWithLifecycle()
    val reloadToken by viewModel.reloadToken.collectAsStateWithLifecycle()
    val ffmpegJobs by FfmpegJobTracker.snapshot.collectAsStateWithLifecycle()

    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    val detectedVideos = activeTab.detectedVideos
    val isPageLoading = activeTab.isPageLoading
    val canGoBack = activeTab.canGoBack

    val canClearPrevious = remember(detectedVideos, activeTab.currentPageUrl) {
        viewModel.hasDetectionsFromOtherPages(activeTab.id)
    }

    // Stable WebView instances keyed by tab id — never recreate on recomposition.
    val webViews = remember { mutableStateMapOf<Long, WebView>() }
    var showHistory by remember { mutableStateOf(false) }
    var listExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

    DisposableEffect(Unit) {
        onDispose {
            webViews.forEach { (tabId, webView) ->
                val state = Bundle()
                webView.saveState(state)
                viewModel.saveTabWebViewState(tabId, state)
                destroyWebView(webView)
            }
            webViews.clear()
        }
    }

    // Destroy WebViews for closed tabs.
    LaunchedEffect(tabs.map { it.id }) {
        val liveIds = tabs.map { it.id }.toSet()
        val stale = webViews.keys.filter { it !in liveIds }
        stale.forEach { id ->
            webViews.remove(id)?.let { destroyWebView(it) }
        }
    }

    LaunchedEffect(initialUrl) {
        val seed = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val normalized = normalizeUrl(seed)
        if (normalized.isBlank()) return@LaunchedEffect
        viewModel.openExternalUrl(normalized)
    }

    LaunchedEffect(pendingNavigateUrl) {
        val target = pendingNavigateUrl ?: return@LaunchedEffect
        val normalized = normalizeUrl(target)
        if (normalized.isNotBlank()) {
            val opened = viewModel.openExternalUrl(normalized)
            if (!opened) {
                Toast.makeText(
                    context,
                    "เปิดแท็บได้สูงสุด ${BrowserTab.MAX_TABS} แท็บ — เปิดในแท็บปัจจุบัน",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        viewModel.consumeNavigateRequest()
    }

    val previousCount = remember { mutableStateOf(0) }
    LaunchedEffect(activeTabId, detectedVideos.size) {
        if (detectedVideos.isNotEmpty()) {
            listExpanded = true
        }
        if (detectedVideos.size > previousCount.value) {
            snackbarHostState.showSnackbar("พบวิดีโอแล้ว (${detectedVideos.size})")
        }
        previousCount.value = detectedVideos.size
    }

    BackHandler(enabled = canGoBack) {
        val webView = webViews[activeTabId]
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
            viewModel.setTabCanGoBack(activeTabId, webView.canGoBack())
        } else {
            viewModel.setTabCanGoBack(activeTabId, false)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                CompactTopChrome(
                    detectedCount = detectedVideos.size,
                    urlInput = activeTab.urlInput,
                    onUrlChange = { viewModel.updateTabUrlInput(activeTabId, it) },
                    canGoBack = canGoBack,
                    onBack = {
                        webViews[activeTabId]?.let { webView ->
                            if (webView.canGoBack()) {
                                webView.goBack()
                                viewModel.setTabCanGoBack(activeTabId, webView.canGoBack())
                            }
                        }
                    },
                    onGo = {
                        if (activeTab.urlInput.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("กรุณาใส่ URL")
                            }
                            return@CompactTopChrome
                        }
                        val normalized = normalizeUrl(activeTab.urlInput)
                        viewModel.navigateTab(activeTabId, normalized)
                    },
                    onOpenDownloads = onOpenDownloads,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    useDesktopUa = useDesktopUa,
                    onToggleDesktopUa = { viewModel.setUseDesktopUa(!useDesktopUa) },
                    adBlockEnabled = adBlockEnabled,
                    onToggleAdBlock = {
                        menuExpanded = false
                        viewModel.setAdBlockEnabled(!adBlockEnabled)
                        Toast.makeText(
                            context,
                            if (!adBlockEnabled) "เปิดบล็อกโฆษณาแล้ว" else "ปิดบล็อกโฆษณาแล้ว",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
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
                        webViews.values.forEach { clearWebViewData(it) }
                        tabs.forEach { viewModel.clearDetectedUrls(it.id) }
                        Toast.makeText(context, "ล้างคุกกี้/แคชแล้ว", Toast.LENGTH_SHORT).show()
                        viewModel.reloadPage()
                    },
                    onClearDetected = {
                        menuExpanded = false
                        viewModel.clearDetectedUrls(activeTabId)
                    }
                )
                TabStrip(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelect = { viewModel.setActiveTab(it) },
                    onClose = { tabId ->
                        if (tabs.size <= 1) {
                            Toast.makeText(context, "ต้องมีอย่างน้อย 1 แท็บ", Toast.LENGTH_SHORT)
                                .show()
                            return@TabStrip
                        }
                        webViews.remove(tabId)?.let { destroyWebView(it) }
                        viewModel.closeTab(tabId)
                    },
                    onAdd = {
                        val id = viewModel.addTab()
                        if (id == null) {
                            Toast.makeText(
                                context,
                                "เปิดแท็บได้สูงสุด ${BrowserTab.MAX_TABS} แท็บ",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
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
                    onClearPrevious = {
                        if (canClearPrevious) {
                            val removed = viewModel.keepOnlyCurrentPageVideos(activeTabId)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (removed > 0) {
                                        "ล้างรายการเก่าแล้ว ($removed) — เหลือเฉพาะหน้านี้"
                                    } else {
                                        "ไม่มีรายการจากหน้าอื่น"
                                    }
                                )
                            }
                        } else {
                            viewModel.clearDetectedUrls(activeTabId)
                            scope.launch {
                                snackbarHostState.showSnackbar("ล้างรายการวิดีโอแล้ว")
                            }
                        }
                    },
                    canClearPrevious = canClearPrevious,
                    onDownloadItem = { item ->
                        listExpanded = false
                        val pageUrl = activeTab.currentPageUrl
                            ?: activeTab.urlInput.takeIf {
                                it.startsWith("http://") || it.startsWith("https://")
                            }
                        val pageTitle = activeTab.currentPageTitle
                        val userAgent = viewModel.currentUserAgent()
                        when (item.type) {
                            VideoType.MP4 -> {
                                scope.launch {
                                    viewModel.setDownloading(true)
                                    val filename = withContext(Dispatchers.IO) {
                                        DownloadFilenameResolver.resolve(
                                            mediaUrl = item.url,
                                            pageTitle = pageTitle,
                                            pageUrl = pageUrl,
                                            userAgent = userAgent,
                                            tabId = item.tabId,
                                            defaultExt = ".mp4"
                                        )
                                    }
                                    DownloadHelper.downloadMp4(
                                        context = context,
                                        url = item.url,
                                        suggestedName = filename,
                                        pageUrl = pageUrl,
                                        userAgent = userAgent,
                                        tabId = item.tabId
                                    )
                                    viewModel.setDownloading(false)
                                    snackbarHostState.showSnackbar(
                                        "เริ่มดาวน์โหลดแล้ว — อยู่หน้าเว็บต่อได้"
                                    )
                                }
                            }
                            VideoType.HLS -> DownloadHelper.handleHlsUrl(
                                context = context,
                                url = item.url,
                                onDownloadStarted = {
                                    viewModel.setDownloading(true)
                                    listExpanded = false
                                },
                                onDownloadFinished = {
                                    viewModel.setDownloading(false)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "เริ่มดาวน์โหลดแล้ว — อยู่หน้าเว็บต่อได้"
                                        )
                                    }
                                },
                                userAgent = userAgent,
                                refererUrl = pageUrl,
                                pageTitle = pageTitle,
                                tabId = item.tabId
                            )
                            VideoType.UNKNOWN -> {
                                scope.launch {
                                    viewModel.setDownloading(true)
                                    val filename = withContext(Dispatchers.IO) {
                                        DownloadFilenameResolver.resolve(
                                            mediaUrl = item.url,
                                            pageTitle = pageTitle,
                                            pageUrl = pageUrl,
                                            userAgent = userAgent,
                                            tabId = item.tabId,
                                            defaultExt = ".mp4"
                                        )
                                    }
                                    DownloadHelper.downloadMp4(
                                        context = context,
                                        url = item.url,
                                        suggestedName = filename,
                                        pageUrl = pageUrl,
                                        userAgent = userAgent,
                                        tabId = item.tabId
                                    )
                                    viewModel.setDownloading(false)
                                    snackbarHostState.showSnackbar(
                                        "เริ่มดาวน์โหลดแล้ว — อยู่หน้าเว็บต่อได้"
                                    )
                                }
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
            MultiTabWebViews(
                tabs = tabs,
                activeTabId = activeTabId,
                webViews = webViews,
                userAgent = viewModel.currentUserAgent(),
                adBlockEnabled = adBlockEnabled,
                reloadToken = reloadToken,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            )

            val activeWeb = webViews[activeTabId]
            if (activeTab.loadUrl.isNullOrBlank() &&
                (activeWeb?.url.isNullOrBlank() || activeWeb?.url == "about:blank") &&
                activeTab.urlInput.isBlank()
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
                    viewModel.navigateTab(activeTabId, selected)
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
private fun TabStrip(
    tabs: List<BrowserTab>,
    activeTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val selected = tab.id == activeTabId
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    tonalElevation = if (selected) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(min = 72.dp, max = 160.dp)
                            .clickable { onSelect(tab.id) }
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tab.displayTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onClose(tab.id) },
                            modifier = Modifier.size(width = 28.dp, height = 28.dp),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Text("×", fontSize = 14.sp)
                        }
                    }
                }
            }
            TextButton(
                onClick = onAdd,
                modifier = Modifier.size(width = 36.dp, height = 32.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
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
    adBlockEnabled: Boolean,
    onToggleAdBlock: () -> Unit,
    onHistory: () -> Unit,
    onReload: () -> Unit,
    onClearSiteData: () -> Unit,
    onClearDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    text = {
                        Text(
                            if (adBlockEnabled) "ปิดบล็อกโฆษณา" else "เปิดบล็อกโฆษณา"
                        )
                    },
                    onClick = onToggleAdBlock
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
            // Intentionally blank when empty — no placeholder hint.
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

/**
 * Hosts one WebView per tab inside a FrameLayout. Inactive WebViews stay attached
 * but hidden (GONE) so they are not destroyed/recreated on tab switch.
 */
@Composable
private fun MultiTabWebViews(
    tabs: List<BrowserTab>,
    activeTabId: Long,
    webViews: MutableMap<Long, WebView>,
    userAgent: String,
    adBlockEnabled: Boolean,
    reloadToken: Int,
    viewModel: VideoDownloaderViewModel,
    modifier: Modifier = Modifier
) {
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestAdBlock by rememberUpdatedState(adBlockEnabled)
    val context = LocalContext.current
    var appliedReloadToken by remember { mutableStateOf(reloadToken) }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { container ->
            // Ensure a WebView exists for every live tab.
            tabs.forEach { tab ->
                val existing = webViews[tab.id]
                val webView = existing ?: createTabWebView(
                    context = context,
                    tabId = tab.id,
                    userAgent = userAgent,
                    adBlockEnabled = { latestAdBlock },
                    viewModelProvider = { latestViewModel }
                ).also { created ->
                    webViews[tab.id] = created
                    container.addView(
                        created,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                if (webView.parent !== container) {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    container.addView(
                        webView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                webView.visibility = if (tab.id == activeTabId) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }

                if (webView.settings.userAgentString != userAgent) {
                    webView.settings.userAgentString = userAgent
                }

                val restored = latestViewModel.restoreTabWebViewState(tab.id)
                if (restored != null) {
                    webView.restoreState(restored)
                    latestViewModel.consumeTabLoadUrl(tab.id)
                } else if (tab.loadUrl != null) {
                    webView.loadUrl(tab.loadUrl)
                    latestViewModel.consumeTabLoadUrl(tab.id)
                }
            }

            // Remove views for tabs that no longer exist (destroy handled by LaunchedEffect).
            val liveIds = tabs.map { it.id }.toSet()
            (0 until container.childCount).mapNotNull { idx ->
                container.getChildAt(idx) as? WebView
            }.forEach { child ->
                val ownerId = webViews.entries.firstOrNull { it.value === child }?.key
                if (ownerId == null || ownerId !in liveIds) {
                    container.removeView(child)
                }
            }

            if (reloadToken != appliedReloadToken) {
                appliedReloadToken = reloadToken
                webViews.values.forEach { webView ->
                    if (!webView.url.isNullOrBlank() && webView.url != "about:blank") {
                        webView.reload()
                    }
                }
            }

            webViews[activeTabId]?.let { webView ->
                latestViewModel.setTabCanGoBack(activeTabId, webView.canGoBack())
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createTabWebView(
    context: Context,
    tabId: Long,
    userAgent: String,
    adBlockEnabled: () -> Boolean,
    viewModelProvider: () -> VideoDownloaderViewModel
): WebView {
    return WebView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = userAgent
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        WebViewCookieHelper.enableFor(this)

        webViewClient = object : VideoInterceptingWebViewClient(
            onVideoUrlDetected = { url, type ->
                viewModelProvider().onVideoUrlDetected(tabId, url, type)
            },
            adBlockEnabled = adBlockEnabled,
            tabId = tabId
        ) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val vm = viewModelProvider()
                vm.setPageLoading(tabId, true)
                vm.setCurrentPageUrl(tabId, url)
                vm.setTabCanGoBack(tabId, view?.canGoBack() == true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                WebViewCookieHelper.flush()
                val vm = viewModelProvider()
                vm.setPageLoading(tabId, false)
                vm.setCurrentPageUrl(tabId, url)
                vm.setTabCanGoBack(tabId, view?.canGoBack() == true)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                viewModelProvider().setCurrentPageTitle(tabId, title)
            }
        }
    }
}

@Composable
private fun DetectedListSection(
    videos: List<DetectedVideoUrl>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClearPrevious: () -> Unit,
    canClearPrevious: Boolean,
    onDownloadItem: (DetectedVideoUrl) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = 4.dp),
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
                if (videos.isNotEmpty() && !expanded && !canClearPrevious) {
                    Text(
                        text = "แตะเพื่อดาวน์โหลด",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (videos.isNotEmpty()) {
                TextButton(
                    onClick = onClearPrevious,
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(
                        text = if (canClearPrevious) "ล้างเก่า" else "ล้าง",
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
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
                            .heightIn(max = 228.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(videos, key = { it.url }) { item ->
                            DetectedVideoRow(
                                item = item,
                                onClick = { onDownloadItem(item) },
                                onCopy = { MediaUrlActions.copyUrl(context, item.url) },
                                onShare = { MediaUrlActions.shareUrl(context, item.url) }
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
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.url,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                val metaLine = formatVideoMeta(item)
                if (metaLine != null) {
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TypeBadge(type = item.type)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCopy,
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("คัดลอก", fontSize = 12.sp)
            }
            TextButton(
                onClick = onShare,
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text("แชร์", fontSize = 12.sp)
            }
        }
    }
}

private fun formatVideoMeta(item: DetectedVideoUrl): String? {
    return when (item.metaState) {
        VideoMetaState.PENDING, VideoMetaState.LOADING -> "กำลังอ่านขนาด…"
        VideoMetaState.UNAVAILABLE -> "ขนาด/ความยาวไม่ทราบ"
        VideoMetaState.READY -> {
            val parts = buildList {
                formatDuration(item.durationMs)?.let { add(it) }
                formatBytes(item.contentLengthBytes, item.sizeIsEstimate)?.let { add(it) }
            }
            parts.joinToString(" · ").ifBlank { "ขนาด/ความยาวไม่ทราบ" }
        }
    }
}

private fun formatBytes(bytes: Long?, estimate: Boolean): String? {
    if (bytes == null || bytes <= 0L) return null
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val text = if (unit == 0) {
        "${bytes.toInt()} ${units[unit]}"
    } else {
        String.format("%.1f %s", value, units[unit])
    }
    return if (estimate) "~$text" else text
}

private fun formatDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalSec = (durationMs + 500L) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
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

internal fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
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

private fun destroyWebView(webView: WebView) {
    runCatching {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.webChromeClient = null
        @Suppress("DEPRECATION")
        webView.webViewClient = android.webkit.WebViewClient()
        webView.destroy()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
