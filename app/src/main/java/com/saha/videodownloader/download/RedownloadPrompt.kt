package com.saha.videodownloader.download

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/**
 * Asks the user what to do when the same media URL was downloaded before.
 */
object RedownloadPrompt {

    enum class Choice {
        CANCEL,
        RENAME,
        OVERWRITE
    }

    fun findExisting(context: Context, mediaUrl: String): FfmpegHistoryStore.Entry? =
        FfmpegHistoryStore(context.applicationContext).findByMediaUrl(mediaUrl)

    /**
     * If [mediaUrl] was downloaded before, shows a dialog; otherwise calls [onProceed]
     * with [Choice.RENAME] and a null existing entry (treated as a fresh download).
     */
    fun confirmIfNeeded(
        context: Context,
        mediaUrl: String,
        existingTitle: String? = null,
        onProceed: (Choice, FfmpegHistoryStore.Entry?) -> Unit
    ) {
        val existing = findExisting(context, mediaUrl)
        if (existing == null) {
            onProceed(Choice.RENAME, null)
            return
        }
        val title = existingTitle?.takeIf { it.isNotBlank() } ?: existing.title
        AlertDialog.Builder(context)
            .setTitle("เคยดาวน์โหลดแล้ว")
            .setMessage(
                "พบไฟล์นี้ในประวัติ:\n$title\n\n" +
                    "ต้องการดาวน์โหลดอีกครั้งหรือไม่?"
            )
            .setPositiveButton("เปลี่ยนชื่อไฟล์") { dialog, _ ->
                dialog.dismiss()
                onProceed(Choice.RENAME, existing)
            }
            .setNeutralButton("บันทึกทับ") { dialog, _ ->
                dialog.dismiss()
                onProceed(Choice.OVERWRITE, existing)
            }
            .setNegativeButton("ยกเลิก") { dialog, _ ->
                dialog.dismiss()
                onProceed(Choice.CANCEL, existing)
            }
            .show()
    }

    fun uniqueFilename(original: String): String {
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ".mp4"
        val cleanedStem = stem.replace(Regex(""" \(\d+\)$"""), "")
        return "${cleanedStem}_${System.currentTimeMillis()}$ext"
    }

    fun deletePublishedFile(context: Context, contentUri: String?): Boolean {
        if (contentUri.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(contentUri)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun deleteByDisplayName(context: Context, filename: String): Boolean {
        if (filename.isBlank()) return false
        var deleted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(filename, "${DownloadPaths.MEDIA_STORE_RELATIVE_PATH}/"),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(collection, id)
                        if (resolver.delete(uri, null, null) > 0) deleted = true
                    }
                }
                // Some devices omit trailing slash on RELATIVE_PATH.
                if (!deleted) {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME}=?",
                        arrayOf(filename),
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val uri = ContentUris.withAppendedId(collection, id)
                            if (resolver.delete(uri, null, null) > 0) deleted = true
                        }
                    }
                }
            } catch (_: Exception) {
                // fall through to legacy path
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, DownloadPaths.destinationPath(filename))
            if (file.exists() && file.delete()) deleted = true
        }
        return deleted
    }

    fun applyOverwrite(
        context: Context,
        existing: FfmpegHistoryStore.Entry
    ) {
        val removedUri = deletePublishedFile(context, existing.contentUri)
        val removedName = deleteByDisplayName(context, existing.title)
        FfmpegHistoryStore(context.applicationContext).removeByMediaUrl(existing.sourceUrl)
        Toast.makeText(
            context,
            if (removedUri || removedName) {
                "ลบไฟล์เดิมแล้ว — จะบันทึกทับ"
            } else {
                "จะบันทึกทับชื่อเดิม"
            },
            Toast.LENGTH_SHORT
        ).show()
    }
}
