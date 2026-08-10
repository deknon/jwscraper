package com.saha.videodownloader.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.saha.videodownloader.download.CapturedMediaHeaders
import com.saha.videodownloader.model.VideoType

/**
 * Intercepts WebView network traffic to detect video URLs.
 *
 * Callbacks may fire off the UI thread — the injected [onVideoUrlDetected]
 * must be thread-safe.
 *
 * Always returns `null` from [shouldInterceptRequest] so the WebView continues
 * loading the real resource (we only observe, never block).
 */
open class VideoInterceptingWebViewClient(
    private val onVideoUrlDetected: (url: String, type: VideoType) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (!url.isNullOrBlank()) {
            inspectUrl(url)
            CapturedMediaHeaders.capture(url, request?.requestHeaders)
        }
        // Return null so WebView loads the resource normally.
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (!url.isNullOrBlank()) {
            inspectUrl(url)
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
}
