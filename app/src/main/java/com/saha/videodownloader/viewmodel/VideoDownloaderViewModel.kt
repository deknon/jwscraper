package com.saha.videodownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.UrlHistoryStore
import com.saha.videodownloader.download.VideoMetaProber
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoMetaState
import com.saha.videodownloader.model.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VideoDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = UrlHistoryStore(application)
    private val seenUrls = synchronizedSetOf<String>()
    private val probeJobs = ConcurrentHashMap<String, Job>()
    private val probeLimiter = Semaphore(permits = 3)

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

    /** Document title from WebChromeClient — used for download filenames. */
    private val _currentPageTitle = MutableStateFlow<String?>(null)
    val currentPageTitle: StateFlow<String?> = _currentPageTitle.asStateFlow()

    fun setCurrentPageUrl(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        if (_currentPageUrl.value != url) {
            // Title belongs to the previous page until onReceivedTitle fires.
            _currentPageTitle.value = null
        }
        _currentPageUrl.value = url
    }

    fun setCurrentPageTitle(title: String?) {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", true)) {
            return
        }
        _currentPageTitle.value = trimmed
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

        val pageUrl = _currentPageUrl.value
        _detectedVideos.update { current ->
            current + DetectedVideoUrl(
                url = url,
                type = type,
                detectedAt = System.currentTimeMillis(),
                pageUrl = pageUrl,
                metaState = VideoMetaState.PENDING
            )
        }
        enqueueMetaProbe(url, type)
    }

    fun clearDetectedUrls() {
        probeJobs.values.forEach { it.cancel() }
        probeJobs.clear()
        synchronized(seenUrls) {
            seenUrls.clear()
        }
        _detectedVideos.value = emptyList()
    }

    /**
     * Drops detections that came from other pages so the list only shows
     * videos found on the current WebView page.
     */
    fun keepOnlyCurrentPageVideos(): Int {
        val currentKey = pageKey(_currentPageUrl.value)
        if (currentKey == null) {
            val removed = _detectedVideos.value.size
            clearDetectedUrls()
            return removed
        }

        val before = _detectedVideos.value
        val kept = before.filter { pageKey(it.pageUrl) == currentKey }
        val removedUrls = before.map { it.url }.toSet() - kept.map { it.url }.toSet()
        if (removedUrls.isEmpty()) return 0

        removedUrls.forEach { url ->
            probeJobs.remove(url)?.cancel()
        }
        synchronized(seenUrls) {
            seenUrls.clear()
            seenUrls.addAll(kept.map { it.url })
        }
        _detectedVideos.value = kept
        return removedUrls.size
    }

    fun hasDetectionsFromOtherPages(): Boolean {
        val currentKey = pageKey(_currentPageUrl.value) ?: return _detectedVideos.value.isNotEmpty()
        return _detectedVideos.value.any { pageKey(it.pageUrl) != currentKey }
    }

    private fun pageKey(url: String?): String? {
        if (url.isNullOrBlank() || url == "about:blank") return null
        return try {
            val uri = java.net.URI(url)
            buildString {
                append(uri.scheme?.lowercase() ?: "https")
                append("://")
                append(uri.host?.lowercase().orEmpty())
                if (uri.port > 0) append(":").append(uri.port)
                append(uri.path.orEmpty().ifEmpty { "/" })
                // Ignore fragment; keep query — same path with different ?id= is another "page".
                if (!uri.query.isNullOrBlank()) append("?").append(uri.query)
            }
        } catch (_: Exception) {
            url.substringBefore('#').trimEnd('/')
        }
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

    private fun enqueueMetaProbe(url: String, type: VideoType) {
        probeJobs[url]?.cancel()
        val job = viewModelScope.launch {
            updateVideo(url) { it.copy(metaState = VideoMetaState.LOADING) }
            val meta = try {
                probeLimiter.withPermit {
                    withContext(Dispatchers.IO) {
                        VideoMetaProber.probe(
                            url = url,
                            type = type,
                            pageUrl = _currentPageUrl.value,
                            userAgent = currentUserAgent()
                        )
                    }
                }
            } catch (_: Throwable) {
                null
            }

            // Drop result if the list was cleared / item removed.
            if (_detectedVideos.value.none { it.url == url }) return@launch

            if (meta == null || (meta.contentLengthBytes == null && meta.durationMs == null)) {
                updateVideo(url) {
                    it.copy(metaState = VideoMetaState.UNAVAILABLE)
                }
            } else {
                updateVideo(url) {
                    it.copy(
                        contentLengthBytes = meta.contentLengthBytes,
                        durationMs = meta.durationMs,
                        sizeIsEstimate = meta.sizeIsEstimate,
                        metaState = VideoMetaState.READY
                    )
                }
            }
        }
        probeJobs[url] = job
        job.invokeOnCompletion { probeJobs.remove(url, job) }
    }

    private fun updateVideo(url: String, transform: (DetectedVideoUrl) -> DetectedVideoUrl) {
        _detectedVideos.update { list ->
            list.map { if (it.url == url) transform(it) else it }
        }
    }

    private fun <T> synchronizedSetOf(): MutableSet<T> =
        java.util.Collections.synchronizedSet(mutableSetOf())
}
