package com.saha.videodownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.UrlHistoryStore
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VideoDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = UrlHistoryStore(application)
    private val seenUrls = synchronizedSetOf<String>()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideoUrl>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideoUrl>> = _detectedVideos.asStateFlow()

    private val _isPageLoading = MutableStateFlow(false)
    val isPageLoading: StateFlow<Boolean> = _isPageLoading.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _pendingNavigateUrl = MutableStateFlow<String?>(null)
    val pendingNavigateUrl: StateFlow<String?> = _pendingNavigateUrl.asStateFlow()

    private val _recentUrls = MutableStateFlow(historyStore.getAll())
    val recentUrls: StateFlow<List<String>> = _recentUrls.asStateFlow()

    private val _useDesktopUa = MutableStateFlow(false)
    val useDesktopUa: StateFlow<Boolean> = _useDesktopUa.asStateFlow()

    private val _reloadToken = MutableStateFlow(0)
    val reloadToken: StateFlow<Int> = _reloadToken.asStateFlow()

    /** Last non-blank page URL loaded in the WebView — used as Referer for CDN auth. */
    private val _currentPageUrl = MutableStateFlow<String?>(null)
    val currentPageUrl: StateFlow<String?> = _currentPageUrl.asStateFlow()

    fun setCurrentPageUrl(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        _currentPageUrl.value = url
    }

    /**
     * Called from [com.saha.videodownloader.webview.VideoInterceptingWebViewClient]
     * callbacks (may be off the UI thread). Uses atomic [MutableStateFlow.update]
     * and a synchronized set keyed by URL to avoid duplicates under race.
     */
    fun onVideoUrlDetected(url: String, type: VideoType) {
        val added = synchronized(seenUrls) {
            seenUrls.add(url)
        }
        if (!added) return

        _detectedVideos.update { current ->
            current + DetectedVideoUrl(
                url = url,
                type = type,
                detectedAt = System.currentTimeMillis()
            )
        }
    }

    fun clearDetectedUrls() {
        synchronized(seenUrls) {
            seenUrls.clear()
        }
        _detectedVideos.value = emptyList()
    }

    fun setPageLoading(loading: Boolean) {
        _isPageLoading.value = loading
    }

    fun setDownloading(downloading: Boolean) {
        _isDownloading.value = downloading
    }

    fun requestNavigate(url: String) {
        rememberUrl(url)
        _pendingNavigateUrl.value = url
    }

    fun consumeNavigateRequest() {
        _pendingNavigateUrl.value = null
    }

    fun rememberUrl(url: String) {
        historyStore.add(url)
        _recentUrls.value = historyStore.getAll()
    }

    fun clearHistory() {
        historyStore.clear()
        _recentUrls.value = emptyList()
    }

    fun setUseDesktopUa(enabled: Boolean) {
        if (_useDesktopUa.value == enabled) return
        _useDesktopUa.value = enabled
        _reloadToken.update { it + 1 }
    }

    fun reloadPage() {
        _reloadToken.update { it + 1 }
    }

    fun currentUserAgent(): String =
        if (_useDesktopUa.value) DownloadHelper.DESKTOP_CHROME_UA else DownloadHelper.MOBILE_CHROME_UA

    private fun <T> synchronizedSetOf(): MutableSet<T> =
        java.util.Collections.synchronizedSet(mutableSetOf())
}
