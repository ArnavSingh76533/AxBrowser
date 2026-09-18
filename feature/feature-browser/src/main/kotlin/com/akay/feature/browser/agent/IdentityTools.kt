package com.akay.feature.browser.agent

import com.akay.core.data.datastore.AxPreferences
import com.akay.core.data.proxy.ProxyManager
import com.akay.core.domain.model.ProxyRotationMode
import com.akay.core.domain.model.ProxyType
import com.akay.core.domain.repository.ProxyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's hands on its own network identity: which proxy/IP the browser leaves through, and
 * which device fingerprint the page is served.
 *
 * Both engines already existed in the app (`ProxyManager`, `FingerprintSpoofing`) but the agent had
 * no way to reach either, so every run was stuck on one IP and one fingerprint - the two things that
 * get an automation rate-limited, blocked, or served a different page than a human would get.
 *
 * This class only orchestrates and reports. It never touches WebView: it writes preferences/the
 * proxy list and lets `ProxyManager`'s own collector apply the change to the live session, exactly
 * as the Settings UI does.
 */
@Singleton
class IdentityTools @Inject constructor(
    private val preferences: AxPreferences,
    private val proxyRepository: ProxyRepository,
    private val proxyManager: ProxyManager
) {

    // ---------- status ----------

    /** A single readable report of the live egress identity, for the agent to check before it
     *  blames a site for blocking it. */
    suspend fun status(): String {
        val sb = StringBuilder()

        val enabled = runCatching { preferences.proxyEnabled.first() }.getOrDefault(false)
        val all = runCatching { proxyRepository.getAllOnce() }.getOrDefault(emptyList())
        val usable = all.filter { it.enabled && it.host.isNotBlank() && it.port in 1..65535 }
        val active = proxyManager.activeProxy.value
        val mode = runCatching { preferences.proxyRotationMode.first() }.getOrDefault(ProxyRotationMode.MANUAL.name)
        val interval = runCatching { preferences.proxyRotationIntervalMinutes.first() }.getOrDefault(10)

        sb.append("PROXY\n")
        sb.append("- proxy setting: ${if (enabled) "ON" else "OFF"}\n")
        sb.append("- configured proxies: ${all.size} (${usable.size} usable/enabled)\n")
        when {
            active != null ->
                sb.append("- traffic exits through: ${active.label} [${active.type.name}] ${active.host}:${active.port}${if (active.requiresAuth) " (authenticated)" else ""}\n")
            enabled && usable.isEmpty() ->
                sb.append("- traffic exits through: your real IP - the proxy setting is ON but every entry is disabled or incomplete\n")
            else ->
                sb.append("- traffic exits through: your real IP (no proxy applied)\n")
        }
        if (proxyManager.lastError.value != null) sb.append("- last proxy error: ${proxyManager.lastError.value}\n")
        val modeNote = when (mode) {
            ProxyRotationMode.PER_NAVIGATION.name -> "a new proxy on every navigation"
            ProxyRotationMode.TIMED.name -> "a new proxy every $interval minute(s)"
            else -> "manual - only rotate_proxy moves it"
        }
        sb.append("- rotation: $mode ($modeNote)\n")

        val fpEnabled = runCatching { preferences.fingerprintProtectionEnabled.first() }.getOrDefault(false)
        val seed = runCatching { getOrCreateSeed() }.getOrDefault("")
        sb.append("\nFINGERPRINT\n")
        if (!fpEnabled) {
            sb.append("- protection OFF: the page sees this device's real canvas/WebGL/hardware values\n")
        } else {
            val canvas = runCatching { preferences.fingerprintSpoofCanvas.first() }.getOrDefault(true)
            val webgl = runCatching { preferences.fingerprintSpoofWebGl.first() }.getOrDefault(true)
            val hardware = runCatching { preferences.fingerprintSpoofHardware.first() }.getOrDefault(true)
            val on = listOfNotNull(
                "canvas".takeIf { canvas },
                "WebGL".takeIf { webgl },
                "hardware".takeIf { hardware }
            ).ifEmpty { listOf("nothing - all three spoofing toggles are off") }
            sb.append("- protection ON, spoofing ${on.joinToString(", ")}\n")
            sb.append("- device profile: ${describeSeed(seed)}\n")
        }
        sb.append("\nBoth take effect on the next page load - call navigate/reload after changing them.")
        return sb.toString()
    }

    // ---------- proxy ----------

    /**
     * Adds a proxy and makes it the one in use. An already-present host:port of the same type is
     * updated rather than duplicated, so re-running this with different credentials is safe.
     */
    suspend fun setProxy(
        label: String,
        type: String,
        host: String,
        port: Int,
        username: String?,
        password: String?
    ): String {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks4://")
            .removePrefix("socks5://").trimEnd('/')
        if (cleanHost.isBlank()) return "A proxy needs a host. Expected keys: host, port, type (HTTP|HTTPS|SOCKS4|SOCKS5), optional label/username/password."
        if (port !in 1..65535) return "Port $port is not a valid TCP port (1-65535)."

        val proxyType = parseType(type)
        val existing = runCatching { proxyRepository.getAllOnce() }.getOrDefault(emptyList())
            .firstOrNull { it.host.equals(cleanHost, ignoreCase = true) && it.port == port && it.type == proxyType }

        val name = label.trim().ifBlank { "$cleanHost:$port" }

        val saved = runCatching {
            proxyRepository.save(
                id = existing?.id,
                label = name,
                type = proxyType,
                host = cleanHost,
                port = port,
                username = username?.trim()?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                enabled = true
            )
        }
        if (saved.isFailure) {
            return "Couldn't save the proxy: ${saved.exceptionOrNull()?.message ?: "storage error"}"
        }

        runCatching { preferences.setProxyEnabled(true) }

        // Point the active index at the entry we just wrote, using the same ordering ProxyManager
        // filters with (only enabled, complete entries count when it picks an index).
        val usable = runCatching { proxyRepository.getAllOnce() }.getOrDefault(emptyList())
            .filter { it.enabled && it.host.isNotBlank() && it.port in 1..65535 }
        val index = usable.indexOfLast { it.host.equals(cleanHost, ignoreCase = true) && it.port == port && it.type == proxyType }
        if (index >= 0) runCatching { preferences.setProxyActiveIndex(index) }

        delay(400) // let ProxyManager's collector apply it to the live session before reporting
        val active = proxyManager.activeProxy.value
        val applied = active != null && active.host.equals(cleanHost, ignoreCase = true) && active.port == port
        val failure = proxyManager.lastError.value
        return buildString {
            append("Proxy ${if (existing != null) "updated" else "added"}: $name [$proxyType] $cleanHost:$port")
            if (!username.isNullOrBlank()) append(" (with credentials)")
            append(".\n")
            when {
                applied -> append("Traffic now exits through it (${usable.size} usable prox${if (usable.size == 1) "y" else "ies"} in the list).")
                failure != null -> append("Saved and selected, but WebView refused it: $failure")
                else -> append("Saved, enabled and selected - WebView may need a page load to move traffic onto it. Verify with proxy_status.")
            }
        }
    }

    /** Moves to the next enabled proxy in the list. */
    suspend fun rotate(): String {
        val usable = runCatching { proxyRepository.getAllOnce() }.getOrDefault(emptyList()).filter { it.enabled }
        if (usable.isEmpty()) return "No enabled proxies to rotate through - add one with set_proxy first (or configure them in Settings > Proxy)."
        if (usable.size == 1) {
            return "Only one enabled proxy (${usable[0].label} ${usable[0].host}:${usable[0].port}) - nothing to rotate to. Add a second one for rotation to change the exit IP."
        }
        val before = proxyManager.activeProxy.value
        proxyManager.rotateNext()
        delay(700)
        val after = proxyManager.activeProxy.value
        return when {
            after == null -> "Requested a rotation from ${usable.size} enabled proxies, but no proxy is currently applied. Turn the proxy setting on (set_proxy does that) - or check proxy_status for the last error."
            before != null && after.host == before.host && after.port == before.port ->
                "Requested a rotation but traffic is still leaving through ${after.host}:${after.port}. That means every other entry is disabled or was rejected - proxy_status lists what's usable."
            else ->
                "Rotated: now exiting through ${after.label} [${after.type.name}] ${after.host}:${after.port} (was ${before?.host ?: "your real IP"}:${before?.port ?: "-"})."
        }
    }

    /** MANUAL | PER_NAVIGATION | TIMED, with the interval for TIMED. */
    suspend fun setRotation(mode: String, intervalMinutes: Int): String {
        val wanted = when (mode.trim().uppercase()) {
            "MANUAL", "OFF", "NONE" -> ProxyRotationMode.MANUAL
            "PER_NAVIGATION", "PER-NAVIGATION", "NAVIGATION", "PER_PAGE" -> ProxyRotationMode.PER_NAVIGATION
            "TIMED", "TIME", "INTERVAL", "EVERY" -> ProxyRotationMode.TIMED
            else -> return "Unknown rotation mode \"$mode\". Use MANUAL, PER_NAVIGATION or TIMED."
        }
        runCatching { preferences.setProxyRotationMode(wanted.name) }
        val minutes = intervalMinutes.coerceIn(1, 1440)
        if (wanted == ProxyRotationMode.TIMED) runCatching { preferences.setProxyRotationIntervalMinutes(minutes) }
        return when (wanted) {
            ProxyRotationMode.MANUAL -> "Proxy rotation set to MANUAL - the exit IP only changes when you call rotate_proxy."
            ProxyRotationMode.PER_NAVIGATION -> "Proxy rotation set to PER_NAVIGATION - every navigation moves to the next enabled proxy. Good for spread-out crawling, bad for a multi-step logged-in flow (the session will look like it jumps IPs mid-login)."
            else -> "Proxy rotation set to TIMED - a new enabled proxy every $minutes minute(s)."
        }
    }

    /** Stops using a proxy without deleting the saved list. */
    suspend fun clear(): String {
        val wasActive = proxyManager.activeProxy.value
        runCatching { preferences.setProxyEnabled(false) }
        delay(400)
        val stillActive = proxyManager.activeProxy.value
        return if (stillActive != null) {
            "Asked WebView to stop proxying, but traffic is still going through ${stillActive.label} ${stillActive.host}:${stillActive.port}. Try clearing it again, or check proxy_status."
        } else {
            "Proxy off - traffic exits through your real IP. The saved list is untouched (${wasActive?.let { "was ${it.label}" } ?: "nothing was active anyway"})."
        }
    }

    // ---------- fingerprint ----------

    /**
     * Configures the device fingerprint the page is served. Only the arguments that are non-null are
     * changed; [regenerate] mints a brand-new device profile (a different canvas/WebGL/hardware
     * combination), which is what you want when a site has already fingerprinted this session.
     */
    suspend fun configureFingerprint(
        regenerate: Boolean,
        enabled: Boolean?,
        canvas: Boolean?,
        webgl: Boolean?,
        hardware: Boolean?
    ): String {
        val changes = mutableListOf<String>()
        if (enabled != null) {
            runCatching { preferences.setFingerprintProtectionEnabled(enabled) }
            changes += "protection ${if (enabled) "ON" else "OFF"}"
        }
        if (canvas != null) {
            runCatching { preferences.setFingerprintSpoofCanvas(canvas) }
            changes += "canvas spoofing ${if (canvas) "ON" else "OFF"}"
        }
        if (webgl != null) {
            runCatching { preferences.setFingerprintSpoofWebGl(webgl) }
            changes += "WebGL spoofing ${if (webgl) "ON" else "OFF"}"
        }
        if (hardware != null) {
            runCatching { preferences.setFingerprintSpoofHardware(hardware) }
            changes += "hardware spoofing ${if (hardware) "ON" else "OFF"}"
        }

        val seed = if (regenerate) runCatching { preferences.regenerateFingerprintSeed() }.getOrNull() else null
        if (regenerate) {
            // Turning protection on is implied: a new profile is pointless if nothing applies it.
            if (enabled == null && !runCatching { preferences.fingerprintProtectionEnabled.first() }.getOrDefault(false)) {
                runCatching { preferences.setFingerprintProtectionEnabled(true) }
                changes += "protection ON (implied by the new profile)"
            }
            changes += "new device profile"
        }

        if (changes.isEmpty()) return "Nothing to change - pass regenerate, enabled, canvas, webgl and/or hardware."

        val nowEnabled = runCatching { preferences.fingerprintProtectionEnabled.first() }.getOrDefault(false)
        val currentSeed = runCatching { getOrCreateSeed() }.getOrDefault("")
        return buildString {
            append("Fingerprint updated: ${changes.joinToString(", ")}.\n")
            if (!nowEnabled) {
                append("Protection is off, so the page still sees this device's real values - pass enabled=true (and keep at least one spoofing toggle on) to actually change what it sees.")
            } else {
                append("Device profile now ${describeSeed(currentSeed)}")
                if (seed != null) append(" - a canvas/WebGL/hardware combination this browser has never presented before")
                append(".\nReload the page (navigate or run_js location.reload()) for it to apply - the script is injected at document start, so the current page keeps the old identity until it reloads.")
            }
        }
    }

    // ---------- internals ----------

    private suspend fun getOrCreateSeed(): String = preferences.getOrCreateFingerprintSeed()

    /** A short, non-reversible label for the seed - the seed itself is this device's identity and
     *  does not belong in a chat transcript or a bug report. */
    private fun describeSeed(seed: String): String {
        if (seed.isBlank()) return "unset"
        val fingerprint = ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 100_000L).toString().padStart(5, '0')
        return "profile #$fingerprint (from seed ${seed.take(8)}...)"
    }

    private fun parseType(raw: String): ProxyType = when (raw.trim().uppercase().replace(".", "")) {
        "HTTPS", "SSL", "TLS" -> ProxyType.HTTPS
        "SOCKS4", "SOCKS", "SOCKS4A" -> ProxyType.SOCKS4
        "SOCKS5", "SOCKS5H" -> ProxyType.SOCKS5
        else -> ProxyType.HTTP
    }
}
