package com.saha.videodownloader.download

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.saha.videodownloader.R

/**
 * Foreground [DownloadService] that keeps Media3 HLS/DASH offline downloads running
 * even when the app is backgrounded.
 */
@OptIn(UnstableApi::class)
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description
) {

    override fun getDownloadManager(): DownloadManager =
        Media3DownloadUtil.getDownloadManager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        ensureChannel(this)
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(
                this,
                R.drawable.ic_download,
                /* contentIntent = */ null,
                /* message = */ getString(R.string.download_notification_message),
                downloads,
                notMetRequirements
            )
    }

    companion object {
        const val CHANNEL_ID = "hls_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1

        fun ensureChannel(context: android.content.Context) {
            NotificationUtil.createNotificationChannel(
                context,
                CHANNEL_ID,
                R.string.download_notification_channel_name,
                R.string.download_notification_channel_description,
                NotificationUtil.IMPORTANCE_LOW
            )
        }
    }
}
