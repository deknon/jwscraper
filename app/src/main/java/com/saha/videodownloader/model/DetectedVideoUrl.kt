package com.saha.videodownloader.model

data class DetectedVideoUrl(
    val url: String,
    val type: VideoType,
    val detectedAt: Long
)

enum class VideoType {
    MP4,
    HLS,
    UNKNOWN
}
