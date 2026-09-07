package com.saha.videodownloader.model

/**
 * One browser tab. Holds only state — the live [android.webkit.WebView] for a
 * tab lives in [com.saha.videodownloader.webview.TabWebViewHolder], keyed by [id].
 */
data class TabState(
    val id: Long,
    /** Text in the URL field for this tab. Empty so the hint shows on a new tab. */
    val urlInput: String = "",
    /** Last committed non-blank page URL — Referer source and page-key input. */
    val pageUrl: String? = null,
    /** Document title from `onReceivedTitle` — feeds download filenames. */
    val title: String? = null,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    /** URL to load when this tab's WebView is first created. */
    val pendingLoadUrl: String? = null,
    /** Tab that opened this one via `window.open` — back/close returns here. */
    val openerTabId: Long? = null,
    /** True until this tab has been shown once; keeps popup tabs from autoplaying. */
    val neverSelected: Boolean = false,
    /** Renderer process died — the tab needs a manual reload. */
    val isCrashed: Boolean = false,
    val blockedAdCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Short label for the tab strip. */
    val label: String
        get() = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: hostOf(pageUrl ?: pendingLoadUrl)
            ?: "แท็บใหม่"

    val isBlank: Boolean
        get() = pageUrl.isNullOrBlank() && pendingLoadUrl.isNullOrBlank()

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
