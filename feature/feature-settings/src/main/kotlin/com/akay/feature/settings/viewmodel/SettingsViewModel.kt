package com.akay.feature.settings.viewmodel

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.ai.AI_PROVIDER_PRESETS
import com.akay.core.data.ai.OpenRouterClient
import com.akay.core.data.ai.OpenRouterModel
import com.akay.core.data.ai.presetForId
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.data.storage.AxStorage
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
import kotlinx.coroutines.flow.first
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
    val customHeaders: String = "",
    val amoled: Boolean = true,
    val accentName: String = "Nebula Violet",
    val galaxyEnabled: Boolean = true,
    val animationIntensity: Int = 100,
    val darkModeForWebsites: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockPinSet: Boolean = false,
    val appLockUseBiometric: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val batterySaverEnabled: Boolean = false,
    val themePreset: String = "Nebula",
    val fingerprintProtectionEnabled: Boolean = false,
    val fingerprintSpoofCanvas: Boolean = true,
    val fingerprintSpoofWebGl: Boolean = true,
    val fingerprintSpoofHardware: Boolean = true,
    val aiApiKeySet: Boolean = false,
    val aiModel: String = "meta-llama/llama-3.1-8b-instruct:free",
    val aiFreeModels: List<OpenRouterModel> = emptyList(),
    val aiModelsLoading: Boolean = false,
    val aiModelsError: String? = null,
    val aiProvider: String = "openrouter",
    val aiBaseUrl: String = "https://openrouter.ai/api/v1",
    val storageFolderConfigured: Boolean = false,
    val storageFolderName: String? = null
) {
    val searchEngineName: String
        get() = SEARCH_ENGINES.firstOrNull { it.url == searchEngineUrl }?.name ?: "Custom"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AxPreferences,
    private val historyRepository: HistoryRepository,
    private val openRouterClient: OpenRouterClient,
    private val axStorage: AxStorage,
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
        refreshStorageStatus()
    }

    /** Re-checks whether the configured folder (if any) is still actually accessible - a
     *  previously-granted folder can go away (SD card removed, user revoked it from system
     *  settings), and the status line should reflect that rather than keep showing stale info. */
    fun refreshStorageStatus() {
        viewModelScope.launch {
            val configured = axStorage.hasConfiguredFolder()
            val name = if (configured) axStorage.rootFolderDisplayName() else null
            _uiState.value = _uiState.value.copy(storageFolderConfigured = configured, storageFolderName = name)
        }
    }

    /** Called after the SAF folder picker returns a URI - persists it and takes the persistable
     *  read/write permission so it survives app restarts and device reboots, not just this
     *  session. */
    fun onStorageFolderPicked(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            preferences.setAxStorageRootUri(uri.toString())
            refreshStorageStatus()
        }
    }

    /** Opts back out to app-private storage - the folder itself isn't deleted, AxBrowser just
     *  stops using it and future writes fall back to internal storage like before this feature
     *  existed. */
    fun clearStorageFolder() {
        viewModelScope.launch {
            preferences.setAxStorageRootUri("")
            refreshStorageStatus()
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
        viewModelScope.launch {
            preferences.amoledTheme.collect { _uiState.value = _uiState.value.copy(amoled = it) }
        }
        viewModelScope.launch {
            preferences.accentColor.collect { _uiState.value = _uiState.value.copy(accentName = it) }
        }
        viewModelScope.launch {
            preferences.galaxyEnabled.collect { _uiState.value = _uiState.value.copy(galaxyEnabled = it) }
        }
        viewModelScope.launch {
            preferences.appLockEnabled.collect { _uiState.value = _uiState.value.copy(appLockEnabled = it) }
        }
        viewModelScope.launch {
            preferences.appLockPinHash.collect { _uiState.value = _uiState.value.copy(appLockPinSet = !it.isNullOrBlank()) }
        }
        viewModelScope.launch {
            preferences.appLockUseBiometric.collect { _uiState.value = _uiState.value.copy(appLockUseBiometric = it) }
        }
        viewModelScope.launch {
            preferences.wifiOnlyDownloads.collect { _uiState.value = _uiState.value.copy(wifiOnlyDownloads = it) }
        }
        viewModelScope.launch {
            preferences.batterySaverEnabled.collect { _uiState.value = _uiState.value.copy(batterySaverEnabled = it) }
        }
        viewModelScope.launch {
            preferences.themePreset.collect { _uiState.value = _uiState.value.copy(themePreset = it) }
        }
        viewModelScope.launch {
            preferences.fingerprintProtectionEnabled.collect { _uiState.value = _uiState.value.copy(fingerprintProtectionEnabled = it) }
        }
        viewModelScope.launch {
            preferences.fingerprintSpoofCanvas.collect { _uiState.value = _uiState.value.copy(fingerprintSpoofCanvas = it) }
        }
        viewModelScope.launch {
            preferences.fingerprintSpoofWebGl.collect { _uiState.value = _uiState.value.copy(fingerprintSpoofWebGl = it) }
        }
        viewModelScope.launch {
            preferences.fingerprintSpoofHardware.collect { _uiState.value = _uiState.value.copy(fingerprintSpoofHardware = it) }
        }
        viewModelScope.launch {
            preferences.openRouterApiKey.collect { _uiState.value = _uiState.value.copy(aiApiKeySet = !it.isNullOrBlank()) }
        }
        viewModelScope.launch {
            preferences.aiModel.collect { _uiState.value = _uiState.value.copy(aiModel = it) }
        }
        viewModelScope.launch {
            preferences.aiProvider.collect { _uiState.value = _uiState.value.copy(aiProvider = it) }
        }
        viewModelScope.launch {
            preferences.aiBaseUrl.collect { _uiState.value = _uiState.value.copy(aiBaseUrl = it) }
        }
        viewModelScope.launch {
            preferences.animationIntensity.collect { _uiState.value = _uiState.value.copy(animationIntensity = it) }
        }
        viewModelScope.launch {
            preferences.darkModeForWebsites.collect { _uiState.value = _uiState.value.copy(darkModeForWebsites = it) }
        }
        _uiState.value = _uiState.value.copy(
            ytDlpInstalled = YtDlpSetup.isInstalled(context)
        )
    }

    fun setAmoled(enabled: Boolean) { viewModelScope.launch { preferences.setAmoledTheme(enabled) } }
    fun setAccent(name: String) { viewModelScope.launch { preferences.setAccentColor(name) } }
    fun setGalaxyEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setGalaxyEnabled(enabled) } }
    fun setAnimationIntensity(value: Int) { viewModelScope.launch { preferences.setAnimationIntensity(value) } }
    fun setDarkModeForWebsites(enabled: Boolean) { viewModelScope.launch { preferences.setDarkModeForWebsites(enabled) } }

    fun setDarkMode(enabled: Boolean) { viewModelScope.launch { preferences.setDarkMode(enabled) } }
    fun setAdBlockerEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setAdBlockerEnabled(enabled) } }

    fun setAppLockEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setAppLockEnabled(enabled) } }
    fun setAppLockUseBiometric(enabled: Boolean) { viewModelScope.launch { preferences.setAppLockUseBiometric(enabled) } }
    fun setAppLockPin(pin: String) {
        viewModelScope.launch { preferences.setAppLockPinHash(com.akay.core.ui.security.PinHasher.hash(pin)) }
    }
    fun clearAppLockPin() {
        viewModelScope.launch {
            preferences.setAppLockPinHash(null)
            preferences.setAppLockEnabled(false)
        }
    }
    fun setWifiOnlyDownloads(enabled: Boolean) { viewModelScope.launch { preferences.setWifiOnlyDownloads(enabled) } }
    fun setBatterySaverEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setBatterySaverEnabled(enabled) } }
    fun setThemePreset(name: String) { viewModelScope.launch { preferences.setThemePreset(name) } }

    fun readLastCrashLog(): String? = com.akay.core.data.crash.CrashLogger.readLastCrash(context)
    fun clearLastCrashLog() { com.akay.core.data.crash.CrashLogger.clearLastCrash(context) }

    fun setFingerprintProtectionEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setFingerprintProtectionEnabled(enabled) } }
    fun setFingerprintSpoofCanvas(enabled: Boolean) { viewModelScope.launch { preferences.setFingerprintSpoofCanvas(enabled) } }
    fun setFingerprintSpoofWebGl(enabled: Boolean) { viewModelScope.launch { preferences.setFingerprintSpoofWebGl(enabled) } }
    fun setFingerprintSpoofHardware(enabled: Boolean) { viewModelScope.launch { preferences.setFingerprintSpoofHardware(enabled) } }
    fun regenerateFingerprintIdentity() { viewModelScope.launch { preferences.regenerateFingerprintSeed() } }

    fun setAiApiKey(key: String) {
        viewModelScope.launch { preferences.setOpenRouterApiKey(key) }
    }

    fun clearAiApiKey() {
        viewModelScope.launch { preferences.setOpenRouterApiKey(null) }
    }

    fun setAiModel(modelId: String) {
        viewModelScope.launch { preferences.setAiModel(modelId) }
    }

    /** Switching provider re-points baseUrl at that preset's URL (except "custom", which keeps
     *  whatever the person already typed) and clears the stale model list from whichever
     *  provider was selected before, since a model ID from one provider is meaningless on
     *  another. */
    fun setAiProvider(providerId: String) {
        viewModelScope.launch {
            preferences.setAiProvider(providerId)
            val preset = presetForId(providerId)
            if (providerId != "custom") preferences.setAiBaseUrl(preset.baseUrl)
            _uiState.value = _uiState.value.copy(aiFreeModels = emptyList(), aiModelsError = null)
        }
    }

    fun setAiBaseUrl(url: String) {
        viewModelScope.launch { preferences.setAiBaseUrl(url) }
    }

    fun refreshFreeModels() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(aiModelsLoading = true, aiModelsError = null)
            val key = preferences.openRouterApiKey.first()
            val provider = preferences.aiProvider.first()
            val preset = presetForId(provider)
            val baseUrl = if (provider == "custom") preferences.aiBaseUrl.first() else preset.baseUrl
            if (provider == "custom" && baseUrl.isBlank()) {
                _uiState.value = _uiState.value.copy(aiModelsLoading = false, aiModelsError = "Set a base URL first.")
                return@launch
            }
            val result = if (preset.filterToFree) {
                openRouterClient.fetchFreeModels(key)
            } else {
                openRouterClient.fetchModels(key, baseUrl)
            }
            _uiState.value = result.fold(
                onSuccess = { models -> _uiState.value.copy(aiModelsLoading = false, aiFreeModels = models) },
                onFailure = { e -> _uiState.value.copy(aiModelsLoading = false, aiModelsError = e.message ?: "Failed to load models") }
            )
        }
    }
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
