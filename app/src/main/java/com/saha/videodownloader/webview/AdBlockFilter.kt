package com.saha.videodownloader.webview

/**
 * Pure ad/tracker URL filter — no Android framework dependencies, so it is
 * safe to unit-test on the JVM (same contract as [VideoUrlMatcher]).
 *
 * Two rule kinds, both parsed from `res/raw/adblock_hosts.txt`:
 * - **host rules** match the host itself and any subdomain of it
 * - **url rules** (`url:` prefix) are plain substrings matched against the
 *   whole lowercased URL, for ad paths served from an otherwise-legit host
 */
class AdBlockFilter(
    private val blockedHosts: Set<String>,
    private val blockedUrlKeywords: Set<String>
) {

    val ruleCount: Int get() = blockedHosts.size + blockedUrlKeywords.size

    fun blocksHost(host: String?): Boolean {
        if (host.isNullOrBlank() || blockedHosts.isEmpty()) return false
        val normalized = host.lowercase().trimEnd('.')
        if (normalized in blockedHosts) return true
        // Subdomain match: walk label boundaries instead of a plain endsWith so
        // "notdoubleclick.net" never matches the "doubleclick.net" rule.
        var index = normalized.indexOf('.')
        while (index in 0 until normalized.length - 1) {
            if (normalized.substring(index + 1) in blockedHosts) return true
            index = normalized.indexOf('.', index + 1)
        }
        return false
    }

    fun blocks(url: String): Boolean {
        if (url.isBlank()) return false
        if (!isHttpUrl(url)) return false
        if (blocksHost(hostOf(url))) return true
        if (blockedUrlKeywords.isEmpty()) return false
        val lower = url.lowercase()
        return blockedUrlKeywords.any { lower.contains(it) }
    }

    companion object {

        val EMPTY = AdBlockFilter(emptySet(), emptySet())

        private const val URL_RULE_PREFIX = "url:"

        fun parse(lines: Sequence<String>): AdBlockFilter {
            val hosts = mutableSetOf<String>()
            val keywords = mutableSetOf<String>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach

                if (line.startsWith(URL_RULE_PREFIX, ignoreCase = true)) {
                    line.substring(URL_RULE_PREFIX.length)
                        .trim()
                        .lowercase()
                        .takeIf { it.isNotEmpty() }
                        ?.let { keywords.add(it) }
                    return@forEach
                }

                // Accept hosts-file ("0.0.0.0 host") and ABP-ish ("||host^") forms.
                val token = line.split(' ', '\t').last { it.isNotBlank() }
                val host = token
                    .removePrefix("||")
                    .trimEnd('^')
                    .trim('.')
                    .lowercase()
                if (host.isNotEmpty() && host.contains('.') && !host.contains('/')) {
                    hosts.add(host)
                }
            }
            return AdBlockFilter(hosts, keywords)
        }

        fun isHttpUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)

        /**
         * Extracts the host without `android.net.Uri`, so the filter stays JVM-pure.
         * Returns `null` for anything that is not an absolute URL with an authority.
         */
        fun hostOf(url: String): String? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val start = schemeEnd + 3
            var end = url.length
            for (i in start until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') {
                    end = i
                    break
                }
            }
            var authority = url.substring(start, end)
            val at = authority.lastIndexOf('@')
            if (at >= 0) authority = authority.substring(at + 1)
            authority = if (authority.startsWith("[")) {
                // IPv6 literal — keep the brackets, drop any :port after them.
                val close = authority.indexOf(']')
                if (close > 0) authority.substring(0, close + 1) else authority
            } else {
                authority.substringBefore(':')
            }
            return authority.lowercase().trim('.').ifEmpty { null }
        }
    }
}
