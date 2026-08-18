package com.saha.videodownloader.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.concurrent.Executors

/**
 * Shared Media3 download infrastructure (cache + DownloadManager singleton).
 */
@OptIn(UnstableApi::class)
object Media3DownloadUtil {

    private const val DOWNLOAD_CONTENT_DIRECTORY = "hls_downloads"

    @Volatile
    private var downloadCache: Cache? = null

    @Volatile
    private var databaseProvider: DatabaseProvider? = null

    @Volatile
    private var downloadManager: DownloadManager? = null

    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        downloadCache?.let { return it }
        val appContext = context.applicationContext
        val downloadDir = File(appContext.getExternalFilesDir(null), DOWNLOAD_CONTENT_DIRECTORY)
        val cache = SimpleCache(
            downloadDir,
            NoOpCacheEvictor(),
            getDatabaseProvider(appContext)
        )
        downloadCache = cache
        return cache
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        downloadManager?.let { return it }
        val appContext = context.applicationContext
        val manager = DownloadManager(
            appContext,
            getDatabaseProvider(appContext),
            getDownloadCache(appContext),
            buildHttpDataSourceFactory(),
            Executors.newFixedThreadPool(/* nThreads = */ DownloadSettingsStore.MAX_CONCURRENT)
        ).apply {
            maxParallelDownloads = DownloadSettingsStore.getMaxConcurrent(appContext)
            requirements = Requirements(Requirements.NETWORK)
        }
        downloadManager = manager
        return manager
    }

    fun applyMaxParallelDownloads(context: Context, max: Int) {
        val clamped = max.coerceIn(
            DownloadSettingsStore.MIN_CONCURRENT,
            DownloadSettingsStore.MAX_CONCURRENT
        )
        runCatching {
            getDownloadManager(context).maxParallelDownloads = clamped
        }
    }

    fun buildHttpDataSourceFactory(): DataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(DownloadHelper.MOBILE_CHROME_UA)
            .setAllowCrossProtocolRedirects(true)

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        databaseProvider?.let { return it }
        val provider = StandaloneDatabaseProvider(context.applicationContext)
        databaseProvider = provider
        return provider
    }
}
