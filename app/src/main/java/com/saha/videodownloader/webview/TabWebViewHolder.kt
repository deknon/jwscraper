package com.saha.videodownloader.webview

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.ViewModel
import com.saha.videodownloader.download.WebViewCookieHelper
import com.saha.videodownloader.model.VideoType

/**
 * Owns one live [WebView] per tab.
 *
 * It is a [ViewModel] so the WebViews survive Activity recreation (dark-mode
 * or locale change — rotation is already absorbed by `configChanges` in the
 * manifest), while each WebView is constructed with a [MutableContextWrapper]
 * that is rebased onto the live Activity in [attachActivity] and back onto the
 * application context in [detachActivity]. An app-context WebView has no
 * window token, which breaks JS dialogs, `<select>` popups and the file
 * chooser; an Activity-context WebView held by a ViewModel leaks the Activity.
 * The wrapper avoids both.
 *
 * Destruction happens in exactly two places: [destroyTab] and [onCleared].
 * Compose must never destroy a WebView — see `detachFrom`.
 */
class TabWebViewHolder : ViewModel() {

    interface Callbacks {
        fun onPageStarted(tabId: Long, url: String?)
        fun onPageFinished(tabId: Long, url: String?, canGoBack: Boolean)
        fun onTitle(tabId: Long, title: String?)
        fun onVideoDetected(tabId: Long, url: String, type: VideoType)
        fun onAdBlocked(tabId: Long, url: String)
        fun onRendererGone(tabId: Long)

        /** @return the new tab's id, or `null` when denied (cap reached / popup blocked). */
        fun onCreateWindowRequest(openerTabId: Long, isUserGesture: Boolean): Long?
        fun onCloseWindowRequest(tabId: Long)
    }

    private val webViews = LinkedHashMap<Long, WebView>()
    private val contexts = HashMap<Long, MutableContextWrapper>()

    @Volatile
    private var appContext: Context? = null
    private var activity: Activity? = null
    private var callbacks: Callbacks? = null
    private var userAgent: String = ""

    fun attachActivity(activity: Activity, callbacks: Callbacks, userAgent: String) {
        this.activity = activity
        this.callbacks = callbacks
        this.appContext = activity.applicationContext
        this.userAgent = userAgent
        contexts.values.forEach { it.baseContext = activity }
        applyUserAgent(userAgent)
    }

    /** Call from `Activity.onDestroy` so no destroyed Activity stays reachable. */
    fun detachActivity() {
        val fallback = appContext ?: return
        contexts.values.forEach { it.baseContext = fallback }
        activity = null
        callbacks = null
    }

    // ---- lifecycle of individual tabs -------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(tabId: Long, allowAutoplay: Boolean): WebView? {
        webViews[tabId]?.let { return it }
        val base: Context = activity ?: appContext ?: return null
        val wrapper = MutableContextWrapper(base)
        val webView = WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Prevent WebView from drawing outside its Compose slot on HyperOS.
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = !allowAutoplay
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = this@TabWebViewHolder.userAgent
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // target=_blank / window.open become new tabs via onCreateWindow.
            // Without the gesture requirement, ad scripts spawn tabs on load.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            // CDN / JW Player auth cookies are often third-party.
            WebViewCookieHelper.enableFor(this)

            webViewClient = TabWebViewClient(tabId)
            webChromeClient = TabWebChromeClient(tabId)
        }
        contexts[tabId] = wrapper
        webViews[tabId] = webView
        return webView
    }

    fun peek(tabId: Long): WebView? = webViews[tabId]

    fun tabIdOf(webView: WebView?): Long? {
        if (webView == null) return null
        return webViews.entries.firstOrNull { it.value === webView }?.key
    }

    fun liveTabIds(): List<Long> = webViews.keys.toList()

    fun destroyTab(tabId: Long) {
        // Drop the entry first so a late callback can never hand a destroyed
        // instance back to Compose.
        val webView = webViews.remove(tabId) ?: return
        contexts.remove(tabId)
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    override fun onCleared() {
        webViews.keys.toList().forEach(::destroyTab)
        activity = null
        callbacks = null
        super.onCleared()
    }

    // ---- Compose viewport --------------------------------------------------

    /**
     * Idempotent: adds missing tabs, removes closed ones, and shows only the
     * active tab. Background tabs stay [View.INVISIBLE] rather than
     * [View.GONE] so they keep measuring — a `GONE` tab created by
     * `window.open` would lay its page out at width 0.
     */
    fun syncInto(
        container: FrameLayout,
        tabIds: List<Long>,
        activeTabId: Long?,
        allowAutoplay: (Long) -> Boolean
    ) {
        tabIds.forEach { tabId ->
            val webView = getOrCreate(tabId, allowAutoplay(tabId)) ?: return@forEach
            webView.settings.mediaPlaybackRequiresUserGesture = !allowAutoplay(tabId)
            if (webView.parent !== container) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            val tabId = tabIdOf(child as? WebView)
            if (tabId == null || tabId !in tabIds) {
                container.removeViewAt(index)
            }
        }

        tabIds.forEach { tabId ->
            val webView = webViews[tabId] ?: return@forEach
            if (tabId == activeTabId) {
                webView.visibility = View.VISIBLE
                container.bringChildToFront(webView)
                runCatching { webView.onResume() }
            } else {
                webView.visibility = View.INVISIBLE
                // Stops audio/animation in background tabs but keeps loading,
                // so detection still works. pauseTimers() is process-global
                // and must never be used for a single tab.
                runCatching { webView.onPause() }
            }
        }
    }

    /** Detach only — Compose disposing a slot must not destroy a tab. */
    fun detachFrom(container: FrameLayout) {
        container.removeAllViews()
    }

    // ---- imperative navigation -------------------------------------------

    fun loadUrl(tabId: Long, url: String) {
        peek(tabId)?.loadUrl(url)
    }

    fun reload(tabId: Long) {
        val webView = peek(tabId) ?: return
        if (webView.url.isNullOrBlank()) return
        webView.reload()
    }

    fun goBack(tabId: Long): Boolean {
        val webView = peek(tabId) ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun canGoBack(tabId: Long): Boolean = peek(tabId)?.canGoBack() == true

    fun applyUserAgent(userAgent: String) {
        this.userAgent = userAgent
        webViews.values.forEach { webView ->
            if (webView.settings.userAgentString != userAgent) {
                webView.settings.userAgentString = userAgent
            }
        }
    }

    fun pauseAll() {
        webViews.values.forEach { runCatching { it.onPause() } }
    }

    fun resumeTab(tabId: Long?) {
        val webView = tabId?.let { peek(it) } ?: return
        runCatching { webView.onResume() }
    }

    /** Cookies and WebStorage are process-global — this clears every tab. */
    fun clearSiteData() {
        webViews.values.forEach { webView ->
            runCatching {
                webView.stopLoading()
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearHistory()
            }
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Called from `Activity.onTrimMemory`. Eight live WebViews can hold
     * several hundred MB, so shed background tabs' renderers before the
     * system kills the process.
     */
    fun onTrimMemory(level: Int, activeTabId: Long?) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        webViews.forEach { (tabId, webView) ->
            if (tabId != activeTabId) {
                runCatching {
                    webView.onPause()
                    webView.clearCache(false)
                }
            }
        }
    }

    // ---- clients ---------------------------------------------------------

    private inner class TabWebViewClient(private val ownTabId: Long) :
        VideoInterceptingWebViewClient(
            tabId = ownTabId,
            onVideoUrlDetected = { tabId, url, type ->
                callbacks?.onVideoDetected(tabId, url, type)
            },
            onAdBlocked = { tabId, url ->
                callbacks?.onAdBlocked(tabId, url)
            }
        ) {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            callbacks?.onPageStarted(ownTabId, url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            WebViewCookieHelper.flush()
            callbacks?.onPageFinished(ownTabId, url, view?.canGoBack() == true)
        }

        /**
         * All WebViews in a process usually share one sandboxed renderer, so
         * one background tab's OOM would otherwise take down the whole app.
         * Returning `true` keeps the process alive.
         */
        @TargetApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val tabId = tabIdOf(view) ?: ownTabId
            destroyTab(tabId)
            callbacks?.onRendererGone(tabId)
            return true
        }
    }

    private inner class TabWebChromeClient(private val ownTabId: Long) : WebChromeClient() {

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            callbacks?.onTitle(ownTabId, title)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val message = resultMsg ?: return false
            val newTabId = callbacks?.onCreateWindowRequest(ownTabId, isUserGesture)
                ?: return false
            // Fully configured before hand-off; Chromium loads the pending URL
            // itself and reports it through onPageStarted — never loadUrl() it.
            val child = getOrCreate(newTabId, allowAutoplay = false) ?: return false
            val transport = message.obj as? WebView.WebViewTransport ?: return false
            transport.webView = child
            message.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            super.onCloseWindow(window)
            tabIdOf(window)?.let { callbacks?.onCloseWindowRequest(it) }
        }
    }
}