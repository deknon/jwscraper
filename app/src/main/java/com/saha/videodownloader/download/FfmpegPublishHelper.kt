package com.saha.videodownloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Publishes a local muxed file into public Downloads/`[DownloadPaths.SUBFOLDER]`.
 */
object FfmpegPublishHelper {

    private const val COPY_BUFFER_BYTES = 256 * 1024

    fun publishToDownloads(context: Context, source: File, filename: String): Uri? {
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
            put(MediaStore.Downloads.RELATIVE_PATH, DownloadPaths.MEDIA_STORE_RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
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
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, DownloadPaths.SUBFOLDER)
        if (!folder.exists() && !folder.mkdirs()) return null
        val dest = File(folder, filename)
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }
}
