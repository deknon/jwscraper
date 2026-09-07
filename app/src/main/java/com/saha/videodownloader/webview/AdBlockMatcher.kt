package com.saha.videodownloader.webview

/**
 * Lightweight URL-based ad blocker. Never blocks URLs that [VideoUrlMatcher]
 * classifies as video candidates.
 */
object AdBlockMatcher {

    private val hostHints = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "pagead2.googlesyndication.com",
        "adservice.google",
        "adnxs.com",
        "adsrvr.org",
        "advertising.com",
        "adsafeprotected.com",
        "adform.net",
        "adcolony.com",
        "ads-twitter.com",
        "ads.yahoo.com",
        "amazon-adsystem.com",
        "moatads.com",
        "scorecardresearch.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "facebook.com/tr",
        "connect.facebook.net",
        "hotjar.com",
        "clarity.ms"
    )

    private val pathHints = listOf(
        "/ads/",
        "/ad/",
        "/advert",
        "/advertise",
        "/banner/",
        "/popup/",
        "/pagead/",
        "/pagead2/",
        "/sponsored/",
        "adsystem",
        "adserver",
        "adservice",
        "doubleclick",
        "googlesyndication",
        "prebid",
        "pixel.gif",
        "1x1.gif"
    )

    /**
     * Returns true when [url] should be blocked as an ad/tracker request.
     * Video media URLs matched by [VideoUrlMatcher] are never blocked.
     */
    fun shouldBlock(url: String): Boolean {
        if (url.isBlank()) return false
        if (VideoUrlMatcher.matchVideoUrl(url) != null) return false

        val lower = url.lowercase()
        if (hostHints.any { lower.contains(it) }) return true
        if (pathHints.any { lower.contains(it) }) return true
        return false
    }
}
