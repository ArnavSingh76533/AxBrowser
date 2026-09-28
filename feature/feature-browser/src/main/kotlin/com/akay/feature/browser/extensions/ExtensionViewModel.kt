package com.akay.feature.browser.extensions

import android.app.Application
import android.net.Uri
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.domain.model.Tab
import com.akay.core.domain.repository.BookmarkRepository
import com.akay.core.domain.repository.HistoryRepository
import com.akay.core.domain.repository.TabRepository
import com.akay.feature.browser.extensions.api.BrowserApis
import com.akay.feature.downloads.viewmodel.DownloadViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class PermissionPrompt(val extension: Extension, val permissions: Set<String>, val origins: Set<String>, val decision: CompletableDeferred<Boolean>)
data class ExtensionPage(val extension: Extension, val path: String)

@HiltViewModel
class ExtensionViewModel @Inject constructor(application: Application, tabs: TabRepository, history: HistoryRepository, bookmarks: BookmarkRepository) : AndroidViewModel(application) {
    val manager = ExtensionManager(application)
    val runtime = ExtensionRuntime(application, manager, BrowserApis(tabs, history, bookmarks), viewModelScope)
    private val installer = ExtensionInstaller(application)
    val pending = MutableStateFlow<PendingExtension?>(null)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val showManager = MutableStateFlow(false)
    val page = MutableStateFlow<ExtensionPage?>(null)
    val permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    private val permissionMutex = Mutex()
    private var downloadsJob: Job? = null
    private var loaded = false
    private var attachedView: WebView? = null
    init {
        runtime.openPage = { ext, path -> page.value = ExtensionPage(ext, path) }
        runtime.permissions.requestApproval = { ext, perms, hosts -> permissionMutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            permissionPrompt.value = PermissionPrompt(ext, perms, hosts, deferred)
            try { withTimeoutOrNull(25000) { deferred.await() } ?: false }
            finally { permissionPrompt.value = null }
        } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manager.load() }
            loaded = true; runtime.start(); runtime.refreshContent()
            var previous = emptyList<Tab>()
            tabs.getAllTabs().collect { all ->
                val current = all.filterNot { it.isIncognito }
                manager.extensions.value.filter { it.enabled }.forEach { ext ->
                    current.filter { t -> previous.none { it.id == t.id } }.forEach { runtime.emit(ext.id, "tabs.onCreated", JSONArray().put(runtime.browser.tabJson(it, ext))) }
                    previous.filter { t -> current.none { it.id == t.id } }.forEach { runtime.emit(ext.id, "tabs.onRemoved", JSONArray().put(runtime.browser.number(it.id)).put(JSONObject().put("windowId", 1).put("isWindowClosing", false))) }
                    current.forEach { tab ->
                        val old = previous.find { it.id == tab.id }
                        if (old != null && (old.url != tab.url || old.title != tab.title)) {
                            val data = runtime.browser.tabJson(tab, ext)
                            val change = JSONObject().put("status", "complete")
                            if (data.has("url")) change.put("url", tab.url).put("title", tab.title)
                            runtime.emit(ext.id, "tabs.onUpdated", JSONArray().put(runtime.browser.number(tab.id)).put(change).put(data))
                        }
                        if (tab.isActive && old?.isActive != true) runtime.emit(ext.id, "tabs.onActivated", JSONArray().put(JSONObject().put("tabId", runtime.browser.number(tab.id)).put("windowId", 1)))
                    }
                }
                previous = current
            }
        }
    }
    fun bindDownloads(vm: DownloadViewModel) {
        if (runtime.browser.downloads === vm) return
        runtime.browser.downloads = vm
        downloadsJob?.cancel()
        downloadsJob = viewModelScope.launch {
            var known = emptyMap<String, String>()
            vm.state.collect { state ->
                manager.extensions.value.filter { it.enabled && "downloads" in runtime.permissions.granted(it) }.forEach { ext ->
                    state.downloads.forEach { item ->
                        val id = runtime.browser.number(item.id)
                        if (item.id !in known) runtime.emit(ext.id, "downloads.onCreated", JSONArray().put(JSONObject().put("id", id).put("url", item.url).put("filename", item.filename)))
                        else if (known[item.id] != item.status.name) runtime.emit(ext.id, "downloads.onChanged", JSONArray().put(JSONObject().put("id", id).put("state", JSONObject().put("current", when(item.status.name) { "COMPLETED" -> "complete"; "FAILED", "CANCELLED" -> "interrupted"; else -> "in_progress" }))))
                    }
                }
                known = state.downloads.associate { it.id to it.status.name }
            }
        }
    }
    private fun stage(load: () -> PendingExtension) {
        if (busy.value || pending.value != null || !loaded) return
        busy.value = true; status.value = "Reading and verifying extension…"; showManager.value = true
        viewModelScope.launch {
            try { pending.value = withContext(Dispatchers.IO) { load() }; status.value = null }
            catch (e: Exception) { status.value = e.message ?: "Could not read extension" }
            finally { busy.value = false }
        }
    }
    fun importPackage(uri: Uri) = stage { installer.importPackage(uri) }
    fun importFolder(uri: Uri) = stage { installer.importFolder(uri) }
    fun installStore(url: String) {
        val id = ExtensionInstaller.storeId(url)
        if (id == null) { status.value = "Open a Chrome Web Store extension listing first"; return }
        stage { installer.fromStore(id) }
    }
    fun cancelInstall() {
        val staged = pending.value ?: return; pending.value = null
        viewModelScope.launch(Dispatchers.IO) { staged.directory.deleteRecursively() }
    }
    fun confirmInstall() {
        val staged = pending.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val update = manager.extensions.value.any { it.id == staged.id }
                runtime.stop(staged.id); page.value = null
                val ext = withContext(Dispatchers.IO) { manager.install(staged) }
                pending.value = null
                runtime.start(ext.id, if (update) "update" else "install"); runtime.refreshContent()
                status.value = "${ext.manifest.name} installed. Reload open pages to apply content scripts."
            } catch (e: Exception) { status.value = e.message ?: "Install failed"; runtime.start() }
            finally { busy.value = false }
        }
    }
    fun setEnabled(ext: Extension, enabled: Boolean) = mutate {
        runtime.stop(ext.id); if (page.value?.extension?.id == ext.id) page.value = null
        withContext(Dispatchers.IO) { manager.setEnabled(ext.id, enabled) }
        runtime.start(); runtime.refreshContent()
        status.value = "${if (enabled) "Enabled" else "Disabled"} ${ext.manifest.name}. Reload open pages to clear or apply page changes."
    }
    fun uninstall(ext: Extension) = mutate {
        runtime.stop(ext.id); page.value = null
        withContext(Dispatchers.IO) { manager.uninstall(ext.id); runtime.storage.uninstall(ext.id); runtime.permissions.clear(ext.id) }
        runtime.refreshContent(); status.value = "${ext.manifest.name} removed"
    }
    private fun mutate(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch { try { block() } catch (e: Exception) { status.value = e.message } finally { busy.value = false } }
    }
    fun action(ext: Extension) {
        if (!ext.enabled) return
        val popup = runtime.ui.action(ext).optString("popup")
        if (popup.isNotBlank()) page.value = ExtensionPage(ext, popup)
        else viewModelScope.launch { runCatching { runtime.browser.current() }.onSuccess { runtime.emit(ext.id, "action.onClicked", JSONArray().put(runtime.browser.tabJson(it, ext))) } }
    }
    fun pageAction(ext: Extension, item: JSONObject) = viewModelScope.launch {
        runCatching { runtime.browser.current() }.onSuccess { tab ->
            runtime.emit(ext.id, "contextMenus.onClicked", JSONArray().put(JSONObject().put("menuItemId", item.getString("id")).put("pageUrl", tab.url)).put(runtime.browser.tabJson(tab, ext)))
        }.onFailure { status.value = it.message }
    }
    fun command(ext: Extension, name: String) { runtime.emit(ext.id, "commands.onCommand", JSONArray().put(name)) }
    fun attach(view: WebView, tabId: String?, incognito: Boolean) {
        attachedView = view
        runtime.attach(view, { tabId }, { incognito })
        runtime.browser.reload = { id -> check(id == tabId && !incognito) { "Only the visible tab can be reloaded" }; view.reload() }
    }
    fun detach(view: WebView) { runtime.detach(view); if (attachedView === view) { attachedView = null; runtime.browser.reload = null } }
    override fun onCleared() { runtime.destroy(); super.onCleared() }
}
