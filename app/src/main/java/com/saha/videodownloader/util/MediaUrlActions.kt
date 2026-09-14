package com.saha.videodownloader.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Copy / share helpers for detected media URLs (Cast MVP = link only).
 */
object MediaUrlActions {

    fun copyUrl(context: Context, url: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("video_url", url))
        Toast.makeText(context, "คัดลอก URL แล้ว", Toast.LENGTH_SHORT).show()
    }

    fun shareUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "แชร์ลิงก์วิดีโอ"))
        }
    }
}
