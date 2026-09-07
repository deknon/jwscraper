package com.saha.videodownloader.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.saha.videodownloader.model.VideoType

/**
 * Hands a detected media URL to whatever cast/player app the user has —
 * Web Video Cast, VLC, MX Player, AllCast, a DLNA sender, … — instead of
 * bundling a Cast SDK.
 *
 * The auth-bearing headers the WebView used (Referer / Cookie / User-Agent)
 * are attached in the several shapes those players read, because most CDN
 * playlists 403 without them. Unknown extras are simply ignored by the
 * receiving app.
 *
 * Requires the `<queries>` entry for ACTION_VIEW with a video mime type in
 * the manifest, otherwise `resolveActivity` sees nothing on API 30+.
 */
object CastIntentHelper {

    /** @return false when no app on the device can play the URL. */
    fun castVideo(
        context: Context,
        url: String,
        type: VideoType,
        pageUrl: String?,
        userAgent: String,
        title: String?,
        chooserTitle: String = "เล่น / Cast วิดีโอด้วย"
    ): Boolean {
        val headers = try {
            CapturedMediaHeaders.mergeFor(url, pageUrl, userAgent)
        } catch (_: Throwable) {
            mapOf("User-Agent" to userAgent)
        }

        val viewIntent = buildViewIntent(url, type, headers, title)
        return try {
            context.startActivity(Intent.createChooser(viewIntent, chooserTitle))
            true
        } catch (_: ActivityNotFoundException) {
            shareUrl(context, url, title, chooserTitle)
        }
    }

    /** Last resort — let the user push the raw URL into any app that takes text. */
    fun shareUrl(
        context: Context,
        url: String,
        title: String? = null,
        chooserTitle: String = "ส่ง URL วิดีโอไปที่"
    ): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return try {
            context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun copyUrl(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("video url", url))
    }

    fun mimeTypeFor(type: VideoType): String = when (type) {
        VideoType.HLS -> "application/x-mpegURL"
        VideoType.MP4 -> "video/mp4"
        VideoType.UNKNOWN -> "video/*"
    }

    /**
     * Flattens headers into the alternating `[name, value, name, value, …]`
     * array that MX Player and several forks expect in the `headers` extra.
     * Pure — unit-testable without Android.
     */
    fun flattenHeaders(headers: Map<String, String>): Array<String> =
        headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .flatMap { listOf(it.key, it.value) }
            .toTypedArray()

    private fun buildViewIntent(
        url: String,
        type: VideoType,
        headers: Map<String, String>,
        title: String?
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndTypeAndNormalize(Uri.parse(url), mimeTypeFor(type))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (!title.isNullOrBlank()) {
            putExtra(Intent.EXTRA_TITLE, title)
            // MX Player / VLC forks.
            putExtra("title", title)
        }

        // MX Player style.
        putExtra("headers", flattenHeaders(headers))
        putExtra("secure_uri", true)

        // MediaRouter / cast-sender style.
        putExtra(
            "android.media.intent.extra.HTTP_HEADERS",
            Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } }
        )

        headers["User-Agent"]?.let {
            putExtra("User-Agent", it)
            putExtra("user-agent", it)
            putExtra("http-user-agent", it)
        }
        headers["Referer"]?.let {
            putExtra("Referer", it)
            putExtra("referer", it)
            putExtra("http-referrer", it)
        }
        headers["Cookie"]?.let {
            putExtra("Cookie", it)
            putExtra("cookies", it)
        }
    }
}
