package com.saha.videodownloader.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockFilterTest {

    private val filter = AdBlockFilter.parse(
        sequenceOf(
            "# comment",
            "",
            "doubleclick.net",
            "0.0.0.0 popads.net",
            "||exoclick.com^",
            "url:/pagead/"
        )
    )

    @Test
    fun parsesAllRuleForms() {
        assertEquals(4, filter.ruleCount)
    }

    @Test
    fun matchesHostAndSubdomain() {
        assertTrue(filter.blocks("https://doubleclick.net/x"))
        assertTrue(filter.blocks("https://ad.g.doubleclick.net/pcs/click"))
        assertTrue(filter.blocks("http://c1.popads.net/pop.js"))
        assertTrue(filter.blocks("https://main.exoclick.com/tag.js"))
    }

    @Test
    fun doesNotMatchOnLabelBoundaryViolation() {
        // Plain endsWith would wrongly block these.
        assertFalse(filter.blocks("https://notdoubleclick.net/a.js"))
        assertFalse(filter.blocks("https://mypopads.net/a.js"))
    }

    @Test
    fun matchesUrlSubstringRule() {
        assertTrue(filter.blocks("https://legit-site.com/pagead/js/adsbygoogle.js"))
        assertFalse(filter.blocks("https://legit-site.com/page/adventure.js"))
    }

    @Test
    fun ignoresNonHttpAndBlankUrls() {
        assertFalse(filter.blocks(""))
        assertFalse(filter.blocks("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(filter.blocks("about:blank"))
    }

    @Test
    fun emptyFilterBlocksNothing() {
        assertFalse(AdBlockFilter.EMPTY.blocks("https://doubleclick.net/x"))
        assertEquals(0, AdBlockFilter.EMPTY.ruleCount)
    }

    @Test
    fun leavesUnrelatedMediaHostsAlone() {
        assertFalse(filter.blocks("https://cdn.example.com/hls/master.m3u8"))
        assertFalse(filter.blocks("https://content.jwplatform.com/videos/x.mp4"))
    }

    @Test
    fun hostOfHandlesPortsUserinfoAndIpv6() {
        assertEquals("example.com", AdBlockFilter.hostOf("https://example.com/a?b=1#c"))
        assertEquals("example.com", AdBlockFilter.hostOf("https://example.com:8443/a"))
        assertEquals("example.com", AdBlockFilter.hostOf("https://user:pw@example.com/a"))
        assertEquals("[::1]", AdBlockFilter.hostOf("http://[::1]:8080/a"))
        assertEquals("example.com", AdBlockFilter.hostOf("HTTPS://EXAMPLE.COM"))
        assertNull(AdBlockFilter.hostOf("not-a-url"))
        assertNull(AdBlockFilter.hostOf(""))
    }

    @Test
    fun bundledRuleFormatStaysParseable() {
        // Sanity check on the shipped file's shape without touching resources.
        val parsed = AdBlockFilter.parse(
            sequenceOf("googlesyndication.com", "url:/adserver/", "   ", "! abp comment")
        )
        assertTrue(parsed.blocks("https://pagead2.googlesyndication.com/pagead/js/x.js"))
        assertTrue(parsed.blocks("https://cdn.site.com/adserver/banner"))
        assertEquals(2, parsed.ruleCount)
    }
}
