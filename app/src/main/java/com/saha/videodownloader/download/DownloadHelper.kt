package com.saha.videodownloader.download

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import com.saha.videodownloader.util.BatteryOptimizationPrompt
import java.net.URI

object DownloadHelper {

    private val media3HlsStrategy: HlsDownloadStrategy = Media3HlsDownloadStrategy()

    /**
     * Enqueues an MP4 download via [DownloadManager].
     *
     * SDK <= 28: writes to public Downloads with WRITE_EXTERNAL_STORAGE.
     * SDK >= 29: lets DownloadManager handle MediaStore / scoped storage.
     */
    fun downloadMp4(context: Context, url: String, suggestedName: String? = null) {
        val filename = suggestedName?.takeIf { it.isNotBlank() } ?: buildFilename(url)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("กำลังดาวน์โหลดวิดีโอ…")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("User-Agent", MOBILE_CHROME_UA)

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // API 28 and below: explicit public Downloads path + WRITE_EXTERNAL_STORAGE.
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            // API 29+: omit setDestination* — DownloadManager uses MediaStore / scoped storage.
        }

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
        Toast.makeText(context, "เริ่มดาวน์โหลด: $filename", Toast.LENGTH_SHORT).show()
    }

    /**
     * Lets the user pick HLS download mode:
     * - ffmpeg mux → single `.mp4` in Downloads
     * - Media3 offline cache (ExoPlayer)
     */
    fun handleHlsUrl(
        context: Context,
        url: String,
        onDownloadStarted: (() -> Unit)? = null,
        onDownloadFinished: (() -> Unit)? = null
    ) {
        val options = arrayOf(
            "Mux เป็น MP4 (ffmpeg) — ไฟล์ใน Downloads",
            "Media3 offline cache — เล่นในแอป"
        )
        AlertDialog.Builder(context)
            .setTitle("HLS (.m3u8)")
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                try {
                    when (which) {
                        0 -> {
                            BatteryOptimizationPrompt.maybePrompt(context)
                            FfmpegHlsDownloadStrategy(
                                onStarted = onDownloadStarted,
                                onFinished = onDownloadFinished
                            ).download(url, context)
                        }
                        1 -> {
                            BatteryOptimizationPrompt.maybePrompt(context)
                            onDownloadStarted?.invoke()
                            try {
                                media3HlsStrategy.download(url, context)
                            } finally {
                                onDownloadFinished?.invoke()
                            }
                        }
                    }
                } catch (e: Exception) {
                    onDownloadFinished?.invoke()
                    Toast.makeText(
                        context,
                        e.message ?: "HLS ดาวน์โหลดไม่สำเร็จ",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    fun handleUnknownOrOther(context: Context, url: String) {
        // Best-effort: try DownloadManager as MP4-like progressive download.
        downloadMp4(context, url)
    }

    private fun buildFilename(url: String): String {
        val host = try {
            URI(url).host?.replace('.', '_') ?: "video"
        } catch (_: Exception) {
            "video"
        }
        val sanitized = sanitize("${host}_${System.currentTimeMillis()}")
        return "$sanitized.mp4"
    }

    private fun sanitize(input: String): String =
        input.replace(Regex("""[^\w\-.]"""), "_")

    /** Mobile Chrome on Android — preferred default over desktop Chrome UA. */
    const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
