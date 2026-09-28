package com.akay.feature.browser.extensions.api

import org.json.JSONObject

/** Method-level registry is also the dispatcher allowlist. Never silently reports unsupported work as success. */
object CapabilityRegistry {
    val methods = mapOf(
        "runtime" to "getManifest getURL sendMessage openOptionsPage getPlatformInfo",
        "storage.local" to "get set remove clear getBytesInUse",
        "storage.session" to "get set remove clear getBytesInUse",
        "tabs" to "query get create update remove reload sendMessage",
        "scripting" to "executeScript insertCSS",
        "cookies" to "get getAll set remove",
        "windows" to "get getCurrent getLastFocused getAll",
        "downloads" to "download search pause resume cancel",
        "notifications" to "create clear",
        "contextMenus" to "create update remove removeAll",
        "permissions" to "getAll contains request remove",
        "i18n" to "getMessage getUILanguage",
        "action" to "setBadgeText getBadgeText setTitle getTitle setPopup getPopup setBadgeBackgroundColor",
        "commands" to "getAll",
        "history" to "search addUrl deleteUrl deleteAll",
        "bookmarks" to "get search getTree create update remove"
    ).flatMap { (namespace, methods) -> methods.split(' ').map { "$namespace.$it" to namespace.substringBefore('.') } }.toMap()
    val permissions = setOf("storage", "tabs", "scripting", "cookies", "webNavigation", "downloads", "notifications", "contextMenus", "history", "bookmarks")
    fun json() = JSONObject().apply {
        methods.keys.forEach { put(it, "partial") }
        put("storage.sync", "unsupported: use local; no cloud sync")
        put("background.service_worker", "emulated while browser is open; no wake after process death")
        put("content_scripts", "isolated top frame; requires WebView JS_INJECTION_IN_FRAME_AND_WORLD")
        put("webNavigation", "top-frame before/complete/error events")
        put("webRequest", "unsupported")
        put("declarativeNetRequest", "unsupported")
        put("nativeMessaging", "unsupported")
        put("activeTab", "unsupported: scripting requires an approved host_permissions entry")
        put("web_accessible_resources", "private to extension contexts; public website access unsupported")
    }
}
