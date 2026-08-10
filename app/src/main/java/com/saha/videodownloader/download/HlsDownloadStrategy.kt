package com.saha.videodownloader.download

import android.content.Context

/**
 * Strategy for downloading HLS (.m3u8) playlists and muxing segments.
 */
interface HlsDownloadStrategy {
    fun download(url: String, context: Context)
}

/**
 * Stub implementation — HLS offline download is not wired up in this version.
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
