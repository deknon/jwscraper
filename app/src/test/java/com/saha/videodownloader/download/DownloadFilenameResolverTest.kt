package com.saha.videodownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilenameResolverTest {

    @Test
    fun parseContentDispositionFilenameStar() {
        val name = DownloadFilenameResolver.parseContentDisposition(
            "attachment; filename=\"fallback.mp4\"; filename*=UTF-8''%E0%B8%A7%E0%B8%B4%E0%B8%94%E0%B8%B5%E0%B9%82%E0%B8%AD.mp4"
        )
        assertEquals("วิดีโอ.mp4", name)
    }

    @Test
    fun parseContentDispositionQuoted() {
        val name = DownloadFilenameResolver.parseContentDisposition(
            "inline; filename=\"My Cool Video.mp4\""
        )
        assertEquals("My Cool Video.mp4", name)
    }

    @Test
    fun sanitizeStripsPathAndLimitsLength() {
        val longTitle = "A".repeat(120) + " end"
        val name = DownloadFilenameResolver.sanitizeFilename(longTitle, ".mp4")!!
        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.removeSuffix(".mp4").length <= DownloadFilenameResolver.MAX_STEM_LENGTH)
        assertFalse(name.contains('/'))
    }

    @Test
    fun fromHintsPrefersPageTitle() {
        val name = DownloadFilenameResolver.fromHints(
            mediaUrl = "https://cdn.example.com/a/b/master.m3u8",
            pageTitle = "ตัวอย่างตอนที่ 12",
            defaultExt = ".mp4"
        )
        assertEquals("ตัวอย่างตอนที่ 12.mp4", name)
    }
}
