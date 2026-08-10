package com.saha.videodownloader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class VideoDownloaderViewModel : ViewModel() {

    private val seenUrls = synchronizedSetOf<String>()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideoUrl>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideoUrl>> = _detectedVideos.asStateFlow()

    private val _filter = MutableStateFlow(DetectedFilter.ALL)
    val filter: StateFlow<DetectedFilter> = _filter.asStateFlow()

    val filteredVideos: StateFlow<List<DetectedVideoUrl>> =
        combine(_detectedVideos, _filter) { videos, selectedFilter ->
            when (selectedFilter) {
                DetectedFilter.ALL -> videos
                DetectedFilter.MP4 -> videos.filter { it.type == VideoType.MP4 }
                DetectedFilter.HLS -> videos.filter { it.type == VideoType.HLS }
                DetectedFilter.UNKNOWN -> videos.filter { it.type == VideoType.UNKNOWN }
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
        val selected = _selectedUrl.value ?: return
        val selectedItem = _detectedVideos.value.firstOrNull { it.url == selected } ?: return
        val matches = when (filter) {
            DetectedFilter.ALL -> true
            DetectedFilter.MP4 -> selectedItem.type == VideoType.MP4
            DetectedFilter.HLS -> selectedItem.type == VideoType.HLS
            DetectedFilter.UNKNOWN -> selectedItem.type == VideoType.UNKNOWN
        }
        if (!matches) {
            _selectedUrl.value = null
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
        _pendingNavigateUrl.value = url
    }

    fun consumeNavigateRequest() {
        _pendingNavigateUrl.value = null
    }

    private fun <T> synchronizedSetOf(): MutableSet<T> =
        java.util.Collections.synchronizedSet(mutableSetOf())
}
