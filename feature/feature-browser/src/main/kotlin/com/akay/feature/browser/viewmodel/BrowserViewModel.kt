package com.akay.feature.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.ai.OpenRouterClient
import com.akay.core.data.datastore.AxPreferences
import com.akay.feature.browser.adblock.AdBlockEngine
import com.akay.core.domain.model.Bookmark
import com.akay.core.domain.model.HistoryItem
import com.akay.core.domain.model.SavedCredential
import com.akay.core.domain.model.SitePermissionType
import com.akay.core.domain.model.Tab
import com.akay.core.domain.repository.AdBlockRepository
import com.akay.core.domain.repository.BookmarkRepository
import com.akay.core.domain.repository.HistoryRepository
import com.akay.core.domain.repository.PasswordRepository
import com.akay.core.domain.repository.TabRepository
import com.akay.feature.browser.suggest.SearchSuggestion
import com.akay.feature.browser.suggest.SearchSuggestionProvider
import com.akay.feature.browser.suggest.SuggestionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject

data class BrowserUiState(
    val tabs: List<Tab> = emptyList(),
    val activeTab: Tab? = null,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val title: String = "",
    val progress: Int = 0,
    val isIncognito: Boolean = false,
    val error: String? = null,
    val showTabSwitcher: Boolean = false,
    val showDownloadSheet: Boolean = false,
    val devConsoleVisible: Boolean = false,
    val pageHtml: String = "",
    val detectedMediaCount: Int = 0,
    val batterySaverEnabled: Boolean = false
)

sealed class BrowserUiEvent {
    data object NavigateBack : BrowserUiEvent()
    data class ShowSnackbar(val message: String) : BrowserUiEvent()
    data class NavigateToUrl(val url: String) : BrowserUiEvent()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabRepository: TabRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val preferences: AxPreferences,
    private val suggestionProvider: SearchSuggestionProvider,
    private val adBlockRepository: AdBlockRepository,
    private val passwordRepository: PasswordRepository,
    val openRouterClient: OpenRouterClient,
    private val proxyManager: com.akay.core.data.proxy.ProxyManager,
    private val savedRequestDao: com.akay.core.data.db.dao.SavedRequestDao,
    private val siteNoteDao: com.akay.core.data.db.dao.SiteNoteDao,
    private val watchDao: com.akay.core.data.db.dao.WatchDao,
    val axStorage: com.akay.core.data.storage.AxStorage,
    val findingsStore: com.akay.feature.browser.agent.FindingsStore,
    val identityTools: com.akay.feature.browser.agent.IdentityTools,
    val datasetStore: com.akay.feature.browser.agent.DatasetStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableStateFlow<BrowserUiEvent?>(null)
    val events: StateFlow<BrowserUiEvent?> = _events.asStateFlow()

    val erudaEnabled = preferences.erudaEnabled
    val adBlockEnabled = preferences.isAdBlockerEnabled
    val httpsUpgradeEnabled = preferences.isHttpsUpgrade
    val javascriptEnabled = preferences.isJavascriptEnabled
    val desktopMode = preferences.isDesktopMode
    val fontSize = preferences.fontSize
    val customHeaders = preferences.customHeaders
    val userScripts = preferences.userScripts
    val darkModeForWebsites = preferences.darkModeForWebsites

    private var searchEngineUrl: String = "https://www.google.com/search?q="

    private val addressQuery = MutableStateFlow("")

    val suggestions: StateFlow<List<SearchSuggestion>> = addressQuery
        .debounce(180)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank() || looksLikeUrl(query)) {
                flowOf(emptyList())
            } else {
                combine(
                    bookmarkRepository.searchBookmarks(query),
                    historyRepository.searchHistory(query)
                ) { bookmarks, history ->
                    val local = buildList {
                        bookmarks.take(3).forEach {
                            add(SearchSuggestion(text = it.title.ifBlank { it.url }, subtitle = it.url, url = it.url, type = SuggestionType.BOOKMARK))
                        }
                        history.take(3).forEach {
                            add(SearchSuggestion(text = it.title?.ifBlank { it.url } ?: it.url, subtitle = it.url, url = it.url, type = SuggestionType.HISTORY))
                        }
                    }
                    local
                }.flatMapLatest { local ->
                    flow {
                        emit(local)
                        val remote = suggestionProvider.fetchRemoteSuggestions(query)
                        emit(local + remote.filter { r -> local.none { it.text.equals(r.text, ignoreCase = true) } })
                    }
                }
            }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") ||
            (t.contains(".") && !t.contains(" ") && !t.startsWith("."))
    }

    fun onAddressQueryChanged(query: String) {
        updateUrl(query)
        addressQuery.value = query
    }

    // ---- Device fingerprint spoofing ----
    // Declared before init{} on purpose: init calls observeFingerprintSettings(),
    // whose coroutine can write to _fingerprintScript as early as its first
    // synchronous emission - if that property were declared *after* init{} in
    // the class body, Kotlin's in-order member initialization means it could
    // still be null (uninitialized) at that point, causing an intermittent NPE
    // crash depending on how fast the underlying DataStore reads resolve.
    private val _fingerprintScript = MutableStateFlow<String?>(null)
    val fingerprintScript: StateFlow<String?> = _fingerprintScript.asStateFlow()

    private fun observeFingerprintSettings() {
        viewModelScope.launch {
            val fallbackSeed = preferences.getOrCreateFingerprintSeed()
            kotlinx.coroutines.flow.combine(
                preferences.fingerprintProtectionEnabled,
                preferences.fingerprintSpoofCanvas,
                preferences.fingerprintSpoofWebGl,
                preferences.fingerprintSpoofHardware,
                preferences.fingerprintDeviceSeed
            ) { enabled, spoofCanvas, spoofWebGl, spoofHardware, seed ->
                if (!enabled) {
                    null
                } else {
                    com.akay.feature.browser.fingerprint.FingerprintSpoofing.buildScript(
                        seed.ifBlank { fallbackSeed }, spoofCanvas, spoofWebGl, spoofHardware
                    )
                }
            }.collect { script ->
                _fingerprintScript.value = script
            }
        }
    }

    init {
        loadTabs()
        viewModelScope.launch {
            preferences.searchEngine.collect { searchEngineUrl = it }
        }
        viewModelScope.launch {
            preferences.batterySaverEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(batterySaverEnabled = enabled)
            }
        }
        viewModelScope.launch {
            adBlockRepository.observeAllBlockedHosts().collect { hosts ->
                AdBlockEngine.setCustomHosts(hosts.toHashSet())
            }
        }
        viewModelScope.launch {
            adBlockRepository.observeDisabledOrigins(SitePermissionType.AD_BLOCK).collect { origins ->
                AdBlockEngine.setAllowlistedOrigins(origins.toHashSet())
            }
        }
        observeFingerprintSettings()
    }

    private fun currentOrigin(): String? =
        runCatching { java.net.URI(_uiState.value.displayUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()

    fun isAdBlockAllowlistedForCurrentSite(): Boolean = AdBlockEngine.isOriginAllowlisted(currentOrigin())

    fun toggleAdBlockForCurrentSite() {
        val origin = currentOrigin() ?: return
        viewModelScope.launch {
            val enabled = adBlockRepository.isEnabledForOrigin(origin, SitePermissionType.AD_BLOCK)
            adBlockRepository.setEnabledForOrigin(origin, SitePermissionType.AD_BLOCK, !enabled)
        }
    }

    // ---- Password manager ----

    data class PendingCredentialSave(val origin: String, val username: String, val password: String)

    private val _pendingCredentialSave = MutableStateFlow<PendingCredentialSave?>(null)
    val pendingCredentialSave: StateFlow<PendingCredentialSave?> = _pendingCredentialSave.asStateFlow()

    private val _fillableCredential = MutableStateFlow<SavedCredential?>(null)
    val fillableCredential: StateFlow<SavedCredential?> = _fillableCredential.asStateFlow()

    /** Called by the JS bridge when a login form with a non-empty password is submitted. */
    fun onCredentialCaptured(origin: String, username: String, password: String) {
        viewModelScope.launch {
            val existing = passwordRepository.getForOrigin(origin).firstOrNull { it.username == username }
            if (existing?.password == password) return@launch // already saved, nothing to prompt
            _pendingCredentialSave.value = PendingCredentialSave(origin, username, password)
        }
    }

    fun confirmSaveCredential() {
        val pending = _pendingCredentialSave.value ?: return
        viewModelScope.launch {
            passwordRepository.save(pending.origin, pending.username, pending.password)
            _pendingCredentialSave.value = null
        }
    }

    fun dismissSaveCredential() {
        _pendingCredentialSave.value = null
    }

    /** Called on every page load to check whether we have a saved login to offer to autofill. */
    fun onPageOriginLoaded(url: String) {
        val origin = runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}" } }.getOrNull()
        if (origin == null) {
            _fillableCredential.value = null
            return
        }
        viewModelScope.launch {
            _fillableCredential.value = passwordRepository.getForOrigin(origin).firstOrNull()
        }
    }

    val aiApiKey: StateFlow<String?> = preferences.openRouterApiKey
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    val aiModel: StateFlow<String> = preferences.aiModel
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "meta-llama/llama-3.1-8b-instruct:free")
    val aiBaseUrl: StateFlow<String> = preferences.aiBaseUrl
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "https://openrouter.ai/api/v1")

    // ---- Captcha solving (used by the agent's captcha tools and the Captcha dev tab) ----
    val captchaSolverEnabled: StateFlow<Boolean> = preferences.captchaSolverEnabled
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val captchaSolverApiKey: StateFlow<String?> = preferences.captchaSolverApiKey
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    val captchaSolverBaseUrl: StateFlow<String> = preferences.captchaSolverBaseUrl
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "https://2captcha.com")
    val captchaAutoCheckbox: StateFlow<Boolean> = preferences.captchaAutoCheckbox
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)

    fun setCaptchaSolverApiKey(key: String?) { viewModelScope.launch { preferences.setCaptchaSolverApiKey(key) } }
    fun setCaptchaSolverBaseUrl(url: String) { viewModelScope.launch { preferences.setCaptchaSolverBaseUrl(url) } }
    fun setCaptchaSolverEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setCaptchaSolverEnabled(enabled) } }
    fun setCaptchaAutoCheckbox(enabled: Boolean) { viewModelScope.launch { preferences.setCaptchaAutoCheckbox(enabled) } }

    val activeProxy: StateFlow<com.akay.core.domain.model.ProxyServer?> = proxyManager.activeProxy

    fun onWebViewReady() {
        proxyManager.markWebViewReady()
    }

    fun clearFillableCredential() {
        _fillableCredential.value = null
    }

    // ---- Tab groups ----

    val groupColors = listOf(0xFFB388FF, 0xFF80D8FF, 0xFFFF8A80, 0xFFFFD180, 0xFFA7FFEB, 0xFFCCFF90)

    fun groupTabs(tabIds: List<String>, groupName: String, colorArgb: Long) {
        if (tabIds.isEmpty()) return
        val groupId = java.util.UUID.randomUUID().toString()
        viewModelScope.launch {
            tabIds.forEach { id ->
                tabRepository.assignTabToGroup(id, groupId, groupName, colorArgb.toInt())
            }
            loadTabs()
        }
    }

    fun addTabToExistingGroup(tabId: String, groupId: String, groupName: String, colorArgb: Int) {
        viewModelScope.launch {
            tabRepository.assignTabToGroup(tabId, groupId, groupName, colorArgb)
            loadTabs()
        }
    }

    fun removeTabFromGroup(tabId: String) {
        viewModelScope.launch {
            tabRepository.clearTabGroup(tabId)
            loadTabs()
        }
    }

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            val members = _uiState.value.tabs.filter { it.groupId == groupId }
            members.forEach { tab ->
                tabRepository.assignTabToGroup(tab.id, groupId, newName, tab.groupColor)
            }
            loadTabs()
        }
    }

    fun ungroupAll(groupId: String) {
        viewModelScope.launch {
            val members = _uiState.value.tabs.filter { it.groupId == groupId }
            members.forEach { tabRepository.clearTabGroup(it.id) }
            loadTabs()
        }
    }

    fun closeGroup(groupId: String) {
        viewModelScope.launch {
            val members = _uiState.value.tabs.filter { it.groupId == groupId }
            members.forEach { tabRepository.deleteTab(it.id) }
            loadTabs()
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        viewModelScope.launch { preferences.setDesktopMode(enabled) }
    }

    fun bookmarkCurrentPage(onDone: (Boolean) -> Unit) {
        val state = _uiState.value
        val url = state.displayUrl
        if (url.isBlank() || url == "about:blank") {
            onDone(false)
            return
        }
        viewModelScope.launch {
            runCatching {
                bookmarkRepository.addBookmark(
                    Bookmark(url = url, title = state.title.ifBlank { url })
                )
            }.onSuccess { onDone(true) }.onFailure { onDone(false) }
        }
    }

    private fun loadTabs() {
        viewModelScope.launch {
            tabRepository.getAllTabs().collect { tabs ->
                _uiState.value = _uiState.value.copy(tabs = tabs)
                if (tabs.isEmpty()) {
                    createNewTab()
                } else {
                    // Repository changes can also originate from the extension adapter.
                    val previous = _uiState.value.activeTab
                    val selected = tabs.find { it.isActive } ?: tabs.find { it.id == previous?.id } ?: tabs.first()
                    val needsNavigation = previous?.id != selected.id ||
                        (previous.url != selected.url && _uiState.value.displayUrl != selected.url)
                    if (needsNavigation) {
                        _uiState.value = _uiState.value.copy(activeTab = selected, url = selected.url,
                            displayUrl = selected.url, title = selected.title, showTabSwitcher = false)
                    } else {
                        _uiState.value = _uiState.value.copy(activeTab = selected)
                    }
                }
            }
        }
    }

    fun createNewTab(url: String = "about:blank", incognito: Boolean = false) {
        viewModelScope.launch {
            val tab = Tab(
                url = url,
                isIncognito = incognito,
                isActive = true
            )
            tabRepository.createTab(tab)
            tabRepository.setActiveTab(tab.id)
        }
    }

    fun setActiveTab(tab: Tab) {
        viewModelScope.launch {
            tabRepository.setActiveTab(tab.id)
            _uiState.value = _uiState.value.copy(
                activeTab = tab,
                url = tab.url,
                displayUrl = tab.url,
                title = tab.title,
                showTabSwitcher = false
            )
        }
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(displayUrl = url)
    }

    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
        viewModelScope.launch {
            _uiState.value.activeTab?.let { tab ->
                tabRepository.updateTab(tab.copy(url = _uiState.value.displayUrl, title = title, lastAccessed = System.currentTimeMillis()))
            }
        }
    }

    fun updateProgress(progress: Int) {
        _uiState.value = _uiState.value.copy(progress = progress)
    }

    fun updateNavigationState(isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.value = _uiState.value.copy(
            isLoading = isLoading,
            canGoBack = canGoBack,
            canGoForward = canGoForward
        )
    }

    fun navigateToUrl(url: String) {
        val processedUrl = when {
            url.isBlank() -> return
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "$searchEngineUrl${URLEncoder.encode(url, "UTF-8")}"
        }
        _uiState.value = _uiState.value.copy(url = processedUrl, displayUrl = processedUrl)
        proxyManager.onNavigation()
        viewModelScope.launch {
            _uiState.value.activeTab?.let { tab ->
                tabRepository.updateTab(tab.copy(url = processedUrl, lastAccessed = System.currentTimeMillis()))
            }
        }
    }

    fun recordHistory(url: String, title: String) {
        viewModelScope.launch {
            historyRepository.addHistoryItem(
                HistoryItem(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    title = title,
                    lastVisited = System.currentTimeMillis()
                )
            )
        }
    }

    fun toggleTabSwitcher() {
        _uiState.value = _uiState.value.copy(showTabSwitcher = !_uiState.value.showTabSwitcher)
    }

    fun toggleDevConsole() {
        _uiState.value = _uiState.value.copy(devConsoleVisible = !_uiState.value.devConsoleVisible)
    }

    fun updatePageHtml(html: String) {
        _uiState.value = _uiState.value.copy(pageHtml = html)
    }

    fun updateDetectedMediaCount(count: Int) {
        _uiState.value = _uiState.value.copy(detectedMediaCount = count)
    }

    fun closeTab(tabId: String) {
        viewModelScope.launch {
            tabRepository.deleteTab(tabId)
            if (_uiState.value.activeTab?.id == tabId) {
                val remainingTabs = _uiState.value.tabs.filter { it.id != tabId }
                if (remainingTabs.isNotEmpty()) {
                    setActiveTab(remainingTabs.first())
                } else {
                    createNewTab()
                }
            }
        }
    }

    fun toggleIncognito() {
        val newState = !_uiState.value.isIncognito
        _uiState.value = _uiState.value.copy(isIncognito = newState)
        createNewTab(incognito = newState)
    }

    fun onEventConsumed() {
        _events.value = null
    }

    // --- Agent persistence: saved curl requests, per-domain notes, background page watches ---
    // Plain DAO wrappers (no repository layer) - these three are agent-internal bookkeeping
    // rather than user-facing domain concepts like bookmarks/history, so the extra
    // interface+impl indirection isn't pulling its weight here.

    suspend fun saveRequest(label: String, curl: String, domain: String) {
        savedRequestDao.upsert(
            com.akay.core.data.db.entity.SavedRequestEntity(
                label = label, curl = curl, domain = domain, createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun listSavedRequests() = savedRequestDao.getAll()
    suspend fun getSavedRequest(label: String) = savedRequestDao.getByLabel(label)
    suspend fun deleteSavedRequest(label: String) = savedRequestDao.deleteByLabel(label)

    suspend fun addSiteNote(domain: String, note: String) {
        siteNoteDao.insert(
            com.akay.core.data.db.entity.SiteNoteEntity(domain = domain, note = note, createdAt = System.currentTimeMillis())
        )
    }

    suspend fun getSiteNotes(domain: String) = siteNoteDao.getForDomain(domain)

    suspend fun createWatch(label: String, url: String, intervalMinutes: Int) {
        watchDao.upsert(
            com.akay.core.data.db.entity.WatchEntity(
                label = label, url = url, intervalMinutes = intervalMinutes, createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun listWatches() = watchDao.getAll()
    suspend fun cancelWatch(label: String) = watchDao.deleteByLabel(label)
}
