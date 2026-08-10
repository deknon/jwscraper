package com.saha.videodownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.UrlHistoryStore
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class DetectedFilter {
    ALL,
    MP4,
    HLS,
    UNKNOWN
}

class VideoDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = UrlHistoryStore(application)
    private val seenUrls = synchronizedSetOf<String>()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideoUrl>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideoUrl>> = _detectedVideos.asStateFlow()

    private val _filter = MutableStateFlow(DetectedFilter.ALL)
    val filter: StateFlow<DetectedFilter> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredVideos: StateFlow<List<DetectedVideoUrl>> =
        combine(_detectedVideos, _filter, _searchQuery) { videos, selectedFilter, query ->
            val byType = when (selectedFilter) {
                DetectedFilter.ALL -> videos
                DetectedFilter.MP4 -> videos.filter { it.type == VideoType.MP4 }
                DetectedFilter.HLS -> videos.filter { it.type == VideoType.HLS }
                DetectedFilter.UNKNOWN -> videos.filter { it.type == VideoType.UNKNOWN }
            }
            val needle = query.trim()
            if (needle.isEmpty()) {
                byType
            } else {
                byType.filter { it.url.contains(needle, ignoreCase = true) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isPageLoading = MutableStateFlow(false)
    val isPageLoading: StateFlow<Boolean> = _isPageLoading.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _selectedUrl = MutableStateFlow<String?>(null)
    val selectedUrl: StateFlow<String?> = _selectedUrl.asStateFlow()

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
        _selectedUrl.value = null
    }

    fun setFilter(filter: DetectedFilter) {
        _filter.value = filter
        clearSelectionIfHidden()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        clearSelectionIfHidden()
    }

    private fun clearSelectionIfHidden() {
        val selected = _selectedUrl.value ?: return
        if (filteredVideos.value.none { it.url == selected }) {
            // Fall back to type/query check against source list for immediate UI.
            val item = _detectedVideos.value.firstOrNull { it.url == selected } ?: run {
                _selectedUrl.value = null
                return
            }
            val filter = _filter.value
            val typeOk = when (filter) {
                DetectedFilter.ALL -> true
                DetectedFilter.MP4 -> item.type == VideoType.MP4
                DetectedFilter.HLS -> item.type == VideoType.HLS
                DetectedFilter.UNKNOWN -> item.type == VideoType.UNKNOWN
            }
            val query = _searchQuery.value.trim()
            val queryOk = query.isEmpty() || item.url.contains(query, ignoreCase = true)
            if (!typeOk || !queryOk) {
                _selectedUrl.value = null
            }
        }
    }

    fun selectUrl(url: String?) {
        _selectedUrl.value = url
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
