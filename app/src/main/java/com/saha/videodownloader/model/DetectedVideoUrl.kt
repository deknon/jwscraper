package com.saha.videodownloader.model

data class DetectedVideoUrl(
    val url: String,
    val type: VideoType,
    val detectedAt: Long,
    val contentLengthBytes: Long? = null,
    val durationMs: Long? = null,
    /** True when [contentLengthBytes] is estimated (e.g. HLS bandwidth × duration). */
    val sizeIsEstimate: Boolean = false,
    val metaState: VideoMetaState = VideoMetaState.PENDING
)

enum class VideoMetaState {
    PENDING,
    LOADING,
    READY,
    UNAVAILABLE
}

enum class VideoType {
    MP4,
    HLS,
    UNKNOWN
}
