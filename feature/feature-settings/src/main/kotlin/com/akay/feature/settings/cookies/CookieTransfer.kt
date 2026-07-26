package com.akay.feature.settings.cookies

import android.webkit.CookieManager

/**
 * Import/export of a site's cookies through [CookieManager], using the
 * Netscape cookies.txt format (the same format yt-dlp and curl understand).
 *
 * WebView's CookieManager only exposes name=value pairs per URL, so exported
 * entries use a far-future expiry and the host-wide domain.
 */
object CookieTransfer {

    private const val FAR_FUTURE_EPOCH = 2145916800L // 2038-01-01

    /** Normalizes user input like "example.com" into "https://example.com". */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    fun hostOf(url: String): String? =
        runCatching { java.net.URI(normalizeUrl(url)).host }.getOrNull()

    /**
     * Exports all cookies visible for [siteUrl] as a Netscape cookies.txt
     * document, or null when the site has no cookies.
     */
    fun exportCookies(siteUrl: String): String? {
        val url = normalizeUrl(siteUrl)
        val host = hostOf(url) ?: return null
        val manager = CookieManager.getInstance()

        // Collect cookies visible from the bare host and the www variant.
        val raw = buildSet {
            manager.getCookie("https://$host")?.let { addAll(it.split(";")) }
            manager.getCookie("http://$host")?.let { addAll(it.split(";")) }
            if (!host.startsWith("www.")) {
                manager.getCookie("https://www.$host")?.let { addAll(it.split(";")) }
            }
        }.map { it.trim() }.filter { it.contains("=") }.distinct()

        if (raw.isEmpty()) return null

        val domain = ".${host.removePrefix("www.")}"
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            appendLine("# Exported by AxBrowser for $host")
            raw.forEach { pair ->
                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=").trim()
                if (name.isNotEmpty()) {
                    appendLine("$domain\tTRUE\t/\tFALSE\t$FAR_FUTURE_EPOCH\t$name\t$value")
                }
            }
        }
    }

    /**
     * Imports cookies from Netscape cookies.txt content and applies them via
     * CookieManager. Returns the number of cookies set.
     */
    fun importCookies(content: String): Int {
        val manager = CookieManager.getInstance()
        var count = 0
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split("\t")
            if (parts.size >= 7) {
                val domain = parts[0].trim()
                val path = parts[2].trim().ifEmpty { "/" }
                val secure = parts[3].trim().equals("TRUE", ignoreCase = true)
                val name = parts[5].trim()
                val value = parts[6].trim()
                if (domain.isNotEmpty() && name.isNotEmpty()) {
                    val bareHost = domain.trimStart('.')
                    val cookie = buildString {
                        append("$name=$value; Domain=$domain; Path=$path")
                        if (secure) append("; Secure")
                    }
                    manager.setCookie("https://$bareHost", cookie)
                    count++
                }
            }
        }
        if (count > 0) manager.flush()
        return count
    }
}
