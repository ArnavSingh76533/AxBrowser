package com.akay.feature.browser.extensions

import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject

/** No addJavascriptInterface, DOM message relay, or page-world fallback. */
class ContentScriptManager(private val runtime: ExtensionRuntime, private val view: WebView,
    private val tabId: () -> String?, private val incognito: () -> Boolean) {
    private data class Registration(val world: JavaScriptExecutionWorld, val scripts: MutableList<ScriptHandler>)
    private val registrations = mutableMapOf<String, Registration>()
    fun register() {
        close()
        if (!runtime.isolatedSupported || incognito()) return
        runtime.manager.extensions.value.filter { it.enabled }.forEach { ext -> runCatching {
            val patterns = runtime.permissions.hosts(ext) + ext.manifest.scripts.flatMap { it.matches }
            if (patterns.isEmpty()) return@forEach
            val origins = patterns.flatMap { it.origins() }.toSet()
            val world = WebViewCompat.getExecutionWorld(view, "ax.extension.${ext.id}")
            val registration = Registration(world, mutableListOf()); registrations[ext.id] = registration
            WebViewCompat.addWebMessageListener(view, "AxExtension", origins, world) { _, message, origin, main, proxy ->
                fun allowed(): Boolean = !incognito() && main && runCatching { runtime.manager.active(ext.id).root == ext.root }.getOrDefault(false) &&
                    view.url?.let { url ->
                        val u = android.net.Uri.parse(url)
                        origin.scheme == u.scheme && origin.host == u.host && origin.port == u.port &&
                            !isProtected(url) && patterns.any { it.matches(url) }
                    } == true
                if (allowed()) {
                    val ep = runtime.endpoint(view, ext.id, proxy) ?: ExtensionEndpoint(ext.id, ExtensionContext.CONTENT, view, proxy, tabId, ::allowed)
                    runtime.bridge.receive(ep, message.data ?: "")
                }
            }
            val guard = "if(window.top!==window||${protectedJs()})return;"
            registration.scripts += WebViewCompat.addJavaScriptOnEvent(view, "(()=>{$guard${runtime.shim(ext)}})();", WebViewCompat.INJECTION_EVENT_DOCUMENT_START, origins, world)
            ext.manifest.scripts.forEach { script ->
                val css = script.css.joinToString("\n") { resourceFile(ext.root, it).readText() }
                val js = script.js.joinToString("\n") { resourceFile(ext.root, it).readText() + "\n;" }
                val match = "const patterns=${JSONArray(script.matches.map { it.value })};const excludes=${JSONArray(script.excludes.map { it.value })};$MATCH_JS;if(!patterns.some(matches)||excludes.some(matches))return;"
                val style = if (css.isEmpty()) "" else "const style=document.createElement('style');style.textContent=${JSONObject.quote(css)};const add=()=>{if(document.documentElement)(document.head||document.documentElement).appendChild(style);};if(document.documentElement)add();else new MutationObserver((_,o)=>{if(document.documentElement){add();o.disconnect();}}).observe(document,{childList:true});"
                val body = "$style\n$js"
                val code = "(()=>{$guard$match${if (script.runAt == "document_idle") "setTimeout(()=>{$body},0);" else body}})();"
                registration.scripts += WebViewCompat.addJavaScriptOnEvent(view, code,
                    if (script.runAt == "document_start") WebViewCompat.INJECTION_EVENT_DOCUMENT_START else WebViewCompat.INJECTION_EVENT_DOCUMENT_END, origins, world)
            }
        }.onFailure { remove(ext.id); runtime.report(ext.id, it.message ?: "Content-script registration failed") } }
    }
    fun remove(id: String) {
        registrations.remove(id)?.let { registration ->
            registration.scripts.forEach { it.remove() }
            WebViewCompat.removeWebMessageListener(view, registration.world, "AxExtension")
        }
    }
    fun close() { registrations.keys.toList().forEach(::remove) }
    companion object {
        fun isProtected(url: String): Boolean = android.net.Uri.parse(url).host.let { it == "chromewebstore.google.com" || it == "chrome.google.com" || it?.endsWith(".ax-extension.invalid") == true }
        private fun protectedJs() = "['chromewebstore.google.com','chrome.google.com'].includes(location.hostname)||location.hostname.endsWith('.ax-extension.invalid')"
        private val MATCH_JS = """
            const matches=p=>{const u=new URL(location.href);if(!['http:','https:'].includes(u.protocol))return false;if(p==='<all_urls>')return true;
            const m=p.match(/^(\*|https?):\/\/([^/]+)(\/.*)$/);if(!m)return false;const h=m[2].toLowerCase();
            const host=h==='*'||u.hostname===h||(h.startsWith('*.')&&(u.hostname===h.slice(2)||u.hostname.endsWith('.'+h.slice(2))));
            const path=new RegExp('^'+m[3].split('*').map(s=>s.replace(/[.*+?^${'$'}{}()|[\]\\]/g,'\\${'$'}&')).join('.*')+'${'$'}');
            return (m[1]==='*'||u.protocol===m[1]+':')&&host&&path.test(u.pathname+u.search);};
        """.trimIndent()
    }
}
