package com.saha.videodownloader.ui

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

            if (playingItem != null) {
                OfflinePlayer(
                    item = playingItem,
                    repository = viewModel.repository(),
                    onClose = { viewModel.stopPlayback() },
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
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val isActiveJob = item.id.startsWith("ffmpeg-job:") &&
        (item.state == LibraryDownload.State.DOWNLOADING ||
            item.state == LibraryDownload.State.QUEUED)

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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun OfflinePlayer(
    item: LibraryDownload,
    repository: OfflineDownloadRepository,
    onClose: () -> Unit,
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

    Column(modifier = modifier) {
        Row(
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
