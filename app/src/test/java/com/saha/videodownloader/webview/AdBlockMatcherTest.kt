package com.saha.videodownloader.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockMatcherTest {

    @Test
    fun blocksCommonAdHosts() {
        assertTrue(
            AdBlockMatcher.shouldBlock(
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
            )
        )
        assertTrue(
            AdBlockMatcher.shouldBlock(
                "https://securepubads.g.doubleclick.net/tag/js/gpt.js"
            )
        )
        assertTrue(
            AdBlockMatcher.shouldBlock(
                "https://www.google-analytics.com/collect?v=1"
            )
        )
        assertTrue(
            AdBlockMatcher.shouldBlock(
                "https://cdn.taboola.com/libtrc/example/loader.js"
            )
        )
    }

    @Test
    fun blocksAdPathHints() {
        assertTrue(AdBlockMatcher.shouldBlock("https://cdn.example.com/ads/banner.js"))
        assertTrue(AdBlockMatcher.shouldBlock("https://cdn.example.com/pagead/show"))
    }

    @Test
    fun neverBlocksVideoUrls() {
        assertFalse(
            AdBlockMatcher.shouldBlock("https://cdn.example.com/stream/master.m3u8")
        )
        assertFalse(
            AdBlockMatcher.shouldBlock("https://cdn.example.com/video.mp4")
        )
        assertFalse(
            AdBlockMatcher.shouldBlock("https://cdn.jwplayer.com/manifests/abc123")
        )
        // Even if an ad host path somehow carries a video extension, video wins.
        assertFalse(
            AdBlockMatcher.shouldBlock("https://ads.example.com/promo.mp4")
        )
    }

    @Test
    fun allowsNormalPageAssets() {
        assertFalse(AdBlockMatcher.shouldBlock("https://example.com/index.html"))
        assertFalse(AdBlockMatcher.shouldBlock("https://cdn.example.com/app.js"))
        assertFalse(AdBlockMatcher.shouldBlock("https://cdn.example.com/style.css"))
        assertFalse(AdBlockMatcher.shouldBlock(""))
    }
}
