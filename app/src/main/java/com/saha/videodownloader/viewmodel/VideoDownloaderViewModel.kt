package com.saha.videodownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saha.videodownloader.download.DownloadHelper
import com.saha.videodownloader.download.UrlHistoryStore
import com.saha.videodownloader.download.VideoMetaProber
import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.TabState
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

class VideoDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = UrlHistoryStore(application)
    private val seenUrls = synchronizedSetOf<String>()
    private val probeJobs = ConcurrentHashMap<String, Job>()
    private val probeLimiter = Semaphore(permits = 3)
    private val tabIdSeq = AtomicLong(1L)

    /**
     * Set by `MainActivity` so closing a tab also destroys its WebView.
     * Both sides are ViewModels in the same store, so this holds no Activity.
     */
    var tabDestroyer: ((Long) -> Unit)? = null

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<Long?>(null)
    val activeTabId: StateFlow<Long?> = _activeTabId.asStateFlow()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideoUrl>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideoUrl>> = _detectedVideos.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _recentUrls = MutableStateFlow(historyStore.getAll())
    val recentUrls: StateFlow<List<String>> = _recentUrls.asStateFlow()

    private val _useDesktopUa = MutableStateFlow(false)
    val useDesktopUa: StateFlow<Boolean> = _useDesktopUa.asStateFlow()

    /** Incremented when a popup was suppressed, so the UI can offer an override. */
    private val _blockedPopupCount = MutableStateFlow(0)
    val blockedPopupCount: StateFlow<Int> = _blockedPopupCount.asStateFlow()

    init {
        openTab()
    }

    // ---- tabs -------------------------------------------------------------

    /**
     * Read synchronously (not a derived flow) so callers never see a stale
     * value while an `Eagerly` sharing coroutine is still starting.
     */
    fun activeTabState(): TabState? {
        val id = _activeTabId.value ?: return null
        return _tabs.value.firstOrNull { it.id == id }
    }

    fun canOpenTab(): Boolean = TabListReducer.canOpen(_tabs.value)

    /** @return the new tab's id, or `null` when [TabListReducer.MAX_TABS] is reached. */
    fun openTab(
        url: String? = null,
        openerTabId: Long? = null,
        select: Boolean = true
    ): Long? {
        val result = TabListReducer.open(
            tabs = _tabs.value,
            activeId = _activeTabId.value,
            newId = tabIdSeq.getAndIncrement(),
            url = url,
            openerTabId = openerTabId,
            select = select
        )
        if (result.openedId == null) return null
        _tabs.value = result.tabs
        _activeTabId.value = result.activeId
        if (url != null) rememberUrl(url)
        return result.openedId
    }

    fun closeTab(tabId: Long) {
        val result = TabListReducer.close(_tabs.value, _activeTabId.value, tabId)
        if (result.tabs === _tabs.value) return
        _tabs.value = result.tabs
        _activeTabId.value = result.activeId
        tabDestroyer?.invoke(tabId)
        // Detections stay: they are the deliverable, and each row carries its
        // own pageUrl / pageTitle so it remains downloadable.
        if (result.tabs.isEmpty()) openTab()
    }

    fun closeActiveTab() {
        _activeTabId.value?.let(::closeTab)
    }

    fun selectTab(tabId: Long) {
        val id = TabListReducer.select(_tabs.value, tabId) ?: return
        _activeTabId.value = id
        // A popup tab is created with autoplay off; showing it makes it a
        // normal tab.
        updateTab(id) { if (it.neverSelected) it.copy(neverSelected = false) else it }
    }

    fun setTabUrlInput(tabId: Long, text: String) {
        updateTab(tabId) { it.copy(urlInput = text) }
    }

    /**
     * Queues [url] for [tabId]. The screen loads it into the tab's WebView and
     * then calls [consumePendingLoad].
     */
    fun requestLoad(tabId: Long, url: String) {
        if (url.isBlank()) return
        rememberUrl(url)
        clearDetectionsForTab(tabId)
        updateTab(tabId) {
            it.copy(
                urlInput = url,
                pendingLoadUrl = url,
                isCrashed = false,
                blockedAdCount = 0
            )
        }
    }

    fun consumePendingLoad(tabId: Long) {
        updateTab(tabId) { if (it.pendingLoadUrl == null) it else it.copy(pendingLoadUrl = null) }
    }

    /** Opens [url] in a new tab, falling back to the active tab when capped. */
    fun requestNavigate(url: String) {
        val active = activeTabState()
        if (active == null) {
            openTab(url)
            return
        }
        if (active.isBlank) {
            requestLoad(active.id, url)
            return
        }
        if (openTab(url) == null) {
            requestLoad(active.id, url)
        }
    }

    // ---- WebView callbacks (may arrive for a closed tab) ------------------

    fun onTabPageStarted(tabId: Long, url: String?) {
        updateTab(tabId) { tab ->
            val committed = url?.takeIf { it.isNotBlank() && it != "about:blank" }
            tab.copy(
                isLoading = true,
                isCrashed = false,
                urlInput = committed ?: tab.urlInput,
                pageUrl = committed ?: tab.pageUrl,
                // The old title belongs to the previous page.
                title = if (committed != null && committed != tab.pageUrl) null else tab.title
            )
        }
    }

    fun onTabPageFinished(tabId: Long, url: String?, canGoBack: Boolean) {
        updateTab(tabId) { tab ->
            val committed = url?.takeIf { it.isNotBlank() && it != "about:blank" }
            tab.copy(
                isLoading = false,
                canGoBack = canGoBack,
                urlInput = committed ?: tab.urlInput,
                pageUrl = committed ?: tab.pageUrl
            )
        }
    }

    fun setTabTitle(tabId: Long, title: String?) {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", true)) return
        updateTab(tabId) { it.copy(title = trimmed) }
    }

    fun setTabCanGoBack(tabId: Long, canGoBack: Boolean) {
        updateTab(tabId) { if (it.canGoBack == canGoBack) it else it.copy(canGoBack = canGoBack) }
    }

    fun onTabAdBlocked(tabId: Long) {
        updateTab(tabId) { it.copy(blockedAdCount = it.blockedAdCount + 1) }
    }

    fun onPopupBlocked() {
        _blockedPopupCount.update { it + 1 }
    }

    fun onRendererGone(tabId: Long) {
        updateTab(tabId) { it.copy(isCrashed = true, isLoading = false) }
    }

    /** Marks every tab except [exceptTabId] to reload the next time it is shown. */
    fun markOtherTabsForReload(exceptTabId: Long?) {
        _tabs.update { list ->
            list.map { tab ->
                val url = tab.pageUrl
                if (tab.id == exceptTabId || url.isNullOrBlank()) tab
                else tab.copy(pendingLoadUrl = url)
            }
        }
    }

    private fun updateTab(tabId: Long, transform: (TabState) -> TabState) {
        _tabs.update { TabListReducer.update(it, tabId, transform) }
    }

    // ---- detections ------------------------------------------------------

    /**
     * Called from [com.saha.videodownloader.webview.VideoInterceptingWebViewClient]
     * on a WebView's own thread. [tabId] is passed in rather than read from
     * "current tab": detections from a background tab would otherwise be
     * tagged with the foreground page.
     */
    fun onVideoUrlDetected(tabId: Long, url: String, type: VideoType) {
        val added = synchronized(seenUrls) { seenUrls.add(url) }
        if (!added) return

        val tab = _tabs.value.firstOrNull { it.id == tabId }
        val pageUrl = tab?.pageUrl
        _detectedVideos.update { current ->
            current + DetectedVideoUrl(
                url = url,
                type = type,
                detectedAt = System.currentTimeMillis(),
                pageUrl = pageUrl,
                pageTitle = tab?.title,
                tabId = tabId,
                metaState = VideoMetaState.PENDING
            )
        }
        enqueueMetaProbe(url, type, pageUrl)
    }

    fun clearDetectedUrls() {
        probeJobs.values.forEach { it.cancel() }
        probeJobs.clear()
        synchronized(seenUrls) { seenUrls.clear() }
        _detectedVideos.value = emptyList()
    }

    /** Used on in-tab navigation so other tabs keep their finds. */
    fun clearDetectionsForTab(tabId: Long) {
        val before = _detectedVideos.value
        if (before.isEmpty()) return
        val kept = DetectionFilters.removeForTab(before, tabId)
        if (kept.size == before.size) return
        commitDetections(before, kept)
    }

    /** Drops detections from pages other than the active tab's current page. */
    fun keepOnlyActiveTabVideos(): Int {
        val before = _detectedVideos.value
        val pageUrl = activeTabState()?.pageUrl
        if (pageUrl.isNullOrBlank()) {
            val removed = before.size
            clearDetectedUrls()
            return removed
        }
        val kept = DetectionFilters.keepOnlyPage(before, pageUrl)
        if (kept.size == before.size) return 0
        commitDetections(before, kept)
        return before.size - kept.size
    }

    private fun commitDetections(
        before: List<DetectedVideoUrl>,
        kept: List<DetectedVideoUrl>
    ) {
        val keptUrls = kept.map { it.url }.toSet()
        (before.map { it.url }.toSet() - keptUrls).forEach { url ->
            probeJobs.remove(url)?.cancel()
        }
        synchronized(seenUrls) {
            seenUrls.clear()
            seenUrls.addAll(keptUrls)
        }
        _detectedVideos.value = kept
    }

    // ---- misc ------------------------------------------------------------

    fun setDownloading(downloading: Boolean) {
        _isDownloading.value = downloading
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
    }

    fun currentUserAgent(): String =
        if (_useDesktopUa.value) DownloadHelper.DESKTOP_CHROME_UA else DownloadHelper.MOBILE_CHROME_UA

    private fun enqueueMetaProbe(url: String, type: VideoType, pageUrl: String?) {
        probeJobs[url]?.cancel()
        val job = viewModelScope.launch {
            updateVideo(url) { it.copy(metaState = VideoMetaState.LOADING) }
            val meta = try {
                probeLimiter.withPermit {
                    withContext(Dispatchers.IO) {
                        VideoMetaProber.probe(
                            url = url,
                            type = type,
                            // The page this URL came from, not whatever page is
                            // in front now — the probe is async.
                            pageUrl = pageUrl,
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
