package com.saha.videodownloader.model

/**
 * A download visible in the library screen.
 */
data class LibraryDownload(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val kind: Kind,
    val state: State,
    val progressPercent: Float,
    /** Content URI for ffmpeg/MP4 files; null for Media3 cache entries. */
    val contentUri: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    enum class Kind {
        MEDIA3_CACHE,
        FFMPEG_MP4
    }

    enum class State {
        QUEUED,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        REMOVING,
        RESTARTING,
        STOPPED
    }

    val canPlay: Boolean
        get() = state == State.COMPLETED && (kind == Kind.MEDIA3_CACHE || !contentUri.isNullOrBlank())
}
