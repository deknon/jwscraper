package com.saha.videodownloader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.saha.videodownloader.download.CastIntentHelper
import com.saha.videodownloader.download.DownloadFilenameResolver
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.FfmpegJobTracker
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.LibraryDownload
import com.saha.videodownloader.model.TabState
import com.saha.videodownloader.model.VideoMetaState
import com.saha.videodownloader.model.VideoType
import com.saha.videodownloader.viewmodel.DetectionFilters
import com.saha.videodownloader.viewmodel.TabListReducer
import com.saha.videodownloader.viewmodel.VideoDownloaderViewModel
import com.saha.videodownloader.webview.AdBlockStore
import com.saha.videodownloader.webview.TabWebViewHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: VideoDownloaderViewModel,
    tabWebViews: TabWebViewHolder,
    onOpenDownloads: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()
    val useDesktopUa by viewModel.useDesktopUa.collectAsStateWithLifecycle()
    val adBlockEnabled by AdBlockStore.enabled.collectAsStateWithLifecycle()
    val blockedPopupCount by viewModel.blockedPopupCount.collectAsStateWithLifecycle()
    val ffmpegJobs by FfmpegJobTracker.snapshot.collectAsStateWithLifecycle()

    val activeTab: TabState? = tabs.firstOrNull { it.id == activeTabId }
    val canClearPrevious = DetectionFilters.hasDetectionsFromOtherPages(
        items = detectedVideos,
        currentPageUrl = activeTab?.pageUrl
    )
    val activeTabDetectedCount = DetectionFilters.countForTab(detectedVideos, activeTabId)

    var showHistory by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
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

    // Applies queued navigations. A background tab that already has content is
    // left alone until it is selected, so a UA switch does not reload 8 tabs
    // at once; a tab that has never loaded anything always loads immediately.
    LaunchedEffect(activeTabId, tabs.map { it.id to it.pendingLoadUrl }) {
        tabs.forEach { tab ->
            val target = tab.pendingLoadUrl ?: return@forEach
            val webView = tabWebViews.getOrCreate(tab.id, allowAutoplay = !tab.neverSelected)
            val isFresh = webView?.url.isNullOrBlank()
            if (tab.id == activeTabId || isFresh) {
                tabWebViews.loadUrl(tab.id, target)
                viewModel.consumePendingLoad(tab.id)
            }
        }
    }

    LaunchedEffect(useDesktopUa) {
        tabWebViews.applyUserAgent(viewModel.currentUserAgent())
        val active = activeTabId ?: return@LaunchedEffect
        viewModel.markOtherTabsForReload(active)
        tabWebViews.reload(active)
    }

    val previousCount = remember { mutableStateOf(0) }
    LaunchedEffect(activeTabDetectedCount) {
        if (activeTabDetectedCount > 0) {
            listExpanded = true
        }
        if (activeTabDetectedCount > previousCount.value) {
            snackbarHostState.showSnackbar("พบวิดีโอแล้ว ($activeTabDetectedCount)")
        }
        previousCount.value = activeTabDetectedCount
    }

    // Back goes back inside the tab, then closes the tab (returning to its
    // opener), then falls through to the system so the app can exit.
    BackHandler(enabled = activeTab?.canGoBack == true || tabs.size > 1) {
        val id = activeTabId
        if (id != null && tabWebViews.goBack(id)) {
            viewModel.setTabCanGoBack(id, tabWebViews.canGoBack(id))
        } else if (tabs.size > 1) {
            viewModel.closeActiveTab()
        }
    }

    fun go(rawUrl: String) {
        val id = activeTabId ?: return
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isEmpty()) return
        viewModel.requestLoad(id, normalized)
    }

    fun openNewTab(url: String? = null) {
        if (viewModel.openTab(url) == null) {
            Toast.makeText(
                context,
                "เปิดได้สูงสุด ${TabListReducer.MAX_TABS} แท็บ",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Put chrome in Scaffold topBar/bottomBar so WebView cannot paint over them.
    // Custom bars must pad status/navigation bars themselves under enableEdgeToEdge().
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    CompactChromeRow(
                        detectedCount = detectedVideos.size,
                        // Keyed so BasicTextField's caret/selection does not
                        // carry over from the previous tab's URL.
                        urlField = {
                            key(activeTabId) {
                                CompactUrlField(
                                    value = activeTab?.urlInput.orEmpty(),
                                    onValueChange = { text ->
                                        activeTabId?.let { viewModel.setTabUrlInput(it, text) }
                                    },
                                    onGo = { go(activeTab?.urlInput.orEmpty()) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                )
                            }
                        },
                        canGoBack = activeTab?.canGoBack == true,
                        onBack = {
                            activeTabId?.let { id ->
                                if (tabWebViews.goBack(id)) {
                                    viewModel.setTabCanGoBack(id, tabWebViews.canGoBack(id))
                                }
                            }
                        },
                        onGo = { go(activeTab?.urlInput.orEmpty()) },
                        onOpenDownloads = onOpenDownloads,
                        menuExpanded = menuExpanded,
                        onMenuExpandedChange = { menuExpanded = it },
                        tabCount = tabs.size,
                        onNewTab = {
                            menuExpanded = false
                            openNewTab()
                        },
                        onManageTabs = {
                            menuExpanded = false
                            showTabs = true
                        },
                        useDesktopUa = useDesktopUa,
                        onToggleDesktopUa = { viewModel.setUseDesktopUa(!useDesktopUa) },
                        adBlockEnabled = adBlockEnabled,
                        blockedAdCount = activeTab?.blockedAdCount ?: 0,
                        onToggleAdBlock = {
                            menuExpanded = false
                            val next = !adBlockEnabled
                            AdBlockStore.setEnabled(context, next)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (next) {
                                        "เปิดบล็อกโฆษณา — รีเฟรชเพื่อให้มีผลเต็มที่"
                                    } else {
                                        "ปิดบล็อกโฆษณาแล้ว"
                                    }
                                )
                            }
                        },
                        blockedPopupCount = blockedPopupCount,
                        onAllowPopups = {
                            menuExpanded = false
                            activeTabId?.let { id ->
                                tabWebViews.peek(id)
                                    ?.settings
                                    ?.javaScriptCanOpenWindowsAutomatically = true
                                scope.launch {
                                    snackbarHostState.showSnackbar("อนุญาตป๊อปอัปในแท็บนี้แล้ว")
                                }
                            }
                        },
                        onHistory = {
                            menuExpanded = false
                            showHistory = true
                        },
                        onReload = {
                            menuExpanded = false
                            activeTabId?.let { id ->
                                val tab = tabs.firstOrNull { it.id == id }
                                val crashedUrl = tab?.pageUrl?.takeIf { tab.isCrashed }
                                if (crashedUrl != null) {
                                    viewModel.requestLoad(id, crashedUrl)
                                } else {
                                    tabWebViews.reload(id)
                                }
                            }
                        },
                        onClearSiteData = {
                            menuExpanded = false
                            tabWebViews.clearSiteData()
                            viewModel.clearDetectedUrls()
                            Toast.makeText(
                                context,
                                "ล้างคุกกี้/แคชแล้ว (มีผลกับทุกแท็บ)",
                                Toast.LENGTH_SHORT
                            ).show()
                            activeTabId?.let { tabWebViews.reload(it) }
                        },
                        onClearDetected = {
                            menuExpanded = false
                            viewModel.clearDetectedUrls()
                        }
                    )

                    AnimatedVisibility(visible = tabs.size > 1) {
                        TabStrip(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            detectedCountFor = { tabId ->
                                DetectionFilters.countForTab(detectedVideos, tabId)
                            },
                            onSelect = { viewModel.selectTab(it) },
                            onClose = { viewModel.closeTab(it) },
                            onNewTab = { openNewTab() }
                        )
                    }
                }
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
                            val removed = viewModel.keepOnlyActiveTabVideos()
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
                            viewModel.clearDetectedUrls()
                            scope.launch {
                                snackbarHostState.showSnackbar("ล้างรายการวิดีโอแล้ว")
                            }
                        }
                    },
                    canClearPrevious = canClearPrevious,
                    onDownloadItem = { item ->
                        // Stay on the WebView — never navigate to the downloads library.
                        listExpanded = false
                        // Prefer the page the item was found on: after navigating
                        // away, the active tab's URL is the wrong Referer and the
                        // CDN answers 403.
                        val pageUrl = item.pageUrl ?: activeTab?.pageUrl
                        val pageTitle = item.pageTitle ?: activeTab?.title
                        val userAgent = viewModel.currentUserAgent()
                        when (item.type) {
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
                                pageTitle = pageTitle
                            )
                            VideoType.MP4, VideoType.UNKNOWN -> {
                                scope.launch {
                                    viewModel.setDownloading(true)
                                    val filename = withContext(Dispatchers.IO) {
                                        DownloadFilenameResolver.resolve(
                                            mediaUrl = item.url,
                                            pageTitle = pageTitle,
                                            pageUrl = pageUrl,
                                            userAgent = userAgent,
                                            defaultExt = ".mp4"
                                        )
                                    }
                                    DownloadHelper.downloadMp4(
                                        context = context,
                                        url = item.url,
                                        suggestedName = filename,
                                        pageUrl = pageUrl,
                                        userAgent = userAgent
                                    )
                                    viewModel.setDownloading(false)
                                    snackbarHostState.showSnackbar(
                                        "เริ่มดาวน์โหลดแล้ว — อยู่หน้าเว็บต่อได้"
                                    )
                                }
                            }
                        }
                    },
                    onCastItem = { item ->
                        val ok = CastIntentHelper.castVideo(
                            context = context,
                            url = item.url,
                            type = item.type,
                            pageUrl = item.pageUrl ?: activeTab?.pageUrl,
                            userAgent = viewModel.currentUserAgent(),
                            title = item.pageTitle ?: activeTab?.title
                        )
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "ไม่พบแอปที่เล่นวิดีโอได้ — ลองติดตั้ง Web Video Cast / VLC"
                                )
                            }
                        }
                    },
                    onCopyItem = { item ->
                        CastIntentHelper.copyUrl(context, item.url)
                        scope.launch { snackbarHostState.showSnackbar("คัดลอก URL แล้ว") }
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
            TabViewport(
                holder = tabWebViews,
                tabIds = tabs.map { it.id },
                activeTabId = activeTabId,
                autoplayFor = { tabId ->
                    tabs.firstOrNull { it.id == tabId }?.neverSelected != true
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            )

            val shownTab = activeTab
            if (shownTab != null && shownTab.isCrashed) {
                CrashedTabNotice(
                    onReload = {
                        shownTab.pageUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { viewModel.requestLoad(shownTab.id, it) }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (shownTab != null && shownTab.isBlank) {
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

            if (shownTab?.isLoading == true || isDownloading) {
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
                    go(selected)
                },
                onClear = { viewModel.clearHistory() },
                onDismiss = { showHistory = false }
            )
        }

        if (showTabs) {
            TabsDialog(
                tabs = tabs,
                activeTabId = activeTabId,
                detectedCountFor = { tabId ->
                    DetectionFilters.countForTab(detectedVideos, tabId)
                },
                onSelect = {
                    viewModel.selectTab(it)
                    showTabs = false
                },
                onClose = { viewModel.closeTab(it) },
                onNewTab = {
                    showTabs = false
                    openNewTab()
                },
                onDismiss = { showTabs = false }
            )
        }
    }
}

@Composable
private fun CompactChromeRow(
    detectedCount: Int,
    urlField: @Composable RowScope.() -> Unit,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onGo: () -> Unit,
    onOpenDownloads: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    tabCount: Int,
    onNewTab: () -> Unit,
    onManageTabs: () -> Unit,
    useDesktopUa: Boolean,
    onToggleDesktopUa: () -> Unit,
    adBlockEnabled: Boolean,
    blockedAdCount: Int,
    onToggleAdBlock: () -> Unit,
    blockedPopupCount: Int,
    onAllowPopups: () -> Unit,
    onHistory: () -> Unit,
    onReload: () -> Unit,
    onClearSiteData: () -> Unit,
    onClearDetected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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

        urlField()

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
                text = { Text("แท็บใหม่ ($tabCount/${TabListReducer.MAX_TABS})") },
                onClick = onNewTab,
                enabled = tabCount < TabListReducer.MAX_TABS
            )
            DropdownMenuItem(
                text = { Text("จัดการแท็บ") },
                onClick = onManageTabs
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
                        buildString {
                            append("บล็อกโฆษณา: ")
                            append(if (adBlockEnabled) "เปิด" else "ปิด")
                            if (adBlockEnabled && blockedAdCount > 0) {
                                append(" ($blockedAdCount)")
                            }
                        }
                    )
                },
                onClick = onToggleAdBlock
            )
            if (blockedPopupCount > 0) {
                DropdownMenuItem(
                    text = { Text("อนุญาตป๊อปอัปในแท็บนี้ ($blockedPopupCount)") },
                    onClick = onAllowPopups
                )
            }
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
private fun CrashedTabNotice(onReload: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "แท็บนี้หยุดทำงาน (หน่วยความจำไม่พอ)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onReload) { Text("โหลดใหม่") }
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

/**
 * One [FrameLayout] holds every tab's WebView; only the active one is visible.
 * The views are never re-parented on a tab switch — detaching a WebView drops
 * its surface and stalls `<video>` playback.
 */
@Composable
private fun TabViewport(
    holder: TabWebViewHolder,
    tabIds: List<Long>,
    activeTabId: Long?,
    autoplayFor: (Long) -> Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { container ->
            holder.syncInto(container, tabIds, activeTabId, autoplayFor)
        },
        // Detach only. Destroying a tab is the ViewModel's job.
        onRelease = { container -> holder.detachFrom(container) }
    )
}

@Composable
private fun DetectedListSection(
    videos: List<DetectedVideoUrl>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClearPrevious: () -> Unit,
    canClearPrevious: Boolean,
    onDownloadItem: (DetectedVideoUrl) -> Unit,
    onCastItem: (DetectedVideoUrl) -> Unit,
    onCopyItem: (DetectedVideoUrl) -> Unit,
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
                    .heightIn(max = 200.dp)
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
                            .heightIn(max = 188.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(videos, key = { it.url }) { item ->
                            DetectedVideoRow(
                                item = item,
                                onClick = { onDownloadItem(item) },
                                onCast = { onCastItem(item) },
                                onCopy = { onCopyItem(item) }
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
    onCast: () -> Unit,
    onCopy: () -> Unit
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
        TextButton(
            onClick = onCast,
            modifier = Modifier.size(width = 40.dp, height = 36.dp),
            contentPadding = ButtonDefaults.TextButtonContentPadding
        ) {
            Text("▶", fontSize = 15.sp)
        }
        TextButton(
            onClick = onCopy,
            modifier = Modifier.size(width = 40.dp, height = 36.dp),
            contentPadding = ButtonDefaults.TextButtonContentPadding
        ) {
            Text("⧉", fontSize = 15.sp)
        }
        TypeBadge(type = item.type)
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

/** Blank input stays blank so the URL field shows its hint instead of "https://". */
internal fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
