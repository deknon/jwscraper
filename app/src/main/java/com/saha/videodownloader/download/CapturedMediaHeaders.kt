package com.saha.videodownloader.download

import com.saha.videodownloader.webview.VideoUrlMatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers request headers the WebView used when fetching media/HLS URLs.
 * CDN tokens often appear as Cookie / Authorization / custom X-* headers.
 */
object CapturedMediaHeaders {

    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    var lastMediaUrl: String? = null
        private set

    fun capture(url: String, requestHeaders: Map<String, String>?) {
        if (requestHeaders.isNullOrEmpty()) return
        if (VideoUrlMatcher.matchVideoUrl(url) == null &&
            !url.contains(".m3u8", ignoreCase = true) &&
            !url.contains(".ts", ignoreCase = true) &&
            !url.contains(".m4s", ignoreCase = true) &&
            !url.contains("googlevideo", ignoreCase = true)
        ) {
            return
        }
        val host = hostOf(url) ?: return
        val useful = requestHeaders
            .filterKeys { key ->
                val k = key.lowercase()
                k == "cookie" ||
                    k == "authorization" ||
                    k == "referer" ||
                    k == "origin" ||
                    k == "user-agent" ||
                    k.startsWith("x-")
            }
            .mapKeys { it.key }
        if (useful.isEmpty()) return
        byHost[host] = useful
        lastMediaUrl = url
    }

    fun mergeFor(
        mediaUrl: String,
        pageUrl: String?,
        userAgent: String
    ): Map<String, String> {
        val base = LinkedHashMap<String, String>()
        base["User-Agent"] = userAgent
        base["Accept"] = "*/*"
        base["Accept-Language"] = "en-US,en;q=0.9,th;q=0.8"
        base["Referer"] = WebViewCookieHelper.resolveReferer(mediaUrl, pageUrl)
        WebViewCookieHelper.resolveOrigin(pageUrl, mediaUrl)?.let { base["Origin"] = it }

        val cookie = WebViewCookieHelper.collectCookieHeader(mediaUrl, pageUrl)
        if (!cookie.isNullOrBlank()) {
            base["Cookie"] = cookie
        }

        // Overlay headers the player actually used for this host / related hosts.
        hostOf(mediaUrl)?.let { host ->
            byHost[host]?.forEach { (k, v) -> base[canonical(k)] = v }
        }
        hostOf(pageUrl)?.let { host ->
            byHost[host]?.forEach { (k, v) ->
                // Don't overwrite a fresher media Cookie with page cookies unless missing.
                val key = canonical(k)
                if (key == "Cookie" && base.containsKey("Cookie")) return@forEach
                base.putIfAbsent(key, v)
            }
        }
        // Latest capture wins for Authorization-like headers.
        byHost.values.forEach { map ->
            map.forEach { (k, v) ->
                val key = canonical(k)
                if (key == "Authorization" || key.startsWith("X-")) {
                    base[key] = v
                }
            }
        }
        return base
    }

    fun toFfmpegHeaderBlock(headers: Map<String, String>): String =
        headers.entries.joinToString("\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" }

    private fun canonical(name: String): String =
        when (name.lowercase()) {
            "user-agent" -> "User-Agent"
            "referer" -> "Referer"
            "origin" -> "Origin"
            "cookie" -> "Cookie"
            "authorization" -> "Authorization"
            "accept" -> "Accept"
            "accept-language" -> "Accept-Language"
            else -> name
        }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
