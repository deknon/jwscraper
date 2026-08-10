package com.saha.videodownloader.download

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Downloads every HLS segment / key / init map referenced by a local playlist
 * using app HTTP + WebView cookies, then rewrites the playlist to local files.
 *
 * ffmpeg then muxes offline — CDNs that block ffmpeg's TLS stack still work.
 */
object HlsOfflineBundler {

    data class Bundle(
        val localPlaylist: File,
        val segmentCount: Int,
        val tempDir: File
    )

    fun bundle(
        absolutePlaylist: File,
        workDir: File,
        headers: Map<String, String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Bundle {
        val segDir = File(workDir, "segs_${System.currentTimeMillis()}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val lines = absolutePlaylist.readLines()
        val remoteLines = mutableListOf<Pair<Int, String>>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY", true) ||
                    trimmed.startsWith("#EXT-X-MAP", true) -> {
                    val uri = quotedUri(trimmed)
                    if (!uri.isNullOrBlank() && isHttp(uri)) {
                        remoteLines += index to uri
                    }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && isHttp(trimmed) -> {
                    remoteLines += index to trimmed
                }
            }
        }

        if (remoteLines.isEmpty()) {
            throw IllegalStateException("playlist ไม่มี segment ให้ดาวน์โหลด")
        }
        if (remoteLines.size > MAX_SEGMENTS) {
            throw IllegalStateException(
                "playlist ยาวเกิน ($MAX_SEGMENTS segments) — ยังไม่รองรับไลฟ์ยาวมาก"
            )
        }

        val urlToLocal = LinkedHashMap<String, File>()
        var done = 0
        val total = remoteLines.map { it.second }.toSet().size

        remoteLines.forEach { (_, url) ->
            if (urlToLocal.containsKey(url)) {
                done = urlToLocal.size
                onProgress(done, total)
                return@forEach
            }
            val ext = guessExt(url)
            val local = File(segDir, "p${urlToLocal.size.toString().padStart(5, '0')}$ext")
            downloadToFile(url, headers, local)
            urlToLocal[url] = local
            done = urlToLocal.size
            onProgress(done, total)
        }

        val rewritten = lines.map { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY", true) ||
                    trimmed.startsWith("#EXT-X-MAP", true) -> {
                    val uri = quotedUri(trimmed) ?: return@map line
                    val local = urlToLocal[uri] ?: return@map line
                    Regex("""URI="([^"]+)"""").replace(line) {
                        """URI="${local.absolutePath}""""
                    }
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && isHttp(trimmed) -> {
                    urlToLocal[trimmed]?.absolutePath ?: line
                }
                else -> line
            }
        }.joinToString("\n", postfix = "\n")

        val out = File(workDir, "offline_${System.currentTimeMillis()}.m3u8")
        out.writeText(rewritten, Charsets.UTF_8)
        return Bundle(
            localPlaylist = out,
            segmentCount = urlToLocal.size,
            tempDir = segDir
        )
    }

    private fun downloadToFile(url: String, headers: Map<String, String>, dest: File) {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 || code == 300) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect ไม่มี Location ($code)")
                    current = resolveUrl(current, loc)
                    redirects++
                    continue
                }
                if (code == 403 || code == 401) {
                    throw IllegalStateException(
                        "HTTP $code ตอนดาวน์โหลด segment — เปิดหน้าเว็บให้วิดีโอเล่นแล้วลองใหม่"
                    )
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code ตอนดาวน์โหลด segment")
                }
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.length() <= 0L) {
                    dest.delete()
                    throw IllegalStateException("segment ว่าง: $url")
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("redirect มากเกินไป: $url")
    }

    private fun quotedUri(line: String): String? =
        Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)

    private fun isHttp(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun guessExt(url: String): String {
        val path = try {
            URI(url).path ?: url
        } catch (_: Exception) {
            url
        }
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            ext.equals("m4s", true) -> ".m4s"
            ext.equals("mp4", true) -> ".mp4"
            ext.equals("aac", true) -> ".aac"
            ext.equals("key", true) -> ".key"
            ext.length in 1..5 && ext.all { it.isLetterOrDigit() } -> ".${ext.lowercase()}"
            else -> ".ts"
        }
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private const val MAX_SEGMENTS = 1500
    private const val TAG = "HlsOfflineBundler"
}
