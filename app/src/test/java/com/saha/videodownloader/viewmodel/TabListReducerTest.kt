package com.saha.videodownloader.viewmodel

import com.saha.videodownloader.model.TabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TabListReducerTest {

    private fun tabsOf(vararg ids: Long) = ids.map { TabState(id = it) }

    @Test
    fun openAppendsAndSelects() {
        val result = TabListReducer.open(tabsOf(1L), activeId = 1L, newId = 2L, url = "https://a.com")
        assertEquals(listOf(1L, 2L), result.tabs.map { it.id })
        assertEquals(2L, result.activeId)
        assertEquals(2L, result.openedId)
        assertEquals("https://a.com", result.tabs.last().pendingLoadUrl)
        assertEquals("https://a.com", result.tabs.last().urlInput)
    }

    @Test
    fun openWithoutSelectKeepsActiveAndMarksNeverSelected() {
        val result = TabListReducer.open(tabsOf(1L), activeId = 1L, newId = 2L, select = false)
        assertEquals(1L, result.activeId)
        assertEquals(2L, result.openedId)
        assertTrue(result.tabs.last().neverSelected)
    }

    @Test
    fun openInsertsChildNextToItsOpener() {
        val result = TabListReducer.open(
            tabs = tabsOf(1L, 2L, 3L),
            activeId = 1L,
            newId = 9L,
            openerTabId = 1L
        )
        assertEquals(listOf(1L, 9L, 2L, 3L), result.tabs.map { it.id })
        assertEquals(1L, result.tabs[1].openerTabId)
    }

    @Test
    fun openAtCapIsRejectedWithoutMutating() {
        val full = tabsOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
        assertFalse(TabListReducer.canOpen(full))
        val result = TabListReducer.open(full, activeId = 1L, newId = 99L)
        assertNull(result.openedId)
        assertSame(full, result.tabs)
        assertEquals(1L, result.activeId)
    }

    @Test
    fun closeInactiveTabKeepsActive() {
        val result = TabListReducer.close(tabsOf(1L, 2L, 3L), activeId = 3L, closeId = 1L)
        assertEquals(listOf(2L, 3L), result.tabs.map { it.id })
        assertEquals(3L, result.activeId)
    }

    @Test
    fun closeActiveFallsBackToOpenerFirst() {
        val tabs = listOf(
            TabState(id = 1L),
            TabState(id = 2L),
            TabState(id = 3L, openerTabId = 1L)
        )
        val result = TabListReducer.close(tabs, activeId = 3L, closeId = 3L)
        assertEquals(1L, result.activeId)
    }

    @Test
    fun closeActiveFallsBackLeftThenRight() {
        val left = TabListReducer.close(tabsOf(1L, 2L, 3L), activeId = 2L, closeId = 2L)
        assertEquals(1L, left.activeId)

        val right = TabListReducer.close(tabsOf(1L, 2L), activeId = 1L, closeId = 1L)
        assertEquals(2L, right.activeId)
    }

    @Test
    fun closeDeadOpenerFallsBackToNeighbour() {
        val tabs = listOf(TabState(id = 5L), TabState(id = 6L, openerTabId = 99L))
        val result = TabListReducer.close(tabs, activeId = 6L, closeId = 6L)
        assertEquals(5L, result.activeId)
    }

    @Test
    fun closeLastTabEmptiesTheList() {
        val result = TabListReducer.close(tabsOf(1L), activeId = 1L, closeId = 1L)
        assertTrue(result.tabs.isEmpty())
        assertNull(result.activeId)
    }

    @Test
    fun closeUnknownIdIsNoOp() {
        val tabs = tabsOf(1L, 2L)
        val result = TabListReducer.close(tabs, activeId = 1L, closeId = 42L)
        assertSame(tabs, result.tabs)
        assertEquals(1L, result.activeId)
    }

    @Test
    fun selectOnlyAcceptsKnownIds() {
        val tabs = tabsOf(1L, 2L)
        assertEquals(2L, TabListReducer.select(tabs, 2L))
        assertNull(TabListReducer.select(tabs, 7L))
    }

    @Test
    fun updateOnlyTouchesTheNamedTabAndIgnoresUnknownIds() {
        val tabs = tabsOf(1L, 2L)
        val updated = TabListReducer.update(tabs, 2L) { it.copy(title = "hello") }
        assertNull(updated.first().title)
        assertEquals("hello", updated.last().title)

        // A callback for an already-closed tab must not resurrect it.
        assertSame(tabs, TabListReducer.update(tabs, 42L) { it.copy(title = "nope") })
    }

    @Test
    fun labelPrefersTitleThenHostThenFallback() {
        assertEquals("หนัง", TabState(id = 1L, title = "หนัง", pageUrl = "https://a.com/x").label)
        assertEquals("a.com", TabState(id = 1L, pageUrl = "https://www.a.com/x").label)
        assertEquals("แท็บใหม่", TabState(id = 1L).label)
    }

    @Test
    fun blankTabDetection() {
        assertTrue(TabState(id = 1L).isBlank)
        assertFalse(TabState(id = 1L, pageUrl = "https://a.com").isBlank)
        assertFalse(TabState(id = 1L, pendingLoadUrl = "https://a.com").isBlank)
    }
}
