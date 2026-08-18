package com.saha.videodownloader.download

/**
 * Public Downloads subfolder used for MP4 / muxed files.
 * MediaStore relative path: `Download/saha vdo download`
 */
object DownloadPaths {
    const val SUBFOLDER = "saha vdo download"

    /** MediaStore.Downloads.RELATIVE_PATH (API 29+). */
    const val MEDIA_STORE_RELATIVE_PATH = "Download/$SUBFOLDER"

    /** Path under [android.os.Environment.DIRECTORY_DOWNLOADS] for DownloadManager. */
    fun destinationPath(filename: String): String = "$SUBFOLDER/$filename"
}
