package com.saha.videodownloader.webview

import com.saha.videodownloader.model.VideoType

/**
 * Pure URL matcher — no Android framework dependencies.
 * Safe to unit-test on the JVM.
 */
object VideoUrlMatcher {

    private val m3u8Regex = Regex("""\.m3u8(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val mp4Regex = Regex("""\.mp4(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val hlsPathRegex = Regex("""/hls/""", RegexOption.IGNORE_CASE)
    private val jwOrManifestRegex = Regex(
        """(manifest|jwplatform|cdn\.jwplayer\.com)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns the detected [VideoType], or `null` if the URL is not a video candidate.
     *
     * Priority:
     * - `.m3u8` or `/hls/` → [VideoType.HLS]
     * - `.mp4` → [VideoType.MP4]
     * - `jwplatform` / `cdn.jwplayer.com` / `manifest` → [VideoType.UNKNOWN]
     */
    fun matchVideoUrl(url: String): VideoType? {
        if (url.isBlank()) return null

        // Fast path: skip obviously non-video resource extensions early.
        val lower = url.lowercase()
        if (looksLikeNonVideoAsset(lower)) return null

        if (m3u8Regex.containsMatchIn(url) || hlsPathRegex.containsMatchIn(url)) {
            return VideoType.HLS
        }
        if (mp4Regex.containsMatchIn(url)) {
            return VideoType.MP4
        }
        if (jwOrManifestRegex.containsMatchIn(url)) {
            return VideoType.UNKNOWN
        }
        return null
    }

    private fun looksLikeNonVideoAsset(lowerUrl: String): Boolean {
        // Cheap early reject for images/css/fonts/scripts — called frequently from WebView.
        return lowerUrl.endsWith(".css") ||
            lowerUrl.endsWith(".js") ||
            lowerUrl.endsWith(".png") ||
            lowerUrl.endsWith(".jpg") ||
            lowerUrl.endsWith(".jpeg") ||
            lowerUrl.endsWith(".gif") ||
            lowerUrl.endsWith(".webp") ||
            lowerUrl.endsWith(".svg") ||
            lowerUrl.endsWith(".ico") ||
            lowerUrl.endsWith(".woff") ||
            lowerUrl.endsWith(".woff2") ||
            lowerUrl.endsWith(".ttf") ||
            lowerUrl.endsWith(".otf") ||
            lowerUrl.endsWith(".map")
    }
}
