package com.akay.feature.browser.proxy

import android.content.Context
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

    private var rotationJob: kotlinx.coroutines.Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                preferences.proxyEnabled,
                proxyRepository.observeAll(),
                preferences.proxyBypassRulesJson
            ) { enabled, proxies, bypassJson -> Triple(enabled, proxies, bypassJson) }
                .collect { (enabled, proxies, bypassJson) ->
                    applyCurrentState(enabled, proxies, bypassJson)
                    restartRotationTimerIfNeeded(enabled, proxies)
                }
        }
    }

    private suspend fun applyCurrentState(enabled: Boolean, proxies: List<ProxyServer>, bypassJson: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            _isApplied.value = false
            return
        }
        val active = proxies.filter { it.enabled }
        if (!enabled || active.isEmpty()) {
            clear()
            return
        }
        val index = preferences.proxyActiveIndex.first().coerceIn(0, active.size - 1)
        val proxy = active[index]
        applyProxy(proxy, bypassJson)
    }

    private fun applyProxy(proxy: ProxyServer, bypassJson: String) {
        val bypassRules = runCatching {
            val arr = JSONArray(bypassJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(listOf("<local>"))

        val builder = ProxyConfig.Builder()
            .addProxyRule(proxy.toProxyRule())
        bypassRules.forEach { rule -> if (rule.isNotBlank()) builder.addBypassRule(rule) }

        runCatching {
            ProxyController.getInstance().setProxyOverride(builder.build(), immediateExecutor) {
                _activeProxy.value = proxy
                _isApplied.value = true
            }
        }
    }

    private fun clear() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            ProxyController.getInstance().clearProxyOverride(immediateExecutor) {
                _activeProxy.value = null
                _isApplied.value = false
            }
        }
    }

    /** Manually advances to the next enabled proxy in the rotation list. */
    fun rotateNext() {
        scope.launch {
            val active = proxyRepository.getAllOnce().filter { it.enabled }
            if (active.isEmpty()) return@launch
            val current = preferences.proxyActiveIndex.first()
            val next = (current + 1) % active.size
            preferences.setProxyActiveIndex(next)
        }
    }

    /** Called on every navigation when rotation mode is PER_NAVIGATION. */
    fun onNavigation() {
        scope.launch {
            val mode = preferences.proxyRotationMode.first()
            if (mode == ProxyRotationMode.PER_NAVIGATION.name) rotateNext()
        }
    }

    private var lastRotationSignature: String? = null

    private fun restartRotationTimerIfNeeded(enabled: Boolean, proxies: List<ProxyServer>) {
        scope.launch {
            val mode = preferences.proxyRotationMode.first()
            val signature = "$enabled|${proxies.size}|$mode"
            if (signature == lastRotationSignature) return@launch
            lastRotationSignature = signature

            rotationJob?.cancel()
            if (!enabled || mode != ProxyRotationMode.TIMED.name || proxies.none { it.enabled }) return@launch

            rotationJob = scope.launch {
                while (true) {
                    val minutes = preferences.proxyRotationIntervalMinutes.first().coerceAtLeast(1)
                    kotlinx.coroutines.delay(minutes * 60_000L)
                    rotateNext()
                }
            }
        }
    }
}
