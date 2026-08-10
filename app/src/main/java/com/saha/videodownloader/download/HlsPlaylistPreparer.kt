package com.saha.videodownloader.download

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * Prefetches an HLS URL with browser-like headers, validates it is a playlist,
 * and resolves master playlists to the highest-bandwidth media playlist.
 *
 * Avoids the common ffmpeg error "Invalid data found when processing input"
 * when the first URL is HTML, JSON, or a master playlist ffmpeg mishandles.
 */
object HlsPlaylistPreparer {

    data class Result(
        val inputUrl: String,
        val localPlaylist: File?,
        val note: String
    )

    fun prepare(
        mediaUrl: String,
        pageUrl: String?,
        userAgent: String,
        workDir: File
    ): Result {
        workDir.mkdirs()
        val headers = browserHeaders(mediaUrl, pageUrl, userAgent)

        val first = fetchText(mediaUrl, headers)
            ?: return Result(mediaUrl, null, "ดาวน์โหลด playlist ไม่ได้")

        if (!looksLikeM3u8(first.body)) {
            val kind = sniffBody(first.body)
            throw IllegalStateException(
                "URL ไม่ใช่ HLS playlist ($kind). เปิดหน้าให้วิดีโอเล่นก่อน แล้วเลือกลิงก์ .m3u8"
            )
        }

        var playlistUrl = first.finalUrl
        var playlistBody = first.body

        if (isMasterPlaylist(playlistBody)) {
            val variant = pickBestVariant(playlistBody, playlistUrl)
                ?: throw IllegalStateException("master playlist ไม่มี variant ที่ใช้ได้")
            val second = fetchText(variant, headers)
                ?: throw IllegalStateException("ดาวน์โหลด media playlist ไม่ได้")
            if (!looksLikeM3u8(second.body)) {
                throw IllegalStateException("media playlist ไม่ถูกต้อง (${sniffBody(second.body)})")
            }
            playlistUrl = second.finalUrl
            playlistBody = second.body
        }

        val rewritten = rewriteToAbsolute(playlistBody, playlistUrl)
        val local = File(workDir, "playlist_${System.currentTimeMillis()}.m3u8")
        local.writeText(rewritten, Charsets.UTF_8)

        return Result(
            inputUrl = local.absolutePath,
            localPlaylist = local,
            note = "playlist พร้อม (${rewritten.lineSequence().count()} บรรทัด)"
        )
    }

    private data class Fetch(val finalUrl: String, val body: String)

    private fun fetchText(url: String, headers: Map<String, String>): Fetch? {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
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
                if (code !in 200..299) {
                    Log.w(TAG, "playlist HTTP $code for $current")
                    return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                val charset = charsetFromContentType(conn.contentType) ?: Charsets.UTF_8
                val body = bytes.toString(charset)
                return Fetch(finalUrl = current, body = body)
            } catch (t: Throwable) {
                Log.w(TAG, "playlist fetch failed: $current", t)
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun browserHeaders(
        mediaUrl: String,
        pageUrl: String?,
        userAgent: String
    ): Map<String, String> {
        val referer = WebViewCookieHelper.resolveReferer(mediaUrl, pageUrl)
        val origin = WebViewCookieHelper.resolveOrigin(pageUrl, mediaUrl)
        val cookie = WebViewCookieHelper.collectCookieHeader(mediaUrl, pageUrl)
        return buildMap {
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9,th;q=0.8")
            put("Referer", referer)
            if (!origin.isNullOrBlank()) put("Origin", origin)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
    }

    private fun looksLikeM3u8(body: String): Boolean {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return trimmed.startsWith("#EXTM3U")
    }

    private fun isMasterPlaylist(body: String): Boolean =
        body.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    private fun pickBestVariant(body: String, baseUrl: String): String? {
        data class Variant(val bandwidth: Long, val uri: String)
        val variants = mutableListOf<Variant>()
        val lines = body.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) {
                    // Skip blank / comment lines between tags and URI.
                    if (lines[j].startsWith("#EXT")) break
                    j++
                }
                if (j < lines.size && !lines[j].startsWith("#")) {
                    variants += Variant(bw, resolveUrl(baseUrl, lines[j]))
                    i = j
                }
            }
            i++
        }
        return variants.maxByOrNull { it.bandwidth }?.uri
    }

    private fun rewriteToAbsolute(body: String, baseUrl: String): String {
        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) ->
                    rewriteQuotedUris(line, baseUrl)
                trimmed.isEmpty() || trimmed.startsWith("#") -> line
                else -> resolveUrl(baseUrl, trimmed)
            }
        } + "\n"
    }

    private fun rewriteQuotedUris(line: String, baseUrl: String): String =
        Regex("""URI="([^"]+)"""").replace(line) { match ->
            val abs = resolveUrl(baseUrl, match.groupValues[1])
            """URI="$abs""""
        }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private fun sniffBody(body: String): String {
        val t = body.trimStart().take(80).lowercase()
        return when {
            t.startsWith("<!doctype") || t.startsWith("<html") -> "HTML"
            t.startsWith("{") || t.startsWith("[") -> "JSON"
            t.startsWith("#extm3u") -> "m3u8"
            t.isBlank() -> "ว่าง"
            else -> "ข้อมูลไม่รู้จัก"
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

    private const val TAG = "HlsPlaylistPreparer"
}
