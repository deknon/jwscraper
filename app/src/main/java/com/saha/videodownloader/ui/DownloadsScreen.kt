package com.saha.videodownloader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.saha.videodownloader.download.DownloadPaths
import com.saha.videodownloader.download.DownloadSettingsStore
import com.saha.videodownloader.download.OfflineDownloadRepository
import com.saha.videodownloader.model.LibraryDownload
import com.saha.videodownloader.viewmodel.DownloadsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val maxConcurrent by viewModel.maxConcurrent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playingItem = downloads.firstOrNull { it.id == playingId }
    var showClearConfirm by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text("รายการดาวน์โหลด", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("กลับ") }
                },
                actions = {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = downloads.isNotEmpty()
                    ) {
                        Text("ล้างประวัติ")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            ConcurrentDownloadsSettings(
                maxConcurrent = maxConcurrent,
                onChange = { viewModel.setMaxConcurrent(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                text = "บันทึกไฟล์ที่ Download/${DownloadPaths.SUBFOLDER}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            HorizontalDivider()

            if (playingItem != null && !fullscreen) {
                OfflinePlayer(
                    item = playingItem,
                    repository = viewModel.repository(),
                    onClose = { viewModel.stopPlayback() },
                    fullscreen = false,
                    onToggleFullscreen = { fullscreen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
                HorizontalDivider()
            }

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ยังไม่มีรายการดาวน์โหลด",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(downloads, key = { it.id }) { item ->
                        DownloadRow(
                            item = item,
                            onPlay = { viewModel.play(item.id) },
                            onOpen = {
                                item.contentUri?.let { uri ->
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(uri), "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            onShare = {
                                item.contentUri?.let { uri ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(intent, "แชร์วิดีโอ")
                                        )
                                    }
                                }
                            },
                            onCopyPageLink = {
                                val link = item.pageUrl?.takeIf {
                                    it.startsWith("http://") || it.startsWith("https://")
                                }
                                if (link.isNullOrBlank()) {
                                    Toast.makeText(
                                        context,
                                        "ไม่มีลิ้งค์หน้าเว็บต้นทาง",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val clipboard =
                                        context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("page_url", link)
                                    )
                                    Toast.makeText(
                                        context,
                                        "คัดลอกลิ้งค์ต้นทางแล้ว",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onCancel = { viewModel.cancelFfmpeg(item) },
                            onRetry = { viewModel.retryFfmpeg(item) },
                            onRemove = { viewModel.remove(item) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (fullscreen && playingItem != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            OfflinePlayer(
                item = playingItem,
                repository = viewModel.repository(),
                onClose = {
                    fullscreen = false
                    viewModel.stopPlayback()
                },
                fullscreen = true,
                onToggleFullscreen = { fullscreen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("ล้างประวัติการดาวน์โหลด") },
            text = { Text("ลบรายการที่เสร็จแล้วและรายการเก่าออกจากหน้านี้หรือไม่") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearHistory()
                    }
                ) {
                    Text("ล้าง")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

@Composable
private fun ConcurrentDownloadsSettings(
    maxConcurrent: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ดาวน์โหลดพร้อมกันสูงสุด",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "ใช้กับคิว HLS mux และ Media3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange(maxConcurrent - 1) },
                enabled = maxConcurrent > DownloadSettingsStore.MIN_CONCURRENT
            ) {
                Text("−")
            }
            Text(
                text = "$maxConcurrent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            OutlinedButton(
                onClick = { onChange(maxConcurrent + 1) },
                enabled = maxConcurrent < DownloadSettingsStore.MAX_CONCURRENT
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: LibraryDownload,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyPageLink: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val isActiveJob = item.id.startsWith("ffmpeg-job:") &&
        (item.state == LibraryDownload.State.DOWNLOADING ||
            item.state == LibraryDownload.State.QUEUED)
    val hasPageLink = !item.pageUrl.isNullOrBlank() &&
        (item.pageUrl.startsWith("http://") || item.pageUrl.startsWith("https://"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasPageLink) {
                    Text(
                        text = item.pageUrl.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        when (item.kind) {
                            LibraryDownload.Kind.MEDIA3_CACHE -> "Media3"
                            LibraryDownload.Kind.FFMPEG_MP4 -> "MP4"
                        }
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stateLabel(item),
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = formatDownloadTimes(item),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (item.state == LibraryDownload.State.DOWNLOADING ||
            item.state == LibraryDownload.State.QUEUED
        ) {
            LinearProgressIndicator(
                progress = { item.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                isActiveJob -> {
                    OutlinedButton(onClick = onCancel) {
                        Text("ยกเลิก")
                    }
                }
                item.kind == LibraryDownload.Kind.MEDIA3_CACHE -> {
                    Button(onClick = onPlay, enabled = item.canPlay) {
                        Text("เล่น")
                    }
                    OutlinedButton(onClick = onRemove) {
                        Text("ลบ")
                    }
                }
                else -> {
                    Button(onClick = onOpen, enabled = item.canPlay) {
                        Text("เปิด")
                    }
                    OutlinedButton(onClick = onShare, enabled = item.canPlay) {
                        Text("แชร์")
                    }
                    OutlinedButton(onClick = onRemove) {
                        Text("ลบ")
                    }
                }
            }
            if (hasPageLink) {
                OutlinedButton(onClick = onCopyPageLink) {
                    Text("คัดลอกลิ้งค์")
                }
            }
            if (item.state == LibraryDownload.State.FAILED && item.id.startsWith("ffmpeg-job:")) {
                Button(onClick = onRetry) {
                    Text("ลองใหม่")
                }
                OutlinedButton(onClick = onRemove) {
                    Text("ปิด")
                }
            }
        }
    }
}

private fun stateLabel(item: LibraryDownload): String {
    val pct = (item.progressPercent * 100).toInt()
    val detail = item.statusMessage
    return when (item.state) {
        LibraryDownload.State.QUEUED -> detail ?: "รอคิว"
        LibraryDownload.State.DOWNLOADING -> detail ?: "กำลังดาวน์โหลด $pct%"
        LibraryDownload.State.COMPLETED -> "เสร็จแล้ว"
        LibraryDownload.State.FAILED -> detail ?: "ล้มเหลว"
        LibraryDownload.State.REMOVING -> "กำลังลบ…"
        LibraryDownload.State.RESTARTING -> "เริ่มใหม่…"
        LibraryDownload.State.STOPPED -> "หยุดชั่วคราว"
    }
}

private fun formatDownloadTimes(item: LibraryDownload): String {
    val formatter = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.SHORT
    )
    val started = item.startedAtMs?.takeIf { it > 0L }?.let { formatter.format(java.util.Date(it)) }
    val completed =
        item.completedAtMs?.takeIf { it > 0L }?.let { formatter.format(java.util.Date(it)) }
    return buildString {
        if (started != null) append("เริ่ม: ").append(started)
        if (completed != null) {
            if (isNotEmpty()) append(" · ")
            append("สิ้นสุด: ").append(completed)
        }
    }.ifBlank { "เวลาเริ่ม/สิ้นสุดยังไม่พร้อม" }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun OfflinePlayer(
    item: LibraryDownload,
    repository: OfflineDownloadRepository,
    onClose: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val download = remember(item.id) { repository.getMedia3Download(item.id) }

    val player = remember(item.id) {
        val cacheFactory = repository.buildCacheDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                val mediaItem = download?.request?.toMediaItem()
                    ?: MediaItem.fromUri(item.sourceUrl)
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(fullscreen) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        onDispose {
            if (fullscreen) {
                activity?.requestedOrientation =
                    previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    Column(modifier = modifier) {
        if (!fullscreen) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "เล่นออฟไลน์: ${item.title}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Text("ปิด")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onToggleFullscreen) {
                Text(if (fullscreen) "ย่อ" else "เต็มจอ")
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                    this.player = player
                }
            },
            update = { it.player = player }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
