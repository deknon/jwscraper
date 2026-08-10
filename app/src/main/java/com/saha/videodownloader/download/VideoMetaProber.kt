package com.saha.videodownloader.download

import android.media.MediaMetadataRetriever
import android.util.Log
import com.saha.videodownloader.model.VideoType
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight pre-download probes for content length and duration.
 * Uses WebView-captured cookies/headers so CDN-gated URLs work.
 */
object VideoMetaProber {

    data class Meta(
        val contentLengthBytes: Long? = null,
        val durationMs: Long? = null,
        val sizeIsEstimate: Boolean = false
    )

    fun probe(
        url: String,
        type: VideoType,
        pageUrl: String?,
        userAgent: String
    ): Meta {
        val headers = CapturedMediaHeaders.mergeFor(url, pageUrl, userAgent)
        return when (type) {
            VideoType.HLS -> probeHls(url, headers)
            VideoType.MP4, VideoType.UNKNOWN -> probeDirect(url, headers)
        }
    }

    private fun probeDirect(url: String, headers: Map<String, String>): Meta {
        val length = probeContentLength(url, headers)
        val duration = probeMediaDuration(url, headers)
        return Meta(
            contentLengthBytes = length,
            durationMs = duration,
            sizeIsEstimate = false
        )
    }

    private fun probeHls(url: String, headers: Map<String, String>): Meta {
        val first = fetchText(url, headers) ?: return Meta()
        if (!looksLikeM3u8(first.body)) return Meta()

        var playlistUrl = first.finalUrl
        var playlistBody = first.body
        var bandwidth: Long? = null

        if (isMasterPlaylist(playlistBody)) {
            val variant = pickBestVariant(playlistBody, playlistUrl) ?: return Meta()
            bandwidth = variant.bandwidth.takeIf { it > 0L }
            val second = fetchText(variant.uri, headers) ?: return Meta()
            if (!looksLikeM3u8(second.body)) return Meta()
            playlistUrl = second.finalUrl
            playlistBody = second.body
        }

        val durationMs = sumExtInfMs(playlistBody)
        val approxBytes = if (durationMs != null && bandwidth != null && bandwidth > 0L) {
            // BANDWIDTH is bits/sec → bytes ≈ bits/8
            ((bandwidth.toDouble() / 8.0) * (durationMs / 1000.0)).toLong().coerceAtLeast(0L)
        } else {
            null
        }

        return Meta(
            contentLengthBytes = approxBytes,
            durationMs = durationMs,
            sizeIsEstimate = approxBytes != null
        )
    }

    private fun probeContentLength(url: String, headers: Map<String, String>): Long? {
        headContentLength(url, headers)?.let { return it }
        return rangeContentLength(url, headers)
    }

    private fun headContentLength(url: String, headers: Map<String, String>): Long? {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "HEAD"
                useCaches = false
                setRequestProperty("Connection", "close")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 || code == 300) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = resolveUrl(current, loc)
                    redirects++
                    continue
                }
                if (code !in 200..299) return null
                val len = conn.contentLengthLong
                return len.takeIf { it > 0L }
            } catch (t: Throwable) {
                Log.d(TAG, "HEAD failed: $current — ${t.message}")
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun rangeContentLength(url: String, headers: Map<String, String>): Long? {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
                setRequestProperty("Range", "bytes=0-0")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 || code == 300) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = resolveUrl(current, loc)
                    redirects++
                    continue
                }
                if (code !in 200..299 && code != 206) return null
                parseContentRangeTotal(conn.getHeaderField("Content-Range"))?.let { return it }
                val len = conn.contentLengthLong
                return len.takeIf { it > 0L && code != 206 }
            } catch (t: Throwable) {
                Log.d(TAG, "Range GET failed: $current — ${t.message}")
                return null
            } finally {
                // Drain a tiny bit so the connection can close cleanly, then disconnect.
                runCatching { conn.inputStream.use { it.readBytes() } }
                conn.disconnect()
            }
        }
        return null
    }

    private fun probeMediaDuration(url: String, headers: Map<String, String>): Long? {
        val retriever = MediaMetadataRetriever()
        val error = AtomicReference<Throwable?>(null)
        val thread = Thread({
            try {
                retriever.setDataSource(url, headers)
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "meta-duration").apply {
            isDaemon = true
            start()
        }
        thread.join(6_000)
        if (thread.isAlive) {
            runCatching { retriever.release() }
            thread.interrupt()
            Log.d(TAG, "duration probe timed out for $url")
            return null
        }
        if (error.get() != null) {
            runCatching { retriever.release() }
            Log.d(TAG, "duration probe failed: ${error.get()?.message}")
            return null
        }
        return try {
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            raw?.toLongOrNull()?.takeIf { it > 0L }
        } catch (t: Throwable) {
            Log.d(TAG, "duration extract failed: ${t.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class Fetch(val finalUrl: String, val body: String)
    private data class Variant(
        val bandwidth: Long,
        val uri: String,
        val rankBandwidth: Long = bandwidth
    )

    private fun fetchText(url: String, headers: Map<String, String>): Fetch? {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 || code == 300) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = resolveUrl(current, loc)
                    redirects++
                    continue
                }
                if (code !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                // Cap playlist body — metadata probes should stay light.
                if (bytes.size > 2_000_000) return null
                val charset = charsetFromContentType(conn.contentType) ?: Charsets.UTF_8
                return Fetch(finalUrl = current, body = bytes.toString(charset))
            } catch (t: Throwable) {
                Log.d(TAG, "playlist fetch failed: $current — ${t.message}")
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun looksLikeM3u8(body: String): Boolean {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return trimmed.startsWith("#EXTM3U")
    }

    private fun isMasterPlaylist(body: String): Boolean =
        body.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    private fun pickBestVariant(body: String, baseUrl: String): Variant? {
        val variants = mutableListOf<Variant>()
        val lines = body.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val avg = Regex("""AVERAGE-BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                val withoutAvg = line.replace(
                    Regex("""AVERAGE-BANDWIDTH=\d+""", RegexOption.IGNORE_CASE),
                    ""
                )
                val peak = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(withoutAvg)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                // Prefer AVERAGE-BANDWIDTH for size estimate; peak for ranking.
                val rankBw = maxOf(avg ?: 0L, peak ?: 0L)
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) {
                    if (lines[j].startsWith("#EXT")) break
                    j++
                }
                if (j < lines.size && !lines[j].startsWith("#")) {
                    variants += Variant(
                        bandwidth = avg ?: peak ?: 0L,
                        uri = resolveUrl(baseUrl, lines[j]),
                        rankBandwidth = rankBw
                    )
                    i = j
                }
            }
            i++
        }
        return variants.maxByOrNull { it.rankBandwidth }
    }

    private fun sumExtInfMs(body: String): Long? {
        var totalSec = 0.0
        var found = false
        val re = Regex("""#EXTINF\s*:\s*([\d.]+)""", RegexOption.IGNORE_CASE)
        body.lineSequence().forEach { line ->
            val m = re.find(line.trim()) ?: return@forEach
            val sec = m.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
            totalSec += sec
            found = true
        }
        if (!found || totalSec <= 0.0) return null
        return (totalSec * 1000.0).toLong().coerceAtLeast(1L)
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // Content-Range: bytes 0-0/123456
        val total = header.substringAfter('/', missingDelimiterValue = "").trim()
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private fun charsetFromContentType(contentType: String?): Charset? {
        if (contentType.isNullOrBlank()) return null
        val marker = "charset="
        val idx = contentType.lowercase().indexOf(marker)
        if (idx < 0) return null
        val name = contentType.substring(idx + marker.length).substringBefore(';').trim()
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private const val TAG = "VideoMetaProber"
}
