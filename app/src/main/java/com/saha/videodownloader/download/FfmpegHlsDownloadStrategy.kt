package com.saha.videodownloader.download

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Starts HLS→MP4 mux inside [FfmpegMuxService] (foreground) so HyperOS / Android 16
 * devices (e.g. Xiaomi 14) are less likely to kill the job in the background.
 *
 * Does not navigate away from the WebView — progress is shown via the FGS notification.
 */
class FfmpegHlsDownloadStrategy(
    private val onStarted: (() -> Unit)? = null,
    private val onFinished: (() -> Unit)? = null,
    private val userAgent: String? = null,
    private val refererUrl: String? = null
) : HlsDownloadStrategy {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun download(url: String, context: Context) {
        onStarted?.let { mainHandler.post(it) }
        // Registers job in downloads list before FGS starts.
        FfmpegMuxService.start(context, url, userAgent, refererUrl)
        mainHandler.post {
            // Service owns the long-running work; clear the button spinner promptly.
            onFinished?.invoke()
        }
    }
}
