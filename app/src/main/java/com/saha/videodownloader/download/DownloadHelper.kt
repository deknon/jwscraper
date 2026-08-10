package com.saha.videodownloader.download

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import java.net.URI

object DownloadHelper {

    private val hlsStrategy: HlsDownloadStrategy = NotImplementedHlsStrategy()

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
     * Explains that HLS needs segment muxing, then delegates to [HlsDownloadStrategy] stub.
     */
    fun handleHlsUrl(context: Context, url: String) {
        AlertDialog.Builder(context)
            .setTitle("HLS (.m3u8)")
            .setMessage(
                "URL นี้เป็น HLS playlist — ต้องรวมหลาย segment ก่อนได้ไฟล์วิดีโอเดียว\n\n" +
                    "เวอร์ชันนี้ดักจับ URL ได้เท่านั้น ยังไม่ดาวน์โหลด/รวม segment จริง"
            )
            .setPositiveButton("ตกลง") { dialog, _ ->
                dialog.dismiss()
                try {
                    hlsStrategy.download(url, context)
                } catch (e: UnsupportedOperationException) {
                    Toast.makeText(
                        context,
                        e.message ?: "HLS ยังไม่รองรับ",
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
