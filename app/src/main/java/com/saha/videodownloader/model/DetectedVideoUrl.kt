package com.saha.videodownloader.model

data class DetectedVideoUrl(
    val url: String,
    val type: VideoType,
    val detectedAt: Long,
    /** Page URL in the WebView when this media URL was first seen. */
    val pageUrl: String? = null,
    /** Document title of [pageUrl] at detection time — feeds download filenames. */
    val pageTitle: String? = null,
    /** Tab this URL was detected in. May point at a tab that is already closed. */
    val tabId: Long? = null,
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
