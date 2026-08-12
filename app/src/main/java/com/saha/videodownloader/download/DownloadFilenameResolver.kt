package com.saha.videodownloader.download

import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * Builds a safe download filename from HTTP `Content-Disposition`, page title, or URL.
 * Stem length is capped so notifications / MediaStore stay readable.
 */
object DownloadFilenameResolver {

    /** Max characters for the name without extension. */
    const val MAX_STEM_LENGTH = 80

    /**
     * Fast path — no network. Prefers [pageTitle], then URL path basename, then host.
     */
    fun fromHints(
        mediaUrl: String,
        pageTitle: String?,
        defaultExt: String = ".mp4"
    ): String {
        val ext = normalizeExt(defaultExt)
        fromPageTitle(pageTitle, ext)?.let { return it }
        fromUrlPath(mediaUrl, ext)?.let { return it }
        return fallback(mediaUrl, ext)
    }

    /**
     * Prefer `Content-Disposition` from a short HEAD/Range probe, then hints.
     * Call off the main thread.
     */
    fun resolve(
        mediaUrl: String,
        pageTitle: String?,
        pageUrl: String?,
        userAgent: String,
        defaultExt: String = ".mp4",
        probeNetwork: Boolean = true
    ): String {
        val ext = normalizeExt(defaultExt)
        if (probeNetwork) {
            runCatching {
                probeContentDisposition(mediaUrl, pageUrl, userAgent)
            }.getOrNull()?.let { raw ->
                sanitizeFilename(raw, ext)?.let { return it }
            }
        }
        return fromHints(mediaUrl, pageTitle, ext)
    }

    fun sanitizeFilename(raw: String, defaultExt: String = ".mp4"): String? {
        val ext = normalizeExt(defaultExt)
        var name = raw.trim().trim('"', '\'')
        if (name.isEmpty()) return null

        // Drop any path components a malicious header might include.
        name = name.substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (name.isEmpty() || name == "." || name == "..") return null

        val existingExt = extensionOf(name)
        val stemRaw = if (existingExt != null) {
            name.dropLast(existingExt.length)
        } else {
            name
        }
        val outExt = existingExt ?: ext

        var stem = stemRaw
            .replace(Regex("""[\u0000-\u001F\u007F]"""), "")
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.', ' ')

        if (stem.isEmpty()) return null
        // Avoid reserved DOS device names on some MediaStore paths.
        if (stem.matches(Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"""))) {
            stem = "video_$stem"
        }
        stem = truncateStem(stem, MAX_STEM_LENGTH)
        return "$stem$outExt"
    }

    private fun fromPageTitle(pageTitle: String?, ext: String): String? {
        val title = pageTitle?.trim().orEmpty()
        if (title.isEmpty()) return null
        // Skip generic browser placeholders.
        if (title.equals("about:blank", true) || title == "null") return null
        return sanitizeFilename(title, ext)
    }

    private fun fromUrlPath(mediaUrl: String, ext: String): String? {
        val path = try {
            URI(mediaUrl).path
        } catch (_: Exception) {
            null
        } ?: return null
        val last = path.substringAfterLast('/').substringBefore('?')
        if (last.isBlank()) return null
        // Ignore playlist / opaque segment-like names.
        val lower = last.lowercase()
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return null
        if (lower.matches(Regex("""p\d{5}\.(ts|m4s|jpg|bin)"""))) return null
        return sanitizeFilename(last, ext)
    }

    private fun fallback(mediaUrl: String, ext: String): String {
        val host = try {
            URI(mediaUrl).host?.replace('.', '_') ?: "video"
        } catch (_: Exception) {
            "video"
        }
        val stem = truncateStem(
            sanitizeFilename(host, ext)?.removeSuffix(ext) ?: "video",
            40
        )
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    private fun probeContentDisposition(
        mediaUrl: String,
        pageUrl: String?,
        userAgent: String
    ): String? {
        val headers = CapturedMediaHeaders.mergeFor(mediaUrl, pageUrl, userAgent)
        headDisposition(mediaUrl, headers)?.let { return it }
        return rangeDisposition(mediaUrl, headers)
    }

    private fun headDisposition(url: String, headers: Map<String, String>): String? =
        readDisposition(url, headers, method = "HEAD", withRange = false)

    private fun rangeDisposition(url: String, headers: Map<String, String>): String? =
        readDisposition(url, headers, method = "GET", withRange = true)

    private fun readDisposition(
        url: String,
        headers: Map<String, String>,
        method: String,
        withRange: Boolean
    ): String? {
        var current = url
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 4_000
                readTimeout = 4_000
                requestMethod = method
                useCaches = false
                setRequestProperty("Connection", "close")
                if (withRange) setRequestProperty("Range", "bytes=0-0")
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
                val header = conn.getHeaderField("Content-Disposition")
                    ?: conn.headerFields?.entries
                        ?.firstOrNull { it.key.equals("Content-Disposition", true) }
                        ?.value
                        ?.firstOrNull()
                return parseContentDisposition(header)
            } catch (t: Throwable) {
                Log.d(TAG, "$method Content-Disposition probe failed: ${t.message}")
                return null
            } finally {
                if (withRange) {
                    runCatching { conn.inputStream.use { it.readBytes() } }
                }
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Parses `filename` / `filename*` from a Content-Disposition header value.
     */
    fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null

        // RFC 5987: filename*=UTF-8''percent-encoded
        val star = Regex(
            """filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(header)
        if (star != null) {
            val encoded = star.groupValues[1].trim().trim('"', '\'')
            val decoded = runCatching {
                URLDecoder.decode(encoded, Charsets.UTF_8.name())
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded.trim()
        }

        val quoted = Regex(
            """filename\s*=\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(header)
        if (quoted != null) {
            return quoted.groupValues[1].trim()
        }

        val plain = Regex(
            """filename\s*=\s*([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(header)
        if (plain != null) {
            return plain.groupValues[1].trim().trim('"', '\'')
        }
        return null
    }

    private fun truncateStem(stem: String, max: Int): String {
        if (stem.length <= max) return stem
        // Prefer cutting at a word boundary near the limit.
        val slice = stem.take(max)
        val space = slice.lastIndexOf(' ')
        val cut = if (space >= max * 2 / 3) slice.take(space) else slice
        return cut.trimEnd(' ', '.', '_', '-')
    }

    private fun extensionOf(name: String): String? {
        val idx = name.lastIndexOf('.')
        if (idx <= 0 || idx == name.lastIndex) return null
        val ext = name.substring(idx)
        if (ext.length !in 2..6) return null
        if (!ext.drop(1).all { it.isLetterOrDigit() }) return null
        return ext.lowercase()
    }

    private fun normalizeExt(ext: String): String {
        val e = ext.trim().lowercase()
        return if (e.startsWith(".")) e else ".$e"
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    private const val TAG = "DownloadFilename"
}
