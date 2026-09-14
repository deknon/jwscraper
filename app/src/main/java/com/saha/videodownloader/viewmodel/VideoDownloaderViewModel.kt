package com.saha.videodownloader.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saha.videodownloader.download.CapturedMediaHeaders
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.DownloadSettingsStore
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
import java.util.concurrent.atomic.AtomicLong

data class BrowserTab(
    val id: Long,
    val title: String = BrowserTab.NEW_TAB_TITLE,
    val urlInput: String = "",
    /** URL the WebView should load (null = no pending navigation). */
    val loadUrl: String? = null,
    val currentPageUrl: String? = null,
    val currentPageTitle: String? = null,
    val detectedVideos: List<DetectedVideoUrl> = emptyList(),
    val isPageLoading: Boolean = false,
    val canGoBack: Boolean = false
) {
    companion object {
        const val NEW_TAB_TITLE = "แท็บใหม่"
        const val MAX_TABS = 5
    }

    val isBlankNewTab: Boolean
        get() {
            val page = currentPageUrl
            val load = loadUrl
            val input = urlInput.trim()
            val pageBlank = page.isNullOrBlank() || page == "about:blank"
            val loadBlank = load.isNullOrBlank() || load == "about:blank"
            val inputBlank = input.isEmpty() || input == "about:blank"
            return pageBlank && loadBlank && inputBlank
        }

    val displayTitle: String
        get() {
            val fromTitle = currentPageTitle?.trim().orEmpty()
            if (fromTitle.isNotEmpty() &&
                !fromTitle.equals("about:blank", true) &&
                fromTitle != NEW_TAB_TITLE
            ) {
                return fromTitle
            }
            val fromUrl = (currentPageUrl ?: loadUrl ?: urlInput).trim()
            if (fromUrl.isNotEmpty() && fromUrl != "about:blank") {
                return try {
                    java.net.URI(fromUrl).host?.removePrefix("www.") ?: fromUrl
                } catch (_: Exception) {
                    fromUrl.take(24)
                }
            }
            return NEW_TAB_TITLE
        }
}

class VideoDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = UrlHistoryStore(application)
    private val tabIdSeq = AtomicLong(1L)
    private val seenUrlsByTab = ConcurrentHashMap<Long, MutableSet<String>>()
    private val probeJobs = ConcurrentHashMap<String, Job>()
    private val probeLimiter = Semaphore(permits = 3)
    private val webViewStates = mutableMapOf<Long, Bundle>()

    private val _tabs = MutableStateFlow(listOf(createTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _pendingNavigateUrl = MutableStateFlow<String?>(null)
    val pendingNavigateUrl: StateFlow<String?> = _pendingNavigateUrl.asStateFlow()

    private val _recentUrls = MutableStateFlow(historyStore.getAll())
    val recentUrls: StateFlow<List<String>> = _recentUrls.asStateFlow()

    private val _useDesktopUa = MutableStateFlow(false)
    val useDesktopUa: StateFlow<Boolean> = _useDesktopUa.asStateFlow()

    private val _adBlockEnabled = MutableStateFlow(
        DownloadSettingsStore.isAdBlockEnabled(application)
    )
    val adBlockEnabled: StateFlow<Boolean> = _adBlockEnabled.asStateFlow()

    /** Bumped when global settings (UA / adblock) require WebView reload. */
    private val _reloadToken = MutableStateFlow(0)
    val reloadToken: StateFlow<Int> = _reloadToken.asStateFlow()

    fun activeTab(): BrowserTab =
        _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: _tabs.value.first()

    fun setActiveTab(tabId: Long) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /**
     * Opens a blank tab. Returns the new tab id, or null if at [BrowserTab.MAX_TABS].
     */
    fun addTab(): Long? {
        if (_tabs.value.size >= BrowserTab.MAX_TABS) return null
        val tab = createTab()
        _tabs.update { it + tab }
        _activeTabId.value = tab.id
        return tab.id
    }

    /**
     * Closes [tabId]. Keeps at least one tab. Returns false if close was refused.
     */
    fun closeTab(tabId: Long): Boolean {
        val current = _tabs.value
        if (current.size <= 1) return false
        val closing = current.firstOrNull { it.id == tabId } ?: return false
        clearTabDetections(closing.id)
        seenUrlsByTab.remove(closing.id)
        webViewStates.remove(closing.id)
        CapturedMediaHeaders.clearTab(closing.id)
        val remaining = current.filter { it.id != tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.last().id
        }
        return true
    }

    fun updateTabUrlInput(tabId: Long, input: String) {
        updateTab(tabId) { it.copy(urlInput = input) }
    }

    fun navigateTab(tabId: Long, url: String) {
        rememberUrl(url)
        clearTabDetections(tabId)
        updateTab(tabId) {
            it.copy(
                urlInput = url,
                loadUrl = url,
                currentPageUrl = null,
                currentPageTitle = null,
                detectedVideos = emptyList(),
                isPageLoading = true,
                title = BrowserTab.NEW_TAB_TITLE
            )
        }
    }

    /** Clears the one-shot navigation target after the WebView has accepted it. */
    fun consumeTabLoadUrl(tabId: Long) {
        updateTab(tabId) { tab ->
            if (tab.loadUrl == null) tab else tab.copy(loadUrl = null)
        }
    }

    fun saveTabWebViewState(tabId: Long, state: Bundle) {
        webViewStates[tabId] = state
    }

    fun restoreTabWebViewState(tabId: Long): Bundle? =
        webViewStates.remove(tabId)

    /**
     * External / share URL: prefer a new tab; reuse the active blank "แท็บใหม่"
     * instead of stacking empty tabs.
     * @return true if navigation was applied, false if tab limit blocked a new tab.
     */
    fun openExternalUrl(url: String): Boolean {
        rememberUrl(url)
        val active = activeTab()
        if (active.isBlankNewTab) {
            navigateTab(active.id, url)
            return true
        }
        if (_tabs.value.size >= BrowserTab.MAX_TABS) {
            // Still navigate the active tab so the share is not lost.
            navigateTab(active.id, url)
            return false
        }
        val tab = createTab(urlInput = url, loadUrl = url)
        _tabs.update { it + tab }
        _activeTabId.value = tab.id
        return true
    }

    fun openDetectedUrl(url: String, background: Boolean): Boolean {
        rememberUrl(url)
        val active = activeTab()
        if (!background && active.isBlankNewTab) {
            navigateTab(active.id, url)
            return true
        }
        if (_tabs.value.size >= BrowserTab.MAX_TABS) {
            return false
        }
        val tab = createTab(urlInput = url, loadUrl = url)
        _tabs.update { it + tab }
        if (!background) {
            _activeTabId.value = tab.id
        }
        return true
    }

    fun setTabCanGoBack(tabId: Long, canGoBack: Boolean) {
        updateTab(tabId) { it.copy(canGoBack = canGoBack) }
    }

    fun setCurrentPageUrl(tabId: Long, url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        updateTab(tabId) { tab ->
            val titleReset = if (tab.currentPageUrl != url) null else tab.currentPageTitle
            tab.copy(
                currentPageUrl = url,
                urlInput = url,
                currentPageTitle = titleReset,
                title = tab.displayTitle
            )
        }
    }

    fun setCurrentPageTitle(tabId: Long, title: String?) {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", true)) return
        updateTab(tabId) {
            it.copy(currentPageTitle = trimmed, title = trimmed)
        }
    }

    fun setPageLoading(tabId: Long, loading: Boolean) {
        updateTab(tabId) { it.copy(isPageLoading = loading) }
    }

    fun onVideoUrlDetected(tabId: Long, url: String, type: VideoType) {
        val seen = seenUrlsByTab.getOrPut(tabId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        val added = synchronized(seen) { seen.add(url) }
        if (!added) return

        val pageUrl = _tabs.value.firstOrNull { it.id == tabId }?.currentPageUrl
        updateTab(tabId) { tab ->
            tab.copy(
                detectedVideos = tab.detectedVideos + DetectedVideoUrl(
                    url = url,
                    type = type,
                    detectedAt = System.currentTimeMillis(),
                    pageUrl = pageUrl,
                    tabId = tabId,
                    metaState = VideoMetaState.PENDING
                )
            )
        }
        enqueueMetaProbe(tabId, url, type)
    }

    fun clearDetectedUrls(tabId: Long = _activeTabId.value) {
        clearTabDetections(tabId)
        updateTab(tabId) { it.copy(detectedVideos = emptyList()) }
    }

    fun keepOnlyCurrentPageVideos(tabId: Long = _activeTabId.value): Int {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return 0
        val currentKey = pageKey(tab.currentPageUrl)
        if (currentKey == null) {
            val removed = tab.detectedVideos.size
            clearDetectedUrls(tabId)
            return removed
        }

        val before = tab.detectedVideos
        val kept = before.filter { pageKey(it.pageUrl) == currentKey }
        val removedUrls = before.map { it.url }.toSet() - kept.map { it.url }.toSet()
        if (removedUrls.isEmpty()) return 0

        removedUrls.forEach { url ->
            probeJobs.remove(probeKey(tabId, url))?.cancel()
        }
        val seen = seenUrlsByTab.getOrPut(tabId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        synchronized(seen) {
            seen.clear()
            seen.addAll(kept.map { it.url })
        }
        updateTab(tabId) { it.copy(detectedVideos = kept) }
        return removedUrls.size
    }

    fun hasDetectionsFromOtherPages(tabId: Long = _activeTabId.value): Boolean {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return false
        val currentKey = pageKey(tab.currentPageUrl) ?: return tab.detectedVideos.isNotEmpty()
        return tab.detectedVideos.any { pageKey(it.pageUrl) != currentKey }
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

    fun setAdBlockEnabled(enabled: Boolean) {
        if (_adBlockEnabled.value == enabled) return
        DownloadSettingsStore.setAdBlockEnabled(getApplication(), enabled)
        _adBlockEnabled.value = enabled
        _reloadToken.update { it + 1 }
    }

    fun reloadPage() {
        _reloadToken.update { it + 1 }
    }

    fun currentUserAgent(): String =
        if (_useDesktopUa.value) DownloadHelper.DESKTOP_CHROME_UA else DownloadHelper.MOBILE_CHROME_UA

    private fun createTab(
        urlInput: String = "",
        loadUrl: String? = null
    ): BrowserTab {
        val id = tabIdSeq.getAndIncrement()
        seenUrlsByTab[id] = java.util.Collections.synchronizedSet(mutableSetOf())
        return BrowserTab(
            id = id,
            urlInput = urlInput,
            loadUrl = loadUrl,
            isPageLoading = loadUrl != null
        )
    }

    private fun updateTab(tabId: Long, transform: (BrowserTab) -> BrowserTab) {
        _tabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
    }

    private fun clearTabDetections(tabId: Long) {
        val prefix = "$tabId|"
        probeJobs.keys.filter { it.startsWith(prefix) }.forEach { key ->
            probeJobs.remove(key)?.cancel()
        }
        seenUrlsByTab[tabId]?.let { seen ->
            synchronized(seen) { seen.clear() }
        }
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
                if (!uri.query.isNullOrBlank()) append("?").append(uri.query)
            }
        } catch (_: Exception) {
            url.substringBefore('#').trimEnd('/')
        }
    }

    private fun probeKey(tabId: Long, url: String) = "$tabId|$url"

    private fun enqueueMetaProbe(tabId: Long, url: String, type: VideoType) {
        val key = probeKey(tabId, url)
        probeJobs[key]?.cancel()
        val job = viewModelScope.launch {
            updateVideo(tabId, url) { it.copy(metaState = VideoMetaState.LOADING) }
            val pageUrl = _tabs.value.firstOrNull { it.id == tabId }?.currentPageUrl
            val meta = try {
                probeLimiter.withPermit {
                    withContext(Dispatchers.IO) {
                        VideoMetaProber.probe(
                            url = url,
                            type = type,
                            pageUrl = pageUrl,
                            userAgent = currentUserAgent(),
                            tabId = tabId
                        )
                    }
                }
            } catch (_: Throwable) {
                null
            }

            val stillPresent = _tabs.value
                .firstOrNull { it.id == tabId }
                ?.detectedVideos
                ?.any { it.url == url } == true
            if (!stillPresent) return@launch

            if (meta == null || (meta.contentLengthBytes == null && meta.durationMs == null)) {
                updateVideo(tabId, url) {
                    it.copy(metaState = VideoMetaState.UNAVAILABLE)
                }
            } else {
                updateVideo(tabId, url) {
                    it.copy(
                        contentLengthBytes = meta.contentLengthBytes,
                        durationMs = meta.durationMs,
                        sizeIsEstimate = meta.sizeIsEstimate,
                        metaState = VideoMetaState.READY
                    )
                }
            }
        }
        probeJobs[key] = job
        job.invokeOnCompletion { probeJobs.remove(key, job) }
    }

    private fun updateVideo(
        tabId: Long,
        url: String,
        transform: (DetectedVideoUrl) -> DetectedVideoUrl
    ) {
        updateTab(tabId) { tab ->
            tab.copy(
                detectedVideos = tab.detectedVideos.map {
                    if (it.url == url) transform(it) else it
                }
            )
        }
    }
}
