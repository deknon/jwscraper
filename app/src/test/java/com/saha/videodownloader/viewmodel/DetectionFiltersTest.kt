package com.saha.videodownloader.viewmodel

import com.saha.videodownloader.model.DetectedVideoUrl
import com.saha.videodownloader.model.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFiltersTest {

    private fun detection(url: String, pageUrl: String?, tabId: Long?) = DetectedVideoUrl(
        url = url,
        type = VideoType.HLS,
        detectedAt = 0L,
        pageUrl = pageUrl,
        tabId = tabId
    )

    private val items = listOf(
        detection("https://cdn/a.m3u8", "https://site/page1", 1L),
        detection("https://cdn/b.m3u8", "https://site/page1", 1L),
        detection("https://cdn/c.m3u8", "https://site/page2", 2L)
    )

    @Test
    fun detectsRowsFromOtherPages() {
        assertTrue(DetectionFilters.hasDetectionsFromOtherPages(items, "https://site/page1"))
        assertFalse(
            DetectionFilters.hasDetectionsFromOtherPages(
                items.take(2),
                "https://site/page1"
            )
        )
    }

    @Test
    fun noCurrentPageMeansAnyRowCounts() {
        assertTrue(DetectionFilters.hasDetectionsFromOtherPages(items, null))
        assertFalse(DetectionFilters.hasDetectionsFromOtherPages(emptyList(), null))
    }

    @Test
    fun keepOnlyPageMatchesOnPageKeyNotTab() {
        // Fragment differences must not split a page.
        val kept = DetectionFilters.keepOnlyPage(items, "https://site/page1#x")
        assertEquals(listOf("https://cdn/a.m3u8", "https://cdn/b.m3u8"), kept.map { it.url })
    }

    @Test
    fun keepOnlyPageWithNoPageDropsEverything() {
        assertTrue(DetectionFilters.keepOnlyPage(items, null).isEmpty())
    }

    @Test
    fun removeForTabDropsOnlyThatTab() {
        val kept = DetectionFilters.removeForTab(items, 1L)
        assertEquals(listOf("https://cdn/c.m3u8"), kept.map { it.url })
        assertEquals(3, DetectionFilters.removeForTab(items, 99L).size)
    }

    @Test
    fun countForTab() {
        assertEquals(2, DetectionFilters.countForTab(items, 1L))
        assertEquals(1, DetectionFilters.countForTab(items, 2L))
        assertEquals(0, DetectionFilters.countForTab(items, 3L))
        assertEquals(0, DetectionFilters.countForTab(items, null))
    }
}
