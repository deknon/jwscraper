package com.saha.videodownloader.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.IOException
import androidx.media3.exoplayer.offline.DownloadHelper as Media3DownloadHelper

/**
 * Strategy for downloading HLS (.m3u8) playlists and caching segments offline.
 */
interface HlsDownloadStrategy {
    fun download(url: String, context: Context)
}

/**
 * Legacy stub — kept for reference / tests of the unsupported path.
 */
class NotImplementedHlsStrategy : HlsDownloadStrategy {
    override fun download(url: String, context: Context) {
        throw UnsupportedOperationException(
            "HLS download ยังไม่พร้อมใช้ในเวอร์ชันนี้ — " +
                "ต้อง integrate Media3 ExoPlayer DownloadService หรือ ffmpeg-kit " +
                "เพื่อรวม segment จาก playlist.m3u8 เป็นไฟล์เดียว"
        )
    }
}

/**
 * Media3 ExoPlayer [DownloadService] implementation (Google-recommended path).
 *
 * Caches HLS segments into app-private storage for offline playback via Media3.
 * Does not mux into a single .mp4 file (use ffmpeg-kit if you need that).
 */
@OptIn(UnstableApi::class)
class Media3HlsDownloadStrategy : HlsDownloadStrategy {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun download(url: String, context: Context) {
        val appContext = context.applicationContext
        VideoDownloadService.ensureChannel(appContext)

        val helper = Media3DownloadHelper.forMediaItem(
            appContext,
            MediaItem.fromUri(Uri.parse(url)),
            DefaultRenderersFactory(appContext),
            Media3DownloadUtil.buildHttpDataSourceFactory()
        )

        helper.prepare(
            object : Media3DownloadHelper.Callback {
                override fun onPrepared(downloadHelper: Media3DownloadHelper) {
                    try {
                        val request: DownloadRequest =
                            downloadHelper.getDownloadRequest(/* id = */ url, /* data = */ null)
                        DownloadService.sendAddDownload(
                            appContext,
                            VideoDownloadService::class.java,
                            request,
                            /* foreground = */ true
                        )
                        toast(appContext, "เริ่มดาวน์โหลด HLS (Media3 cache)")
                    } catch (e: Exception) {
                        toast(
                            appContext,
                            "เริ่ม HLS ไม่สำเร็จ: ${e.message ?: e.javaClass.simpleName}"
                        )
                    } finally {
                        downloadHelper.release()
                    }
                }

                override fun onPrepareError(downloadHelper: Media3DownloadHelper, e: IOException) {
                    downloadHelper.release()
                    toast(
                        appContext,
                        "อ่าน playlist ไม่ได้: ${e.message ?: "IO error"}"
                    )
                }
            }
        )
    }

    private fun toast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
