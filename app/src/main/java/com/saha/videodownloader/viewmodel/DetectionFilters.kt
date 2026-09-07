package com.saha.videodownloader.viewmodel

import com.saha.videodownloader.model.DetectedVideoUrl

/**
 * Pure filters over the detected-video list, extracted from the ViewModel so
 * they are unit-testable on the JVM.
 *
 * The list is deliberately global across tabs: dedupe is by URL (which is also
 * the `LazyColumn` key), the probe limiter is shared, and the bottom sheet is
 * the app's inbox — a video found in tab 3 should still be downloadable while
 * browsing tab 1.
 */
object DetectionFilters {

    fun hasDetectionsFromOtherPages(
        items: List<DetectedVideoUrl>,
        currentPageUrl: String?
    ): Boolean {
        val currentKey = PageKeys.pageKey(currentPageUrl) ?: return items.isNotEmpty()
        return items.any { PageKeys.pageKey(it.pageUrl) != currentKey }
    }

    fun keepOnlyPage(
        items: List<DetectedVideoUrl>,
        pageUrl: String?
    ): List<DetectedVideoUrl> {
        val currentKey = PageKeys.pageKey(pageUrl) ?: return emptyList()
        return items.filter { PageKeys.pageKey(it.pageUrl) == currentKey }
    }

    fun removeForTab(items: List<DetectedVideoUrl>, tabId: Long): List<DetectedVideoUrl> =
        items.filterNot { it.tabId == tabId }

    fun countForTab(items: List<DetectedVideoUrl>, tabId: Long?): Int {
        if (tabId == null) return 0
        return items.count { it.tabId == tabId }
    }
}
