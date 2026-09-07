package com.saha.videodownloader.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.saha.videodownloader.download.CapturedMediaHeaders
import com.saha.videodownloader.model.VideoType
import java.io.ByteArrayInputStream

/**
 * Intercepts WebView network traffic to detect video URLs, and — when the
 * ad-block toggle is on — aborts requests and navigations that match
 * [AdBlockStore]'s filter.
 *
 * Callbacks may fire off the UI thread on the WebView's own thread, and one
 * client instance belongs to exactly one tab, so every callback carries
 * [tabId]: reading a global "current tab" would mis-tag detections that come
 * from a background tab.
 *
 * Detection always runs *before* the block decision, so ad-blocking can never
 * cost a detection or a captured CDN token. A URL that [VideoUrlMatcher]
 * classifies as video is never blocked.
 */
open class VideoInterceptingWebViewClient(
    private val tabId: Long,
    private val onVideoUrlDetected: (tabId: Long, url: String, type: VideoType) -> Unit,
    private val onAdBlocked: (tabId: Long, url: String) -> Unit = { _, _ -> }
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url.isNullOrBlank()) return null

        // Observe first, always.
        inspectUrl(url)
        CapturedMediaHeaders.capture(url, request.requestHeaders)

        // Main-frame navigations are cancelled in shouldOverrideUrlLoading instead:
        // an empty response there would blank the page the user is looking at.
        if (request.isForMainFrame) return null

        if (shouldBlock(url)) {
            onAdBlocked(tabId, url)
            return blockedResponse()
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url.isNullOrBlank()) return null
        inspectUrl(url)
        if (shouldBlock(url)) {
            onAdBlocked(tabId, url)
            return blockedResponse()
        }
        return null
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        // Fallback for requests that may not go through shouldInterceptRequest.
        if (!url.isNullOrBlank()) {
            inspectUrl(url)
        }
        super.onLoadResource(view, url)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        return blockNavigation(url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return blockNavigation(url)
    }

    /** `true` cancels the navigation and leaves the current page untouched. */
    private fun blockNavigation(url: String): Boolean {
        if (!AdBlockStore.enabled.value) return false

        if (!isNavigableScheme(url)) {
            // Streaming sites bounce to intent:// / market:// to open app stores.
            onAdBlocked(tabId, url)
            return true
        }
        if (shouldBlock(url)) {
            onAdBlocked(tabId, url)
            return true
        }
        return false
    }

    private fun shouldBlock(url: String): Boolean {
        // Never block the media we exist to find.
        if (VideoUrlMatcher.matchVideoUrl(url) != null) return false
        return AdBlockStore.blocks(url)
    }

    private fun inspectUrl(url: String) {
        val type = VideoUrlMatcher.matchVideoUrl(url) ?: return
        onVideoUrlDetected(tabId, url, type)
    }

    private companion object {

        private val NAVIGABLE_SCHEMES = listOf(
            "http://", "https://", "about:", "data:", "blob:", "file:", "javascript:"
        )

        fun isNavigableScheme(url: String): Boolean =
            NAVIGABLE_SCHEMES.any { url.startsWith(it, ignoreCase = true) }

        fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
