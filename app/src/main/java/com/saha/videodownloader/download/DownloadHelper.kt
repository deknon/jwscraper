package com.saha.videodownloader.download

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.saha.videodownloader.util.BatteryOptimizationPrompt

object DownloadHelper {

    private val media3HlsStrategy: HlsDownloadStrategy = Media3HlsDownloadStrategy()

    /**
     * Enqueues an MP4 download via [DownloadManager], after optional duplicate prompt.
     */
    fun downloadMp4(
        context: Context,
        url: String,
        suggestedName: String? = null,
        pageTitle: String? = null,
        pageUrl: String? = null,
        userAgent: String? = null,
        skipDuplicateCheck: Boolean = false
    ) {
        val ua = userAgent ?: MOBILE_CHROME_UA
        val resolved = suggestedName?.takeIf { it.isNotBlank() }
            ?: DownloadFilenameResolver.resolve(
                mediaUrl = url,
                pageTitle = pageTitle,
                pageUrl = pageUrl,
                userAgent = ua,
                defaultExt = ".mp4",
                probeNetwork = true
            )

        fun enqueue(filename: String) {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("กำลังดาวน์โหลดวิดีโอ…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", ua)
                pageUrl?.takeIf { it.startsWith("http") }?.let { addRequestHeader("Referer", it) }
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    DownloadPaths.destinationPath(filename)
                )
            }
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            OfflineDownloadRepository(context).recordProgressiveMp4(
                sourceUrl = url,
                title = filename,
                pageUrl = pageUrl
            )
            Toast.makeText(
                context,
                "เริ่มดาวน์โหลด: $filename\n→ Download/${DownloadPaths.SUBFOLDER}",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (skipDuplicateCheck) {
            enqueue(resolved)
            return
        }

        RedownloadPrompt.confirmIfNeeded(
            context = context,
            mediaUrl = url,
            existingTitle = resolved
        ) { choice, existing ->
            when (choice) {
                RedownloadPrompt.Choice.CANCEL -> Unit
                RedownloadPrompt.Choice.OVERWRITE -> {
                    existing?.let { RedownloadPrompt.applyOverwrite(context, it) }
                    enqueue(existing?.title?.takeIf { it.isNotBlank() } ?: resolved)
                }
                RedownloadPrompt.Choice.RENAME -> {
                    val name = if (existing != null) {
                        RedownloadPrompt.uniqueFilename(
                            existing.title.takeIf { it.isNotBlank() } ?: resolved
                        )
                    } else {
                        resolved
                    }
                    enqueue(name)
                }
            }
        }
    }

    /**
     * Lets the user pick HLS download mode after optional duplicate prompt:
     * - ffmpeg mux → single `.mp4` in Downloads
     * - Media3 offline cache (ExoPlayer)
     */
    fun handleHlsUrl(
        context: Context,
        url: String,
        onDownloadStarted: (() -> Unit)? = null,
        onDownloadFinished: (() -> Unit)? = null,
        userAgent: String? = null,
        refererUrl: String? = null,
        pageTitle: String? = null,
        skipDuplicateCheck: Boolean = false
    ) {
        fun showModePicker(forcedFilename: String?) {
            val options = arrayOf(
                "Mux เป็น MP4 (ffmpeg) — ไฟล์ใน Download/${DownloadPaths.SUBFOLDER}",
                "Media3 offline cache — เล่นในแอป"
            )
            AlertDialog.Builder(context)
                .setTitle("HLS (.m3u8)")
                .setItems(options) { dialog, which ->
                    dialog.dismiss()
                    try {
                        when (which) {
                            0 -> {
                                FfmpegHlsDownloadStrategy(
                                    onStarted = onDownloadStarted,
                                    onFinished = onDownloadFinished,
                                    userAgent = userAgent,
                                    refererUrl = refererUrl,
                                    pageTitle = pageTitle,
                                    forcedFilename = forcedFilename
                                ).download(url, context)
                                BatteryOptimizationPrompt.maybePromptLater(context)
                            }
                            1 -> {
                                onDownloadStarted?.invoke()
                                try {
                                    media3HlsStrategy.download(url, context)
                                } finally {
                                    onDownloadFinished?.invoke()
                                }
                                BatteryOptimizationPrompt.maybePromptLater(context)
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
                .setNegativeButton("ยกเลิก") { _, _ ->
                    onDownloadFinished?.invoke()
                }
                .show()
        }

        if (skipDuplicateCheck) {
            showModePicker(null)
            return
        }

        RedownloadPrompt.confirmIfNeeded(
            context = context,
            mediaUrl = url,
            existingTitle = pageTitle
        ) { choice, existing ->
            when (choice) {
                RedownloadPrompt.Choice.CANCEL -> onDownloadFinished?.invoke()
                RedownloadPrompt.Choice.OVERWRITE -> {
                    existing?.let { RedownloadPrompt.applyOverwrite(context, it) }
                    showModePicker(existing?.title?.takeIf { it.isNotBlank() })
                }
                RedownloadPrompt.Choice.RENAME -> {
                    val forced = if (existing != null) {
                        RedownloadPrompt.uniqueFilename(
                            existing.title.takeIf { it.isNotBlank() }
                                ?: DownloadFilenameResolver.fromHints(url, pageTitle)
                        )
                    } else {
                        null
                    }
                    showModePicker(forced)
                }
            }
        }
    }

    fun handleUnknownOrOther(
        context: Context,
        url: String,
        pageTitle: String? = null,
        pageUrl: String? = null,
        userAgent: String? = null
    ) {
        downloadMp4(
            context = context,
            url = url,
            pageTitle = pageTitle,
            pageUrl = pageUrl,
            userAgent = userAgent
        )
    }

    /** Mobile Chrome on Android — preferred default over desktop Chrome UA. */
    const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Desktop Chrome UA — optional for sites that serve a different JW Player embed
     * to mobile clients. Keep mobile as the default.
     */
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
