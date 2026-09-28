package com.akay.feature.browser.extensions

import com.akay.feature.browser.extensions.api.CapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Protocol v1: {v,id,method,args} -> {id,result} or {id,error:{message}}. */
class ExtensionBridge(private val runtime: ExtensionRuntime) {
    fun receive(endpoint: ExtensionEndpoint, raw: String) {
        if (raw.length > 1024 * 1024 || !endpoint.allowed()) return
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = request.optString("id")
        if (id.length !in 1..80) return
        runtime.scope.launch {
            val response = JSONObject().put("id", id)
            try {
                check(request.getInt("v") == 1) { "Unsupported protocol version" }
                check(endpoint.allowed()) { "Extension context expired" }
                val ext = runtime.manager.active(endpoint.extensionId)
                val method = request.getString("method")
                check(method == "runtime.__ready" || runtime.isLive(endpoint)) { "Extension document is not registered" }
                val args = request.getJSONArray("args")
                response.put("result", dispatch(ext, endpoint, method, args) ?: JSONObject.NULL)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { response.put("error", JSONObject().put("message", e.message ?: "Extension request failed")) }
            if (runtime.isLive(endpoint)) runCatching { endpoint.proxy.postMessage(response.toString()) }
        }
    }
    private suspend fun dispatch(ext: Extension, ep: ExtensionEndpoint, method: String, args: JSONArray): Any? {
        when (method) {
            "runtime.__ready" -> { runtime.register(ep); return CapabilityRegistry.json() }
            "runtime.__backgroundReady" -> { runtime.backgroundReady(ep); return JSONObject.NULL }
            "runtime.__respond" -> return runtime.respond(ep, args, false)
            "runtime.__executionResult" -> return runtime.respond(ep, args, true)
        }
        check(method in CapabilityRegistry.methods) { "Unsupported Chrome API: $method" }
        val namespace = method.substringBefore('.')
        if (ep.kind == ExtensionContext.CONTENT) check(namespace in listOf("runtime", "storage", "i18n")) { "$namespace is unavailable in content scripts; message the background instead" }
        if (namespace in setOf("storage", "scripting", "cookies", "downloads", "notifications", "contextMenus", "history", "bookmarks")) runtime.permissions.require(ext, namespace)
        val name = method.substringAfterLast('.')
        val details = args.optJSONObject(0) ?: JSONObject()
        return when (namespace) {
            "runtime" -> when (name) {
                "getManifest" -> ext.manifest.json
                "getURL" -> ext.origin + "/" + args.optString(0).trimStart('/')
                "sendMessage" -> runtime.message(ep, args, false)
                "openOptionsPage" -> { val page = ext.manifest.options ?: error("No options page declared"); runtime.openPage(ext, page); JSONObject.NULL }
                "getPlatformInfo" -> JSONObject().put("os", "android").put("arch", "arm").put("nacl_arch", "arm")
                else -> error("Unsupported runtime API")
            }
            "storage" -> {
                check(ep.kind != ExtensionContext.CONTENT || method.substringAfter('.').substringBefore('.') != "session") { "Session storage is restricted to extension pages" }
                runtime.storage.call(ext.id, method.split('.')[1], name, args)
            }
            "permissions" -> {
                val before = runtime.permissions.granted(ext) to runtime.permissions.hosts(ext).map { it.value }.toSet()
                val result = runtime.permissions.call(ext, name, details)
                val after = runtime.permissions.granted(ext) to runtime.permissions.hosts(ext).map { it.value }.toSet()
                if (before != after) {
                    val added = name == "request"
                    val changed = JSONObject().put("permissions", JSONArray((if (added) after.first - before.first else before.first - after.first).toList()))
                        .put("origins", JSONArray((if (added) after.second - before.second else before.second - after.second).toList()))
                    runtime.emit(ext.id, if (added) "permissions.onAdded" else "permissions.onRemoved", JSONArray().put(changed))
                    runtime.refreshContent()
                }
                result
            }
            "cookies" -> runtime.cookies.call(ext, name, details)
            "scripting" -> runtime.scripting(ext, name, details)
            "tabs" -> if (name == "sendMessage") runtime.message(ep, args, true) else runtime.browser.call(ext, method, args)
            "action", "commands", "contextMenus", "notifications" -> runtime.ui.call(ext, method, args)
            else -> runtime.browser.call(ext, method, args)
        }
    }
}
