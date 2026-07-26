package com.akay.feature.settings.viewmodel

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.data.userscript.UserScript
import com.akay.core.data.userscript.UserScriptCodec
import com.akay.core.domain.repository.HistoryRepository
import com.akay.feature.downloads.engine.YtDlpSetup
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchEngineOption(val name: String, val url: String)

val SEARCH_ENGINES = listOf(
    SearchEngineOption("Google", "https://www.google.com/search?q="),
    SearchEngineOption("DuckDuckGo", "https://duckduckgo.com/?q="),
    SearchEngineOption("Bing", "https://www.bing.com/search?q="),
    SearchEngineOption("Brave Search", "https://search.brave.com/search?q="),
    SearchEngineOption("Startpage", "https://www.startpage.com/sp/search?query=")
)

data class SettingsUiState(
    val isDarkMode: Boolean = true,
    val isAdBlockerEnabled: Boolean = true,
    val isHttpsUpgrade: Boolean = true,
    val isJavascriptEnabled: Boolean = true,
    val maxConcurrentDownloads: Int = 3,
    val isDesktopMode: Boolean = false,
    val fontSize: Int = 100,
    val clearCacheOnExit: Boolean = false,
    val isErudaEnabled: Boolean = false,
    val ytDlpInstalled: Boolean = false,
    val ytDlpUpdateStatus: String? = null,
    val searchEngineUrl: String = SEARCH_ENGINES.first().url,
    val customHeaders: String = ""
) {
    val searchEngineName: String
        get() = SEARCH_ENGINES.firstOrNull { it.url == searchEngineUrl }?.name ?: "Custom"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AxPreferences,
    private val historyRepository: HistoryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _userScripts = MutableStateFlow<List<UserScript>>(emptyList())
    val userScripts: StateFlow<List<UserScript>> = _userScripts.asStateFlow()

    init {
        loadPreferences()
        viewModelScope.launch {
            preferences.userScripts.collect { _userScripts.value = UserScriptCodec.decode(it) }
        }
    }

    private fun persistScripts(list: List<UserScript>) {
        viewModelScope.launch { preferences.setUserScripts(UserScriptCodec.encode(list)) }
    }

    fun saveScript(script: UserScript) {
        val current = _userScripts.value
        val updated = if (current.any { it.id == script.id }) {
            current.map { if (it.id == script.id) script else it }
        } else current + script
        persistScripts(updated)
    }

    fun deleteScript(id: String) = persistScripts(_userScripts.value.filterNot { it.id == id })

    fun toggleScript(id: String) = persistScripts(
        _userScripts.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
    )

    fun importScriptFromUrl(url: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val normalized = if (url.startsWith("http")) url else "https://$url"
                    val conn = (java.net.URL(normalized).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "AxBrowser")
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
            }
            result.onSuccess { source ->
                if (source.isBlank()) { onResult(false, "Empty file"); return@launch }
                val script = UserScriptCodec.fromUserJs(source)
                saveScript(script)
                onResult(true, "Imported \"${script.name}\"")
            }.onFailure { onResult(false, it.message ?: "Import failed") }
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferences.isDarkMode.collect { _uiState.value = _uiState.value.copy(isDarkMode = it) }
        }
        viewModelScope.launch {
            preferences.isAdBlockerEnabled.collect { _uiState.value = _uiState.value.copy(isAdBlockerEnabled = it) }
        }
        viewModelScope.launch {
            preferences.isHttpsUpgrade.collect { _uiState.value = _uiState.value.copy(isHttpsUpgrade = it) }
        }
        viewModelScope.launch {
            preferences.isJavascriptEnabled.collect { _uiState.value = _uiState.value.copy(isJavascriptEnabled = it) }
        }
        viewModelScope.launch {
            preferences.maxConcurrentDownloads.collect { _uiState.value = _uiState.value.copy(maxConcurrentDownloads = it) }
        }
        viewModelScope.launch {
            preferences.isDesktopMode.collect { _uiState.value = _uiState.value.copy(isDesktopMode = it) }
        }
        viewModelScope.launch {
            preferences.fontSize.collect { _uiState.value = _uiState.value.copy(fontSize = it) }
        }
        viewModelScope.launch {
            preferences.clearCacheOnExit.collect { _uiState.value = _uiState.value.copy(clearCacheOnExit = it) }
        }
        viewModelScope.launch {
            preferences.erudaEnabled.collect { _uiState.value = _uiState.value.copy(isErudaEnabled = it) }
        }
        viewModelScope.launch {
            preferences.searchEngine.collect { _uiState.value = _uiState.value.copy(searchEngineUrl = it) }
        }
        viewModelScope.launch {
            preferences.customHeaders.collect { _uiState.value = _uiState.value.copy(customHeaders = it) }
        }
        _uiState.value = _uiState.value.copy(
            ytDlpInstalled = YtDlpSetup.isInstalled(context)
        )
    }

    fun setDarkMode(enabled: Boolean) { viewModelScope.launch { preferences.setDarkMode(enabled) } }
    fun setAdBlockerEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setAdBlockerEnabled(enabled) } }
    fun setHttpsUpgrade(enabled: Boolean) { viewModelScope.launch { preferences.setHttpsUpgrade(enabled) } }
    fun setJavascriptEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setJavascriptEnabled(enabled) } }
    fun setDesktopMode(enabled: Boolean) { viewModelScope.launch { preferences.setDesktopMode(enabled) } }
    fun setFontSize(size: Int) { viewModelScope.launch { preferences.setFontSize(size) } }
    fun setClearCacheOnExit(enabled: Boolean) { viewModelScope.launch { preferences.setClearCacheOnExit(enabled) } }
    fun setErudaEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setErudaEnabled(enabled) } }
    fun setSearchEngine(url: String) { viewModelScope.launch { preferences.setSearchEngine(url) } }
    fun setCustomHeaders(headers: String) { viewModelScope.launch { preferences.setCustomHeaders(headers) } }

    fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            if (clearHistory) {
                runCatching { historyRepository.deleteAllHistory() }
            }
            if (clearCookies) {
                runCatching {
                    val manager = CookieManager.getInstance()
                    manager.removeAllCookies(null)
                    manager.flush()
                    WebStorage.getInstance().deleteAllData()
                }
            }
            if (clearCache) {
                withContext(Dispatchers.IO) {
                    runCatching { context.cacheDir.deleteRecursively() }
                }
            }
            onDone()
        }
    }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Updating...")
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context, YoutubeDL.UpdateChannel.STABLE
                )
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> {
                        _uiState.value = _uiState.value.copy(
                            ytDlpUpdateStatus = "Updated successfully",
                            ytDlpInstalled = true
                        )
                    }
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                        _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Already up to date")
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Update status: $status")
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Update failed: ${e.message}")
            }
        }
    }
}
