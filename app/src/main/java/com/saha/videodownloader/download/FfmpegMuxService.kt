package com.saha.videodownloader.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.Statistics
import com.saha.videodownloader.MainActivity
import com.saha.videodownloader.R
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Foreground service that runs ffmpeg HLS→MP4 mux.
 *
 * Important on Xiaomi / HyperOS (e.g. Xiaomi 14, Android 16): background
 * processes are aggressively killed — keeping a visible FGS notification
 * greatly improves survival while muxing.
 */
class FfmpegMuxService : Service() {

    private val activeSessionId = AtomicReference<Long?>(null)

    override fun onCreate() {
        super.onCreate()
        FfmpegJobTracker.init(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                val sessionId = activeSessionId.get()
                    ?: jobId?.let { FfmpegJobTracker.get(it)?.sessionId }
                if (sessionId != null) {
                    runCatching { FFmpegKit.cancel(sessionId) }
                }
                if (jobId != null) {
                    FfmpegJobTracker.remove(jobId)
                }
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: "ffmpeg-job:${UUID.randomUUID()}"
                val filename = intent.getStringExtra(EXTRA_FILENAME)
                    ?: "hls_${System.currentTimeMillis()}.mp4"
                val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
                    ?: DownloadHelper.MOBILE_CHROME_UA
                if (FfmpegJobTracker.get(jobId) == null) {
                    FfmpegJobTracker.start(jobId, filename, url)
                }
                startMux(jobId, url, filename, userAgent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMux(jobId: String, url: String, filename: String, userAgent: String) {
        try {
            VideoDownloadService.ensureChannel(this)
            val notification = buildNotification(jobId, filename, 0, "เริ่ม mux…")
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            FfmpegJobTracker.fail(
                jobId,
                "เริ่ม foreground service ไม่ได้: ${t.message ?: t.javaClass.simpleName}"
            )
            stopSelfSafely()
            return
        }

        FfmpegJobTracker.updateProgress(jobId, 0f, "กำลังเตรียม ffmpeg…")

        val init = FfmpegKitLoader.ensureReady()
        if (init.isFailure) {
            val detail = init.exceptionOrNull()?.message ?: "ffmpeg-kit init failed"
            FfmpegJobTracker.fail(jobId, "เริ่ม ffmpeg ไม่ได้: $detail")
            updateNotification(jobId, filename, 0, "เริ่ม ffmpeg ไม่ได้", ongoing = false)
            stopSelfSafely()
            return
        }

        val workDir = File(cacheDir, "hls_mux").apply { mkdirs() }
        val outputFile = File(workDir, filename)
        if (outputFile.exists()) outputFile.delete()

        val httpHeaders = buildHttpHeaders(url, userAgent)
        val durationMs = AtomicLong(0L)
        runCatching {
            // Best-effort duration for progress; mux does not wait on probe.
            FFprobeKit.getMediaInformationAsync(url) { probeSession ->
                val durationSec = probeSession.mediaInformation?.duration?.toDoubleOrNull() ?: 0.0
                durationMs.set(max(0L, (durationSec * 1000.0).toLong()))
            }
        }

        val strategies = buildMuxStrategies(url, userAgent, httpHeaders, outputFile.absolutePath)
        runMuxAttempt(
            jobId = jobId,
            url = url,
            filename = filename,
            outputFile = outputFile,
            durationMs = durationMs,
            strategies = strategies,
            attemptIndex = 0,
            lastError = null
        )
    }

    private fun runMuxAttempt(
        jobId: String,
        url: String,
        filename: String,
        outputFile: File,
        durationMs: AtomicLong,
        strategies: List<MuxStrategy>,
        attemptIndex: Int,
        lastError: String?
    ) {
        if (attemptIndex >= strategies.size) {
            val message = lastError ?: "ffmpeg ล้มเหลว"
            FfmpegJobTracker.fail(jobId, message)
            updateNotification(jobId, filename, 0, message, ongoing = false)
            runCatching { if (outputFile.exists()) outputFile.delete() }
            stopSelfSafely()
            return
        }

        val strategy = strategies[attemptIndex]
        if (outputFile.exists()) outputFile.delete()
        FfmpegJobTracker.updateProgress(
            jobId,
            0f,
            "mux ลอง ${attemptIndex + 1}/${strategies.size}: ${strategy.label}"
        )
        Log.i(TAG, "mux attempt ${attemptIndex + 1}: ${strategy.label} args=${strategy.args.joinToString(" ")}")

        val session = try {
            FFmpegKit.executeWithArgumentsAsync(
                strategy.args,
                { completed ->
                    try {
                        when {
                            ReturnCode.isSuccess(completed.returnCode) -> {
                                updateNotification(jobId, filename, 99, "กำลังบันทึกไป Downloads…")
                                FfmpegJobTracker.updateProgress(jobId, 0.99f, "กำลังบันทึกไป Downloads…")
                                val published = FfmpegPublishHelper.publishToDownloads(
                                    this,
                                    outputFile,
                                    filename
                                )
                                if (published != null) {
                                    OfflineDownloadRepository(this).recordFfmpegSuccess(
                                        sourceUrl = url,
                                        title = filename,
                                        contentUri = published.toString()
                                    )
                                    FfmpegJobTracker.complete(jobId)
                                    updateNotification(jobId, filename, 100, "บันทึกแล้ว", ongoing = false)
                                } else {
                                    FfmpegJobTracker.fail(jobId, "mux สำเร็จ แต่คัดลอกไป Downloads ไม่ได้")
                                    updateNotification(
                                        jobId,
                                        filename,
                                        0,
                                        "บันทึก Downloads ไม่สำเร็จ",
                                        ongoing = false
                                    )
                                }
                                runCatching { if (outputFile.exists()) outputFile.delete() }
                                stopSelfSafely()
                            }
                            ReturnCode.isCancel(completed.returnCode) -> {
                                FfmpegJobTracker.remove(jobId)
                                runCatching { if (outputFile.exists()) outputFile.delete() }
                                stopSelfSafely()
                            }
                            else -> {
                                val err = extractFfmpegError(completed)
                                Log.w(TAG, "mux attempt ${attemptIndex + 1} failed: $err")
                                runMuxAttempt(
                                    jobId = jobId,
                                    url = url,
                                    filename = filename,
                                    outputFile = outputFile,
                                    durationMs = durationMs,
                                    strategies = strategies,
                                    attemptIndex = attemptIndex + 1,
                                    lastError = "ffmpeg ล้มเหลว (rc=${completed.returnCode}): $err"
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "mux completion handler failed", t)
                        FfmpegJobTracker.fail(
                            jobId,
                            "mux error: ${t.message ?: t.javaClass.simpleName}"
                        )
                        runCatching { if (outputFile.exists()) outputFile.delete() }
                        stopSelfSafely()
                    }
                },
                null,
                { stats: Statistics ->
                    try {
                        val duration = durationMs.get()
                        val timeMs = stats.time.toLong()
                        val progress = if (duration > 0L) {
                            (timeMs.toFloat() / duration.toFloat()).coerceIn(0f, 0.99f)
                        } else {
                            (0.9f * (1f - 1f / (1f + timeMs / 15_000f))).coerceIn(0f, 0.9f)
                        }
                        val pct = (progress * 100).toInt()
                        val speed = stats.speed
                        val msg = if (speed > 0) {
                            "mux $pct% · ${"%.1f".format(speed)}x · ${strategy.label}"
                        } else {
                            "mux $pct% · ${strategy.label}"
                        }
                        FfmpegJobTracker.updateProgress(jobId, progress, msg)
                        updateNotification(jobId, filename, pct, msg)
                    } catch (t: Throwable) {
                        Log.w(TAG, "stats update failed", t)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "ffmpeg execute failed to start", t)
            runMuxAttempt(
                jobId = jobId,
                url = url,
                filename = filename,
                outputFile = outputFile,
                durationMs = durationMs,
                strategies = strategies,
                attemptIndex = attemptIndex + 1,
                lastError = "เริ่ม ffmpeg ไม่ได้: ${FfmpegKitLoader.formatThrowable(t)}"
            )
            return
        }
        activeSessionId.set(session.sessionId)
        FfmpegJobTracker.bindSession(jobId, session.sessionId)
    }

    private fun buildNotification(
        jobId: String,
        title: String,
        progress: Int,
        text: String,
        ongoing: Boolean = true
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FfmpegMuxService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, VideoDownloadService.CHANNEL_ID)
            .setContentTitle("HLS → MP4: $title")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ongoing) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            builder.addAction(0, "ยกเลิก", cancelIntent)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun updateNotification(
        jobId: String,
        title: String,
        progress: Int,
        text: String,
        ongoing: Boolean = true
    ) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(jobId, title, progress, text, ongoing)
            )
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun stopSelfSafely() {
        activeSessionId.set(null)
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private data class MuxStrategy(val label: String, val args: Array<String>)

    companion object {
        private const val TAG = "FfmpegMuxService"
        const val ACTION_START = "com.saha.videodownloader.action.FFMPEG_MUX_START"
        const val ACTION_CANCEL = "com.saha.videodownloader.action.FFMPEG_MUX_CANCEL"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, url: String, userAgent: String? = null): String {
            val appContext = context.applicationContext
            FfmpegJobTracker.init(appContext)
            val filename = buildFilename(url)
            val jobId = "ffmpeg-job:${UUID.randomUUID()}"
            FfmpegJobTracker.start(jobId, filename, url)

            val intent = Intent(appContext, FfmpegMuxService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_USER_AGENT, userAgent ?: DownloadHelper.MOBILE_CHROME_UA)
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "startForegroundService failed", t)
                FfmpegJobTracker.fail(
                    jobId,
                    "เริ่ม service ไม่ได้: ${t.message ?: t.javaClass.simpleName}"
                )
            }
            return jobId
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

        private fun buildHttpHeaders(url: String, userAgent: String): String {
            val referer = try {
                val uri = URI(url)
                val host = uri.host
                if (host.isNullOrBlank()) {
                    null
                } else {
                    val scheme = uri.scheme ?: "https"
                    "$scheme://$host/"
                }
            } catch (_: Exception) {
                null
            }
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

            val lines = buildList {
                add("User-Agent: $userAgent")
                if (!referer.isNullOrBlank()) add("Referer: $referer")
                add("Accept: */*")
                if (!cookie.isNullOrBlank()) add("Cookie: $cookie")
            }
            // ffmpeg -headers requires CRLF between entries and a trailing CRLF.
            return lines.joinToString("\r\n", postfix = "\r\n")
        }

        private fun buildMuxStrategies(
            url: String,
            userAgent: String,
            headers: String,
            outputPath: String
        ): List<MuxStrategy> {
            val commonPrefix = arrayOf(
                "-y",
                "-user_agent", userAgent,
                "-headers", headers,
                "-allowed_extensions", "ALL",
                "-protocol_whitelist", "file,http,https,tcp,tls,crypto,data,pipe",
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5",
                "-i", url
            )
            return listOf(
                // Most HLS/TS audio needs ADTS→ASC when remuxing to MP4.
                MuxStrategy(
                    label = "copy+aac_bsf",
                    args = commonPrefix + arrayOf(
                        "-c", "copy",
                        "-bsf:a", "aac_adtstoasc",
                        "-movflags", "+faststart",
                        outputPath
                    )
                ),
                // Fails when there is no ADTS AAC (already fragmented MP4 / no audio).
                MuxStrategy(
                    label = "copy",
                    args = commonPrefix + arrayOf(
                        "-c", "copy",
                        "-movflags", "+faststart",
                        outputPath
                    )
                ),
                // Last resort: re-encode audio so MP4 mux can succeed.
                MuxStrategy(
                    label = "copyV+aac",
                    args = commonPrefix + arrayOf(
                        "-c:v", "copy",
                        "-c:a", "aac",
                        "-b:a", "128k",
                        "-movflags", "+faststart",
                        outputPath
                    )
                )
            )
        }

        fun extractFfmpegError(session: Session): String {
            val logs = runCatching { session.allLogsAsString }.getOrNull().orEmpty()
            val interesting = logs
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { line ->
                    val lower = line.lowercase()
                    lower.contains("error") ||
                        lower.contains("invalid") ||
                        lower.contains("failed") ||
                        lower.contains("unable") ||
                        lower.contains("not found") ||
                        lower.contains("403") ||
                        lower.contains("404") ||
                        lower.contains("server returned") ||
                        lower.contains("bitstream filter") ||
                        lower.contains("does not contain") ||
                        lower.contains("option not found")
                }
                .toList()
                .takeLast(4)
            if (interesting.isNotEmpty()) {
                return interesting.joinToString(" | ").take(240)
            }
            val fail = session.failStackTrace?.take(200)
            if (!fail.isNullOrBlank()) return fail
            val output = session.output?.lineSequence()?.lastOrNull { it.isNotBlank() }
            return output?.take(200) ?: "ไม่มีรายละเอียดจาก ffmpeg"
        }
    }
}
