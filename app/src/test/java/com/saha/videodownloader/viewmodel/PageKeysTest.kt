package com.saha.videodownloader.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKeysTest {

    @Test
    fun fragmentIsIgnored() {
        assertEquals(
            PageKeys.pageKey("https://example.com/watch"),
            PageKeys.pageKey("https://example.com/watch#top")
        )
    }

    @Test
    fun queryIsPartOfIdentity() {
        assertNotEquals(
            PageKeys.pageKey("https://example.com/watch?id=1"),
            PageKeys.pageKey("https://example.com/watch?id=2")
        )
    }

    @Test
    fun hostAndSchemeAreLowercased() {
        assertEquals(
            "https://example.com/watch",
            PageKeys.pageKey("HTTPS://Example.COM/watch")
        )
    }

    @Test
    fun emptyPathBecomesRoot() {
        assertEquals("https://example.com/", PageKeys.pageKey("https://example.com"))
    }

    @Test
    fun blankAndAboutBlankAreNull() {
        assertNull(PageKeys.pageKey(null))
        assertNull(PageKeys.pageKey(""))
        assertNull(PageKeys.pageKey("   "))
        assertNull(PageKeys.pageKey("about:blank"))
    }

    @Test
    fun samePageNeedsBothSides() {
        assertTrue(PageKeys.samePage("https://a.com/x", "https://a.com/x#y"))
        assertFalse(PageKeys.samePage("https://a.com/x", null))
        assertFalse(PageKeys.samePage(null, null))
    }
}
