package com.saha.videodownloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.net.URI

/**
 * Muxes an HLS playlist into a single `.mp4` via ffmpeg-kit
 * (`-i playlist.m3u8 -c copy`), then publishes it to Downloads.
 */
class FfmpegHlsDownloadStrategy(
    private val onStarted: (() -> Unit)? = null,
    private val onFinished: (() -> Unit)? = null
) : HlsDownloadStrategy {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun download(url: String, context: Context) {
        val appContext = context.applicationContext
        val filename = buildFilename(url)
        val workDir = File(appContext.cacheDir, "hls_mux").apply { mkdirs() }
        val outputFile = File(workDir, filename)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        onStarted?.let { mainHandler.post(it) }
        toast(appContext, "กำลัง mux HLS → MP4…")

        val args = arrayOf(
            "-user_agent", DownloadHelper.MOBILE_CHROME_UA,
            "-i", url,
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",
            "-movflags", "+faststart",
            "-y",
            outputFile.absolutePath
        )

        FFmpegKit.executeWithArgumentsAsync(args) { session ->
            try {
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val published = publishToDownloads(appContext, outputFile, filename)
                    if (published != null) {
                        toast(appContext, "บันทึกแล้ว: $filename")
                    } else {
                        toast(
                            appContext,
                            "mux สำเร็จ แต่คัดลอกไป Downloads ไม่ได้ — ไฟล์อยู่ที่ ${outputFile.absolutePath}"
                        )
                    }
                } else {
                    val fail = session.failStackTrace?.take(200)
                    val rc = session.returnCode
                    toast(
                        appContext,
                        "ffmpeg ล้มเหลว (rc=$rc)${if (fail.isNullOrBlank()) "" else ": $fail"}"
                    )
                }
            } finally {
                // Keep failed outputs for debugging; delete successful temp after publish.
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists()) {
                    outputFile.delete()
                }
                onFinished?.let { mainHandler.post(it) }
            }
        }
    }

    private fun publishToDownloads(context: Context, source: File, filename: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(context, source, filename)
        } else {
            publishLegacyPublicDownloads(source, filename)
        }
    }

    private fun publishViaMediaStore(context: Context, source: File, filename: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacyPublicDownloads(source: File, filename: String): Uri? {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) return null
        val dest = File(downloads, filename)
        return try {
            source.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFilename(url: String): String {
        val host = try {
            URI(url).host?.replace('.', '_') ?: "hls"
        } catch (_: Exception) {
            "hls"
        }
        val sanitized = host.replace(Regex("""[^\w\-.]"""), "_")
        return "${sanitized}_${System.currentTimeMillis()}.mp4"
    }

    private fun toast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
