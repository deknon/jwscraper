package com.saha.videodownloader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoType
import com.saha.videodownloader.viewmodel.VideoDownloaderViewModel
import com.saha.videodownloader.webview.VideoInterceptingWebViewClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: VideoDownloaderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val isPageLoading by viewModel.isPageLoading.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val selectedUrl by viewModel.selectedUrl.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf("https://") }
    var webViewLoadUrl by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val previousCount = remember { mutableStateOf(0) }
    LaunchedEffect(detectedVideos.size) {
        if (detectedVideos.size > previousCount.value) {
            snackbarHostState.showSnackbar("พบวิดีโอแล้ว (${detectedVideos.size})")
        }
        previousCount.value = detectedVideos.size
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
                onGo = {
                    val normalized = normalizeUrl(urlInput)
                    urlInput = normalized
                    viewModel.clearDetectedUrls()
                    webViewLoadUrl = normalized
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

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
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            HorizontalDivider()

            DetectedListSection(
                videos = detectedVideos,
                selectedUrl = selectedUrl,
                onSelect = { viewModel.selectUrl(it) },
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
                    .height(220.dp)
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun UrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoWebView(
    loadUrl: String?,
    viewModel: VideoDownloaderViewModel,
    modifier: Modifier = Modifier
) {
    val latestViewModel by rememberUpdatedState(viewModel)

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
                settings.userAgentString = DownloadHelper.MOBILE_CHROME_UA
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
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        latestViewModel.setPageLoading(false)
                    }
                }

                webChromeClient = WebChromeClient()
            }
        },
        update = { webView ->
            val target = loadUrl
            if (target != null && webView.url != target) {
                webView.loadUrl(target)
            }
        }
    )
}

@Composable
private fun DetectedListSection(
    videos: List<DetectedVideoUrl>,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
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
                text = "วิดีโอที่ตรวจพบ (${videos.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row {
                OutlinedButton(
                    onClick = onClear,
                    enabled = videos.isNotEmpty()
                ) {
                    Text("ล้างรายการ")
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

        Spacer(modifier = Modifier.height(8.dp))

        if (videos.isEmpty()) {
            Text(
                text = "ยังไม่พบวิดีโอ — เปิดหน้าเว็บแล้วรอให้รายการขึ้น",
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
                        onSelect = { onSelect(item.url) }
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
    onSelect: () -> Unit
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
        Spacer(modifier = Modifier.width(8.dp))
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
