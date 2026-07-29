package com.akay.feature.browser.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Host-based ad/tracker blocker in the spirit of Brave shields / uBlock's
 * network filtering. Blocks any request whose host matches (or is a subdomain
 * of) an entry in the bundled filter list, plus a few URL-path heuristics for
 * common ad endpoints.
 */
object AdBlockEngine {

    @Volatile
    private var bundledHosts: Set<String> = emptySet()

    @Volatile
    private var customHosts: Set<String> = emptySet()

    @Volatile
    private var allowlistedOrigins: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val urlPatterns = listOf(
        "/pagead/",
        "/adsbygoogle",
        "/ad_status",
        "/adserver/",
        "/adframe/",
        "/adrequest",
        "/prebid",
        "/openrtb"
    )

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            val hosts = runCatching {
                context.assets.open("adblock/adservers.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            }.getOrDefault(hashSetOf())
            bundledHosts = hosts
            loaded = true
        }
    }

    /** Called with the merged host set from all active filter-list subscriptions. */
    fun setCustomHosts(hosts: Set<String>) {
        customHosts = hosts
    }

    /** Origins (scheme://host) where the user has explicitly disabled ad-blocking. */
    fun setAllowlistedOrigins(origins: Set<String>) {
        allowlistedOrigins = origins
    }

    fun isOriginAllowlisted(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        return allowlistedOrigins.any { origin.endsWith(it, ignoreCase = true) }
    }

    fun shouldBlock(url: String, pageOrigin: String? = null): Boolean {
        if (isOriginAllowlisted(pageOrigin)) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false

        var candidate = host
        while (true) {
            if (candidate in bundledHosts || candidate in customHosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(dot + 1)
            if (!candidate.contains('.')) break
        }

        val lower = url.lowercase()
        return urlPatterns.any { lower.contains(it) }
    }

    fun onBlocked() {
        _blockedCount.value = _blockedCount.value + 1
    }

    fun resetCounter() {
        _blockedCount.value = 0
    }
}
