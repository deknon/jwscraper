package com.saha.videodownloader.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig

/**
 * Eagerly initializes ffmpeg-kit and surfaces the *root* failure cause.
 *
 * `dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.7` references
 * `com.arthenica.smartexception` in [FFmpegKitConfig] static init but does not
 * declare that dependency in its POM — without
 * `com.arthenica:smart-exception-java` the class fails with
 * ExceptionInInitializerError whose message is just
 * `com.arthenica.ffmpegkit.FFmpegKitConfig`.
 */
object FfmpegKitLoader {

    @Volatile
    private var ready: Boolean? = null

    @Volatile
    private var lastError: String? = null

    fun ensureReady(): Result<Unit> {
        ready?.let { ok ->
            return if (ok) Result.success(Unit)
            else Result.failure(IllegalStateException(lastError ?: "ffmpeg-kit unavailable"))
        }
        synchronized(this) {
            ready?.let { ok ->
                return if (ok) Result.success(Unit)
                else Result.failure(IllegalStateException(lastError ?: "ffmpeg-kit unavailable"))
            }
            return try {
                // Touching the class runs native load in <clinit>.
                val version = FFmpegKitConfig.getVersion()
                Log.i(TAG, "ffmpeg-kit ready: $version")
                ready = true
                lastError = null
                Result.success(Unit)
            } catch (t: Throwable) {
                val detail = formatThrowable(t)
                Log.e(TAG, "ffmpeg-kit init failed: $detail", t)
                ready = false
                lastError = detail
                Result.failure(IllegalStateException(detail, t))
            }
        }
    }

    fun formatThrowable(t: Throwable): String {
        val parts = LinkedHashSet<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val msg = cur.message?.takeIf { it.isNotBlank() && it != cur.javaClass.name }
            parts += if (msg != null) {
                "${cur.javaClass.simpleName}: $msg"
            } else {
                cur.javaClass.simpleName
            }
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" → ").take(280)
    }

    private const val TAG = "FfmpegKitLoader"
}
