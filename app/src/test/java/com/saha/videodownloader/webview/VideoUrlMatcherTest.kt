package com.saha.videodownloader.webview

import com.saha.videodownloader.model.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoUrlMatcherTest {

    @Test
    fun matchM3u8AsHls() {
        assertEquals(
            VideoType.HLS,
            VideoUrlMatcher.matchVideoUrl("https://cdn.example.com/stream/master.m3u8")
        )
        assertEquals(
            VideoType.HLS,
            VideoUrlMatcher.matchVideoUrl("https://cdn.example.com/a.m3u8?token=abc")
        )
    }

    @Test
    fun matchHlsPathAsHls() {
        assertEquals(
            VideoType.HLS,
            VideoUrlMatcher.matchVideoUrl("https://cdn.example.com/hls/playlist")
        )
    }

    @Test
    fun matchMp4() {
        assertEquals(
            VideoType.MP4,
            VideoUrlMatcher.matchVideoUrl("https://cdn.example.com/video.mp4")
        )
        assertEquals(
            VideoType.MP4,
            VideoUrlMatcher.matchVideoUrl("https://cdn.example.com/video.mp4?exp=1&sig=x")
        )
    }

    @Test
    fun matchJwAndManifestAsUnknown() {
        assertEquals(
            VideoType.UNKNOWN,
            VideoUrlMatcher.matchVideoUrl("https://cdn.jwplayer.com/manifests/abc123")
        )
        assertEquals(
            VideoType.UNKNOWN,
            VideoUrlMatcher.matchVideoUrl("https://content.jwplatform.com/videos/xyz")
        )
        assertEquals(
            VideoType.UNKNOWN,
            VideoUrlMatcher.matchVideoUrl("https://example.com/path/manifest/xyz")
        )
    }

    @Test
    fun ignoreNonVideoAssets() {
        assertNull(VideoUrlMatcher.matchVideoUrl("https://example.com/style.css"))
        assertNull(VideoUrlMatcher.matchVideoUrl("https://example.com/app.js"))
        assertNull(VideoUrlMatcher.matchVideoUrl("https://example.com/logo.png"))
        assertNull(VideoUrlMatcher.matchVideoUrl(""))
    }
}
