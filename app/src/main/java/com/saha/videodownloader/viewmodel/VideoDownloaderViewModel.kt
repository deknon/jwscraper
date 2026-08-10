package com.saha.videodownloader.viewmodel

import androidx.lifecycle.ViewModel
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VideoDownloaderViewModel : ViewModel() {

    private val seenUrls = synchronizedSetOf<String>()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideoUrl>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideoUrl>> = _detectedVideos.asStateFlow()

    private val _isPageLoading = MutableStateFlow(false)
    val isPageLoading: StateFlow<Boolean> = _isPageLoading.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _selectedUrl = MutableStateFlow<String?>(null)
    val selectedUrl: StateFlow<String?> = _selectedUrl.asStateFlow()

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

    fun selectUrl(url: String?) {
        _selectedUrl.value = url
    }

    fun setPageLoading(loading: Boolean) {
        _isPageLoading.value = loading
    }

    fun setDownloading(downloading: Boolean) {
        _isDownloading.value = downloading
    }

    private fun <T> synchronizedSetOf(): MutableSet<T> =
        java.util.Collections.synchronizedSet(mutableSetOf())
}
