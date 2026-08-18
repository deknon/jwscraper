package com.saha.videodownloader.download

import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Queues ffmpeg HLS→MP4 jobs and starts at most
 * [DownloadSettingsStore.getMaxConcurrent] at a time.
 */
object MuxJobQueue {

    private val activeIds = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String? = null,
        refererUrl: String? = null,
        pageTitle: String? = null
    ): String {
        val appContext = context.applicationContext
        FfmpegJobTracker.init(appContext)
        DownloadSettingsStore.init(appContext)

        val filename = DownloadFilenameResolver.fromHints(
            mediaUrl = url,
            pageTitle = pageTitle,
            defaultExt = ".mp4"
        )
        val jobId = "ffmpeg-job:${UUID.randomUUID()}"
        val ua = userAgent ?: DownloadHelper.MOBILE_CHROME_UA

        FfmpegJobTracker.enqueue(
            id = jobId,
            title = filename,
            sourceUrl = url,
            refererUrl = refererUrl,
            userAgent = ua,
            pageTitle = pageTitle
        )
        Log.i(TAG, "enqueued $jobId ($filename)")
        pump(appContext)
        return jobId
    }

    fun onJobFinished(context: Context, jobId: String) {
        activeIds.remove(jobId)
        pump(context.applicationContext)
    }

    fun onJobCancelled(context: Context, jobId: String) {
        activeIds.remove(jobId)
        pump(context.applicationContext)
    }

    fun isActive(jobId: String): Boolean = activeIds.contains(jobId)

    fun activeCount(): Int = activeIds.size

    fun pump(context: Context) {
        val appContext = context.applicationContext
        FfmpegJobTracker.init(appContext)
        DownloadSettingsStore.init(appContext)
        synchronized(lock) {
            val max = DownloadSettingsStore.getMaxConcurrent(appContext)
            while (activeIds.size < max) {
                val next = FfmpegJobTracker.nextQueued() ?: break
                if (!activeIds.add(next.id)) continue
                FfmpegJobTracker.markRunning(next.id)
                Log.i(TAG, "starting ${next.id} (active=${activeIds.size}/$max)")
                FfmpegMuxService.dispatchStart(
                    context = appContext,
                    jobId = next.id,
                    url = next.sourceUrl,
                    filename = next.title,
                    userAgent = next.userAgent ?: DownloadHelper.MOBILE_CHROME_UA,
                    refererUrl = next.refererUrl,
                    pageTitle = next.pageTitle
                )
            }
        }
    }

    private const val TAG = "MuxJobQueue"
}
