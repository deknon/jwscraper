package com.saha.videodownloader.viewmodel

import java.net.URI

/**
 * Canonical page identity used to tell "found on this page" from
 * "found on an earlier page". Pure — unit-testable on the JVM.
 */
object PageKeys {

    /**
     * Normalizes scheme/host/port/path/query and drops the fragment, so
     * `a.com/x#top` and `a.com/x` are the same page but `a.com/x?id=2` is not.
     * Returns `null` for blank input and `about:blank`.
     */
    fun pageKey(url: String?): String? {
        if (url.isNullOrBlank() || url == "about:blank") return null
        return try {
            val uri = URI(url)
            buildString {
                append(uri.scheme?.lowercase() ?: "https")
                append("://")
                append(uri.host?.lowercase().orEmpty())
                if (uri.port > 0) append(":").append(uri.port)
                append(uri.path.orEmpty().ifEmpty { "/" })
                if (!uri.query.isNullOrBlank()) append("?").append(uri.query)
            }
        } catch (_: Exception) {
            url.substringBefore('#').trimEnd('/')
        }
    }

    fun samePage(a: String?, b: String?): Boolean {
        val keyA = pageKey(a) ?: return false
        val keyB = pageKey(b) ?: return false
        return keyA == keyB
    }
}
