package com.akay.feature.browser.extensions

import android.content.Context
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewFeature
import com.akay.feature.browser.extensions.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ExtensionContext { PAGE, BACKGROUND, CONTENT }
class ExtensionEndpoint(val extensionId: String, val kind: ExtensionContext, val view: WebView,
    val proxy: JavaScriptReplyProxy, val tabId: () -> String?, val allowed: () -> Boolean) {
    fun event(name: String, args: JSONArray, token: String? = null) {
        if (allowed()) proxy.postMessage(JSONObject().put("event", name).put("args", args).put("token", token).toString())
    }
}

/** All endpoint and WebView operations run on Main. Requests never accept a JS-supplied extension identity. */
class ExtensionRuntime(val context: Context, val manager: ExtensionManager, val browser: BrowserApis, val scope: CoroutineScope) {
    val permissions = PermissionsApi(context)
    val storage = StorageApi(context) { id, area, changes -> emit(id, "storage.onChanged", JSONArray().put(changes).put(area)) }
    val cookies = CookiesApi(permissions)
    val ui = ExtensionUiApi(context, this)
    val bridge = ExtensionBridge(this)
    private val endpoints = mutableListOf<ExtensionEndpoint>()
    private val retired = java.util.WeakHashMap<JavaScriptReplyProxy, Boolean>()
    private val backgrounds = mutableMapOf<String, WebView>()
    private val readyBackgrounds = mutableSetOf<String>()
    private val subscriptions = mutableMapOf<WebView, ContentScriptManager>()
    private val responses = mutableMapOf<String, Pair<ExtensionEndpoint, CompletableDeferred<Any?>>>()
    private val installEvents = mutableMapOf<String, String>()
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    var openPage: (Extension, String) -> Unit = { _, _ -> }
    val isolatedSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
    init {
        browser.granted = permissions::granted
        browser.hostPatterns = permissions::hosts
    }

    fun start(installedId: String? = null, reason: String = "install") {
        if (installedId != null) installEvents[installedId] = reason
        manager.extensions.value.filter { it.enabled }.forEach { ext ->
            if (ext.id !in backgrounds && ext.manifest.worker != null) runCatching {
                backgrounds[ext.id] = ExtensionWebViewBridge(this).create(ext, "__ax_background.html", ExtensionContext.BACKGROUND)
            }.onFailure { report(ext.id, it.message ?: "Background failed") }
        }
    }
    fun stop(id: String) {
        readyBackgrounds.remove(id)
        endpoints.filter { it.extensionId == id }.toList().forEach { ep ->
            retired[ep.proxy] = true
            responses.filterValues { it.first === ep }.keys.toList().forEach { token -> responses.remove(token)?.second?.completeExceptionally(IllegalStateException("Extension stopped")) }
        }
        endpoints.removeAll { it.extensionId == id }
        backgrounds.remove(id)?.let { it.stopLoading(); it.destroy() }
        subscriptions.values.forEach { it.remove(id) }
        storage.clearSession(id)
        ui.clear(id)
    }
    fun destroy() {
        manager.extensions.value.forEach { stop(it.id) }
        subscriptions.values.forEach { it.close() }; subscriptions.clear()
    }
    fun attach(view: WebView, tabId: () -> String?, incognito: () -> Boolean) {
        subscriptions.remove(view)?.close()
        subscriptions[view] = ContentScriptManager(this, view, tabId, incognito).also { it.register() }
    }
    fun detach(view: WebView) { invalidateView(view); subscriptions.remove(view)?.close() }
    fun refreshContent() { subscriptions.forEach { (view, scripts) -> invalidateView(view); scripts.register() } }
    fun invalidateView(view: WebView) {
        val removed = endpoints.filter { it.view === view }
        removed.forEach { retired[it.proxy] = true }
        endpoints.removeAll(removed.toSet())
        responses.filterValues { it.first in removed }.keys.toList().forEach { token -> responses.remove(token)?.second?.completeExceptionally(IllegalStateException("Document navigated")) }
    }
    fun register(ep: ExtensionEndpoint) {
        check(retired[ep.proxy] != true) { "Document context expired" }
        // onPageStarted may arrive AFTER document-start JS on older WebViews. The
        // new document's native reply proxy is the authoritative replacement signal.
        val previous = endpoints.filter { it !== ep && it.view === ep.view && it.extensionId == ep.extensionId && it.kind == ep.kind }
        previous.forEach { retired[it.proxy] = true }
        responses.filterValues { it.first in previous }.keys.toList().forEach { token ->
            responses.remove(token)?.second?.completeExceptionally(IllegalStateException("Document replaced"))
        }
        endpoints.removeAll { it.view === ep.view && it.extensionId == ep.extensionId && it.kind == ep.kind }
        endpoints.add(ep)
    }
    fun backgroundReady(ep: ExtensionEndpoint) {
        if (ep.kind == ExtensionContext.BACKGROUND) scope.launch {
            if (ep in endpoints && ep.allowed()) {
                readyBackgrounds.add(ep.extensionId)
                val reason = installEvents.remove(ep.extensionId)
                if (reason != null) ep.event("runtime.onInstalled", JSONArray().put(JSONObject().put("reason", reason)))
                else ep.event("runtime.onStartup", JSONArray())
            }
        }
    }
    fun endpoint(view: WebView, id: String, proxy: JavaScriptReplyProxy) = endpoints.firstOrNull { it.view === view && it.extensionId == id && it.proxy === proxy }
    fun isLive(ep: ExtensionEndpoint) = ep in endpoints && retired[ep.proxy] != true && ep.allowed()
    fun emit(id: String, event: String, args: JSONArray) {
        endpoints.filter { it.extensionId == id }.toList().forEach { runCatching { it.event(event, args) } }
    }
    fun navigation(tabId: String?, url: String, event: String, incognito: Boolean) {
        if (incognito || tabId == null) return
        manager.extensions.value.filter { it.enabled && "webNavigation" in permissions.granted(it) && permissions.hosts(it).any { p -> p.matches(url) } }.forEach {
            emit(it.id, "webNavigation.$event", JSONArray().put(JSONObject().put("tabId", browser.number(tabId)).put("url", url).put("frameId", 0).put("timeStamp", System.currentTimeMillis())))
        }
    }
    suspend fun message(sender: ExtensionEndpoint, args: JSONArray, toTab: Boolean): Any? {
        val id = if (toTab) browser.tab(args.getInt(0)).id else null
        if (!toTab && manager.active(sender.extensionId).manifest.worker != null && sender.kind != ExtensionContext.BACKGROUND) {
            withTimeout(5000) {
                while (sender.extensionId !in readyBackgrounds) {
                    check(isLive(sender)) { "Extension context expired" }
                    delay(25)
                }
            }
        }
        val receiver = endpoints.sortedBy { if (it.kind == ExtensionContext.BACKGROUND) 0 else 1 }.firstOrNull { it.extensionId == sender.extensionId && it !== sender && isLive(it) &&
            (if (toTab) it.kind == ExtensionContext.CONTENT && it.tabId() == id else it.kind != ExtensionContext.CONTENT) }
            ?: error("Could not establish connection. Receiving end does not exist.")
        val token = UUID.randomUUID().toString(); val deferred = CompletableDeferred<Any?>()
        responses[token] = receiver to deferred
        val data = JSONObject().put("id", sender.extensionId)
        if (sender.kind == ExtensionContext.CONTENT) {
            val ext = manager.active(sender.extensionId)
            sender.tabId()?.let { key -> browser.tabs.getTab(key)?.let { data.put("tab", browser.tabJson(it, ext)).put("url", it.url).put("frameId", 0) } }
        } else data.put("url", sender.view.url)
        try {
            receiver.event("runtime.onMessage", JSONArray().put(args.opt(if (toTab) 1 else 0)).put(data), token)
            return withTimeout(20000) { deferred.await() }
        } finally { responses.remove(token) }
    }
    fun respond(ep: ExtensionEndpoint, args: JSONArray, execution: Boolean): Any {
        val item = responses[args.getString(0)] ?: return JSONObject.NULL
        check(item.first === ep && ep.allowed()) { "Response context does not match request" }
        if (execution && !args.isNull(2)) item.second.completeExceptionally(IllegalStateException(args.getString(2)))
        else item.second.complete(args.opt(1))
        return JSONObject.NULL
    }
    suspend fun scripting(ext: Extension, method: String, details: JSONObject): Any? {
        check(isolatedSupported) { "Update Android System WebView to support isolated content scripts" }
        val target = details.getJSONObject("target")
        check(!target.optBoolean("allFrames") && !target.has("frameIds") && !target.has("documentIds")) { "Only the top frame is supported" }
        check(details.optString("world", "ISOLATED") == "ISOLATED") { "MAIN world is not supported" }
        val tab = browser.tab(target.getInt("tabId")); permissions.requireHost(ext, tab.url)
        val ep = endpoints.firstOrNull { it.extensionId == ext.id && it.kind == ExtensionContext.CONTENT && it.tabId() == tab.id && it.allowed() }
            ?: error("This tab has no live isolated extension context. Activate and reload it first.")
        val source = if (method == "insertCSS") {
            val css = if (details.has("css")) details.getString("css") else details.getJSONArray("files").strings().joinToString("\n") { resourceFile(ext.root, it).readText() }
            "const style=document.createElement('style');style.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(style);null"
        } else if (details.has("files")) details.getJSONArray("files").strings().joinToString("\n") { resourceFile(ext.root, it).readText() }
        else "return await (${details.getString("func")})(...${details.optJSONArray("args") ?: JSONArray()});"
        val token = UUID.randomUUID().toString(); val deferred = CompletableDeferred<Any?>(); responses[token] = ep to deferred
        val script = "(async()=>{try{const value=await(async()=>{$source\n})();__axCompleteExecution(${JSONObject.quote(token)},value,null);}catch(e){__axCompleteExecution(${JSONObject.quote(token)},null,String(e));}})();"
        try {
            ep.proxy.executeJavaScript(script, null)
            val result = withTimeout(20000) { deferred.await() }
            return if (method == "insertCSS") JSONObject.NULL else JSONArray().put(JSONObject().put("frameId", 0).put("result", result ?: JSONObject.NULL))
        } finally { responses.remove(token) }
    }
    fun shim(ext: Extension): String {
        val locale = java.util.Locale.getDefault().toLanguageTag()
        val messages = listOf(locale.replace('-', '_'), locale.substringBefore('-'), ext.manifest.json.optString("default_locale")).filter { Regex("[A-Za-z0-9_]+" ).matches(it) }
            .firstNotNullOfOrNull { l -> runCatching { JSONObject(resourceFile(ext.root, "_locales/$l/messages.json").readText()) }.getOrNull() } ?: JSONObject()
        val config = JSONObject().put("id", ext.id).put("origin", ext.origin).put("manifest", ext.manifest.json).put("messages", messages).put("locale", locale)
        return context.assets.open("extensions/chrome_bridge.js").bufferedReader().use { it.readText() }.replace("__AX_CONFIG__", config.toString())
    }
    fun report(id: String, message: String) { errors.value = errors.value + (id to message) }
}
