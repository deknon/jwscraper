package com.saha.videodownloader.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.saha.videodownloader.download.CapturedMediaHeaders
import com.saha.videodownloader.model.VideoType
import java.io.ByteArrayInputStream

/**
 * Intercepts WebView network traffic to detect video URLs and optionally
 * block ad/tracker requests when [adBlockEnabled] returns true.
 *
 * Callbacks may fire off the UI thread — the injected [onVideoUrlDetected]
 * must be thread-safe.
 */
open class VideoInterceptingWebViewClient(
    private val onVideoUrlDetected: (url: String, type: VideoType) -> Unit,
    private val adBlockEnabled: () -> Boolean = { false }
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (!url.isNullOrBlank()) {
            inspectUrl(url)
            CapturedMediaHeaders.capture(url, request?.requestHeaders)
            if (adBlockEnabled() && AdBlockMatcher.shouldBlock(url)) {
                return emptyBlockedResponse()
            }
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (!url.isNullOrBlank()) {
            inspectUrl(url)
            if (adBlockEnabled() && AdBlockMatcher.shouldBlock(url)) {
                return emptyBlockedResponse()
            }
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

    private fun inspectUrl(url: String) {
        val type = VideoUrlMatcher.matchVideoUrl(url) ?: return
        onVideoUrlDetected(url, type)
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
}
