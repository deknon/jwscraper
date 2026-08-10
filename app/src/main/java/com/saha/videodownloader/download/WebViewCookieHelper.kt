package com.saha.videodownloader.download

import android.webkit.CookieManager
import android.webkit.WebView
import java.net.URI

/**
 * Collects cookies / Referer / Origin the way a browser would when fetching
 * an HLS playlist from a page (needed for CDN 403 Forbidden responses).
 */
object WebViewCookieHelper {

    fun enableFor(webView: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * Merge cookies from the media URL, the page URL, and their origins.
     * CDN hosts often only receive cookies when queried by their own URL.
     */
    fun collectCookieHeader(mediaUrl: String, pageUrl: String?): String? {
        flush()
        val cm = CookieManager.getInstance()
        val byName = linkedMapOf<String, String>()

        fun ingest(raw: String?) {
            if (raw.isNullOrBlank()) return
            raw.split(';').forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@forEach
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@forEach
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    byName[name] = value
                }
            }
        }

        val candidates = buildList {
            add(mediaUrl)
            originOf(mediaUrl)?.let { add(it) }
            hostRootOf(mediaUrl)?.let { add(it) }
            if (!pageUrl.isNullOrBlank()) {
                add(pageUrl)
                originOf(pageUrl)?.let { add(it) }
                hostRootOf(pageUrl)?.let { add(it) }
            }
        }.distinct()

        candidates.forEach { url ->
            ingest(runCatching { cm.getCookie(url) }.getOrNull())
        }

        if (byName.isEmpty()) return null
        return byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun resolveReferer(mediaUrl: String, pageUrl: String?): String {
        if (!pageUrl.isNullOrBlank() &&
            (pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) &&
            pageUrl != "about:blank"
        ) {
            return pageUrl
        }
        return originOf(mediaUrl) ?: mediaUrl
    }

    fun resolveOrigin(pageUrl: String?, mediaUrl: String): String? =
        originOf(pageUrl) ?: originOf(mediaUrl)

    fun buildFfmpegHeaders(
        mediaUrl: String,
        pageUrl: String?,
        userAgent: String
    ): String {
        val referer = resolveReferer(mediaUrl, pageUrl)
        val origin = resolveOrigin(pageUrl, mediaUrl)
        val cookie = collectCookieHeader(mediaUrl, pageUrl)

        val lines = buildList {
            add("User-Agent: $userAgent")
            add("Accept: */*")
            add("Accept-Language: en-US,en;q=0.9,th;q=0.8")
            add("Referer: $referer")
            if (!origin.isNullOrBlank()) {
                add("Origin: $origin")
            }
            // Some CDNs expect this for media from a document context.
            add("Sec-Fetch-Dest: empty")
            add("Sec-Fetch-Mode: cors")
            add("Sec-Fetch-Site: cross-site")
            if (!cookie.isNullOrBlank()) {
                add("Cookie: $cookie")
            }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            "$scheme://$host"
        } catch (_: Exception) {
            null
        }
    }

    private fun hostRootOf(url: String?): String? {
        val origin = originOf(url) ?: return null
        return "$origin/"
    }
}
