package com.saha.videodownloader.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import com.arthenica.ffmpegkit.FFmpegKit
import com.saha.videodownloader.model.LibraryDownload
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.net.URI

/**
 * Combines Media3 [DownloadManager] state with ffmpeg history into one library list.
 */
@OptIn(UnstableApi::class)
class OfflineDownloadRepository(context: Context) {

    private val appContext = context.applicationContext
    private val historyStore = FfmpegHistoryStore(appContext)

    fun observeLibrary(): Flow<List<LibraryDownload>> =
        combine(
            observeMedia3Downloads(),
            observeFfmpegHistory(),
            FfmpegJobTracker.snapshot
        ) { media3, ffmpegHistory, ffmpegJobs ->
            val activeFfmpeg = ffmpegJobs.map { job ->
                LibraryDownload(
                    id = job.id,
                    title = job.title,
                    sourceUrl = job.sourceUrl,
                    kind = LibraryDownload.Kind.FFMPEG_MP4,
                    state = job.state,
                    progressPercent = job.progressPercent,
                    contentUri = null,
                    updatedAtMs = job.updatedAtMs,
                    statusMessage = job.message
                )
            }
            (media3 + activeFfmpeg + ffmpegHistory).sortedByDescending { it.updatedAtMs }
        }

    fun recordFfmpegSuccess(sourceUrl: String, title: String, contentUri: String) {
        historyStore.add(
            FfmpegHistoryStore.Entry(
                id = "ffmpeg:$contentUri",
                title = title,
                sourceUrl = sourceUrl,
                contentUri = contentUri,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    fun removeFfmpegEntry(id: String) {
        if (id.startsWith("ffmpeg-job:")) {
            cancelFfmpegJob(id)
            return
        }
        historyStore.remove(id)
    }

    fun cancelFfmpegJob(id: String) {
        val sessionId = FfmpegJobTracker.get(id)?.sessionId
        if (sessionId != null) {
            FFmpegKit.cancel(sessionId)
        }
        FfmpegJobTracker.remove(id)
        // Ask the mux service to drop its foreground notification.
        runCatching {
            appContext.startService(
                android.content.Intent(appContext, FfmpegMuxService::class.java).apply {
                    action = FfmpegMuxService.ACTION_CANCEL
                    putExtra(FfmpegMuxService.EXTRA_JOB_ID, id)
                }
            )
        }
    }

    fun removeMedia3Download(id: String) {
        DownloadService.sendRemoveDownload(
            appContext,
            VideoDownloadService::class.java,
            id,
            /* foreground = */ false
        )
    }

    fun buildCacheDataSourceFactory(): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(Media3DownloadUtil.getDownloadCache(appContext))
            .setUpstreamDataSourceFactory(Media3DownloadUtil.buildHttpDataSourceFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun getMedia3Download(id: String): Download? =
        Media3DownloadUtil.getDownloadManager(appContext).downloadIndex.getDownload(id)

    private fun observeMedia3Downloads(): Flow<List<LibraryDownload>> = callbackFlow {
        val manager = Media3DownloadUtil.getDownloadManager(appContext)

        fun emitCurrent() {
            val fromIndex = manager.downloadIndex.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.download)
                    }
                }
            }
            val unique = fromIndex.associateBy { it.request.id }.values
            trySend(unique.map { it.toLibraryDownload() })
        }

        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                emitCurrent()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                emitCurrent()
            }

            override fun onIdle(downloadManager: DownloadManager) {
                emitCurrent()
            }
        }

        manager.addListener(listener)
        emitCurrent()
        awaitClose { manager.removeListener(listener) }
    }

    private fun observeFfmpegHistory(): Flow<List<LibraryDownload>> =
        FfmpegHistoryStore.changes
            .onStart { emit(Unit) }
            .map {
                historyStore.getAll().map { entry ->
                    LibraryDownload(
                        id = entry.id,
                        title = entry.title,
                        sourceUrl = entry.sourceUrl,
                        kind = LibraryDownload.Kind.FFMPEG_MP4,
                        state = LibraryDownload.State.COMPLETED,
                        progressPercent = 1f,
                        contentUri = entry.contentUri,
                        updatedAtMs = entry.createdAtMs,
                        statusMessage = null
                    )
                }
            }

    private fun Download.toLibraryDownload(): LibraryDownload {
        val uri = request.uri.toString()
        return LibraryDownload(
            id = request.id,
            title = shortTitle(uri),
            sourceUrl = uri,
            kind = LibraryDownload.Kind.MEDIA3_CACHE,
            state = state.toLibraryState(),
            progressPercent = percentDownloaded.coerceIn(0f, 100f) / 100f,
            contentUri = null,
            updatedAtMs = updateTimeMs,
            statusMessage = null
        )
    }

    private fun Int.toLibraryState(): LibraryDownload.State = when (this) {
        Download.STATE_QUEUED -> LibraryDownload.State.QUEUED
        Download.STATE_STOPPED -> LibraryDownload.State.STOPPED
        Download.STATE_DOWNLOADING -> LibraryDownload.State.DOWNLOADING
        Download.STATE_COMPLETED -> LibraryDownload.State.COMPLETED
        Download.STATE_FAILED -> LibraryDownload.State.FAILED
        Download.STATE_REMOVING -> LibraryDownload.State.REMOVING
        Download.STATE_RESTARTING -> LibraryDownload.State.RESTARTING
        else -> LibraryDownload.State.QUEUED
    }

    private fun shortTitle(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host ?: "hls"
            val path = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "stream"
            "$host/$path"
        } catch (_: Exception) {
            url.take(48)
        }
    }
}
