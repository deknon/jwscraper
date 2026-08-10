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
            val provisional = File(
                segDir,
                "p${urlToLocal.size.toString().padStart(5, '0')}.bin"
            )
            downloadToFile(url, headers, provisional)
            // CDNs often disguise MPEG-TS/fMP4 as .jpg/.txt — rename by magic bytes
            // so FFmpeg's HLS demuxer accepts the local files.
            val local = renameByContent(provisional, urlToLocal.size, url)
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

    /**
     * Pick a media-friendly extension from file magic bytes (and URL as fallback).
     * Keeps AES keys as `.key`; maps obfuscated `.jpg`/`.txt` segments to `.ts`/`.m4s`.
     */
    private fun renameByContent(file: File, index: Int, sourceUrl: String): File {
        val ext = sniffExt(file) ?: allowedUrlExt(sourceUrl) ?: ".ts"
        val target = File(file.parentFile, "p${index.toString().padStart(5, '0')}$ext")
        if (file.absolutePath == target.absolutePath) return file
        if (target.exists()) target.delete()
        return if (file.renameTo(target)) {
            target
        } else {
            file.copyTo(target, overwrite = true)
            file.delete()
            target
        }
    }

    private fun sniffExt(file: File): String? {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) return null

        // MPEG-TS sync byte 0x47
        if (header[0] == 0x47.toByte()) return ".ts"

        // ISO BMFF / fMP4: ....ftyp / styp / moof / mdat / sidx
        if (read >= 8) {
            val box = String(header, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp" || box == "styp" || box == "moof" || box == "mdat" || box == "sidx") {
                return ".m4s"
            }
        }

        // ADTS AAC
        if (read >= 2) {
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xF0) == 0xF0) return ".aac"
        }

        // ID3-tagged audio
        if (read >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        ) {
            return ".aac"
        }

        // Raw AES key blobs are typically 16 bytes with no media signature.
        if (file.length() in 16L..32L) return ".key"

        return null
    }

    /** Only keep URL extensions that FFmpeg already allows for HLS segments. */
    private fun allowedUrlExt(url: String): String? {
        val path = try {
            URI(url).path ?: url
        } catch (_: Exception) {
            url
        }
        val ext = path.substringAfterLast('/')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        return when (ext) {
            "ts", "m2ts", "mts", "mpg", "mpeg", "mpegts",
            "m4s", "mp4", "m4a", "m4v", "aac", "cmfv", "cmfa", "fmp4", "key" -> ".$ext"
            else -> null
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
