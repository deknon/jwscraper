package com.saha.videodownloader.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Starts HLS→MP4 mux inside [FfmpegMuxService] (foreground) so HyperOS / Android 16
 * devices (e.g. Xiaomi 14) are less likely to kill the job in the background.
 */
class FfmpegHlsDownloadStrategy(
    private val onStarted: (() -> Unit)? = null,
    private val onFinished: (() -> Unit)? = null
) : HlsDownloadStrategy {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun download(url: String, context: Context) {
        onStarted?.let { mainHandler.post(it) }
        FfmpegMuxService.start(context, url)
        mainHandler.post {
            Toast.makeText(
                context.applicationContext,
                "เริ่ม mux ใน foreground service — ดูความคืบหน้าในหน้าดาวน์โหลด / แถบแจ้งเตือน",
                Toast.LENGTH_LONG
            ).show()
            // Service owns the long-running work; clear the button spinner promptly.
            onFinished?.invoke()
        }
    }
}
