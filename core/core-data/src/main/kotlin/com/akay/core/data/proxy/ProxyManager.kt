package com.akay.core.data.proxy

import android.content.Context
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.domain.model.ProxyRotationMode
import com.akay.core.domain.model.ProxyServer
import com.akay.core.domain.repository.ProxyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies proxy configuration to WebView using Android's official
 * per-process proxy override (androidx.webkit ProxyController), which
 * requires WebView provider support (WebViewFeature.PROXY_OVERRIDE - true
 * on essentially every device with an up-to-date WebView). This applies
 * process-wide, not per-tab, which matches how every mobile proxy browser
 * behaves.
 *
 * Every call into ProxyController/ProxyConfig is wrapped defensively: a
 * malformed host/port/credential can throw synchronously while *building*
 * the rule (not just in the async callback), and calling ProxyController
 * before any WebView has ever been created in the process can also throw
 * on some devices. Neither should ever be able to crash the app - at worst,
 * the proxy silently fails to apply.
 */
@Singleton
class ProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AxPreferences,
    private val proxyRepository: ProxyRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val immediateExecutor = Executor { it.run() }

    private val _activeProxy = MutableStateFlow<ProxyServer?>(null)
    val activeProxy: StateFlow<ProxyServer?> = _activeProxy.asStateFlow()

    private val _isApplied = MutableStateFlow(false)
    val isApplied: StateFlow<Boolean> = _isApplied.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var rotationJob: kotlinx.coroutines.Job? = null
    private var started = false

    // ProxyController must not be touched before WebView's native library is
    // loaded, which is only guaranteed once a WebView has actually been
    // constructed. We collect preference/db changes right away (cheap, no
    // WebView involved) but hold off on actually calling ProxyController
    // until markWebViewReady() has been called at least once.
    @Volatile
    private var webViewReady = false
    private var pendingState: Triple<Boolean, List<ProxyServer>, String>? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                preferences.proxyEnabled,
                proxyRepository.observeAll(),
                preferences.proxyBypassRulesJson
            ) { enabled, proxies, bypassJson -> Triple(enabled, proxies, bypassJson) }
                .collect { state ->
                    if (!webViewReady) {
                        pendingState = state
                        return@collect
                    }
                    val (enabled, proxies, bypassJson) = state
                    applyCurrentState(enabled, proxies, bypassJson)
                    restartRotationTimerIfNeeded(enabled, proxies)
                }
        }
    }

    /** Call once a WebView instance actually exists (e.g. from the browser's WebView factory). */
    fun markWebViewReady() {
        if (webViewReady) return
        webViewReady = true
        pendingState?.let { (enabled, proxies, bypassJson) ->
            scope.launch {
                applyCurrentState(enabled, proxies, bypassJson)
                restartRotationTimerIfNeeded(enabled, proxies)
            }
        }
    }

    private fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }.getOrDefault(false)

    private suspend fun applyCurrentState(enabled: Boolean, proxies: List<ProxyServer>, bypassJson: String) {
        if (!isSupported()) {
            _isApplied.value = false
            return
        }
        val active = proxies.filter { it.enabled && it.host.isNotBlank() && it.port in 1..65535 }
        if (!enabled || active.isEmpty()) {
            clear()
            return
        }
        val index = runCatching { preferences.proxyActiveIndex.first() }.getOrDefault(0)
            .coerceIn(0, active.size - 1)
        applyProxy(active[index], bypassJson)
    }

    private fun applyProxy(proxy: ProxyServer, bypassJson: String) {
        runCatching {
            val bypassRules = runCatching {
                val arr = JSONArray(bypassJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(listOf("<local>"))

            val builder = ProxyConfig.Builder()
            builder.addProxyRule(proxy.toProxyRule())
            bypassRules.forEach { rule -> if (rule.isNotBlank()) runCatching { builder.addBypassRule(rule) } }

            ProxyController.getInstance().setProxyOverride(builder.build(), immediateExecutor) {
                _activeProxy.value = proxy
                _isApplied.value = true
                _lastError.value = null
            }
        }.onFailure { e ->
            Log.w("ProxyManager", "Failed to apply proxy ${proxy.label}", e)
            _isApplied.value = false
            _lastError.value = e.message ?: "Couldn't apply this proxy - check host/port/credentials."
        }
    }

    private fun clear() {
        if (!isSupported()) return
        runCatching {
            ProxyController.getInstance().clearProxyOverride(immediateExecutor) {
                _activeProxy.value = null
                _isApplied.value = false
            }
        }.onFailure { e -> Log.w("ProxyManager", "Failed to clear proxy override", e) }
    }

    /** Manually advances to the next enabled proxy in the rotation list. */
    fun rotateNext() {
        scope.launch {
            val active = runCatching { proxyRepository.getAllOnce() }.getOrDefault(emptyList()).filter { it.enabled }
            if (active.isEmpty()) return@launch
            val current = runCatching { preferences.proxyActiveIndex.first() }.getOrDefault(0)
            val next = (current + 1) % active.size
            runCatching { preferences.setProxyActiveIndex(next) }
        }
    }

    /** Called on every navigation when rotation mode is PER_NAVIGATION. */
    fun onNavigation() {
        scope.launch {
            val mode = runCatching { preferences.proxyRotationMode.first() }.getOrDefault(ProxyRotationMode.MANUAL.name)
            if (mode == ProxyRotationMode.PER_NAVIGATION.name) rotateNext()
        }
    }

    private var lastRotationSignature: String? = null

    private fun restartRotationTimerIfNeeded(enabled: Boolean, proxies: List<ProxyServer>) {
        scope.launch {
            val mode = runCatching { preferences.proxyRotationMode.first() }.getOrDefault(ProxyRotationMode.MANUAL.name)
            val signature = "$enabled|${proxies.size}|$mode"
            if (signature == lastRotationSignature) return@launch
            lastRotationSignature = signature

            rotationJob?.cancel()
            if (!enabled || mode != ProxyRotationMode.TIMED.name || proxies.none { it.enabled }) return@launch

            rotationJob = scope.launch {
                while (true) {
                    val minutes = runCatching { preferences.proxyRotationIntervalMinutes.first() }.getOrDefault(10).coerceAtLeast(1)
                    kotlinx.coroutines.delay(minutes * 60_000L)
                    rotateNext()
                }
            }
        }
    }
}
