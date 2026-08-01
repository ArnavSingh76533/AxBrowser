package com.akay.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.domain.model.ProxyRotationMode
import com.akay.core.domain.model.ProxyServer
import com.akay.core.domain.model.ProxyType
import com.akay.core.domain.repository.ProxyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

data class ProxySettingsUiState(
    val proxies: List<ProxyServer> = emptyList(),
    val enabled: Boolean = false,
    val rotationMode: String = ProxyRotationMode.MANUAL.name,
    val rotationIntervalMinutes: Int = 10,
    val bypassRules: List<String> = listOf("<local>"),
    val activeIndex: Int = 0
)

@HiltViewModel
class ProxySettingsViewModel @Inject constructor(
    private val proxyRepository: ProxyRepository,
    private val preferences: AxPreferences
) : ViewModel() {

    val uiState: StateFlow<ProxySettingsUiState> = combine(
        proxyRepository.observeAll(),
        preferences.proxyEnabled,
        preferences.proxyRotationMode,
        preferences.proxyRotationIntervalMinutes,
        preferences.proxyBypassRulesJson,
        preferences.proxyActiveIndex
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val proxies = values[0] as List<ProxyServer>
        val enabled = values[1] as Boolean
        val rotationMode = values[2] as String
        val intervalMin = values[3] as Int
        val bypassJson = values[4] as String
        val activeIndex = values[5] as Int
        val bypassRules = runCatching {
            val arr = JSONArray(bypassJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(listOf("<local>"))
        ProxySettingsUiState(proxies, enabled, rotationMode, intervalMin, bypassRules, activeIndex)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySettingsUiState())

    fun setProxyEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setProxyEnabled(enabled) }
    }

    fun setRotationMode(mode: ProxyRotationMode) {
        viewModelScope.launch { preferences.setProxyRotationMode(mode.name) }
    }

    fun setRotationInterval(minutes: Int) {
        viewModelScope.launch { preferences.setProxyRotationIntervalMinutes(minutes) }
    }

    fun saveProxy(
        id: String?,
        label: String,
        type: ProxyType,
        host: String,
        port: Int,
        username: String?,
        password: String?,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            proxyRepository.save(id, label, type, host, port, username, password, enabled)
        }
    }

    fun deleteProxy(id: String) {
        viewModelScope.launch { proxyRepository.delete(id) }
    }

    fun toggleProxyEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { proxyRepository.setEnabled(id, enabled) }
    }

    fun addBypassRule(rule: String) {
        if (rule.isBlank()) return
        viewModelScope.launch {
            val current = uiState.value.bypassRules
            if (rule in current) return@launch
            val updated = current + rule
            preferences.setProxyBypassRulesJson(JSONArray(updated).toString())
        }
    }

    fun removeBypassRule(rule: String) {
        viewModelScope.launch {
            val updated = uiState.value.bypassRules.filterNot { it == rule }
            preferences.setProxyBypassRulesJson(JSONArray(updated).toString())
        }
    }
}
