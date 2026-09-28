package com.akay.feature.browser.extensions.api

import android.webkit.URLUtil
import com.akay.core.domain.model.Bookmark
import com.akay.core.domain.model.HistoryItem
import com.akay.core.domain.model.Tab
import com.akay.core.domain.repository.BookmarkRepository
import com.akay.core.domain.repository.HistoryRepository
import com.akay.core.domain.repository.TabRepository
import com.akay.feature.browser.extensions.Extension
import com.akay.feature.browser.extensions.MatchPattern
import com.akay.feature.downloads.viewmodel.DownloadViewModel
import com.akay.feature.downloads.viewmodel.ItemStatus
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Adapter to the existing repositories and the existing download ViewModel; no parallel database/engine. */
class BrowserApis(val tabs: TabRepository, private val history: HistoryRepository, private val bookmarks: BookmarkRepository) {
    var downloads: DownloadViewModel? = null
    var reload: ((String) -> Unit)? = null
    var granted: (Extension) -> Set<String> = { it.manifest.permissions }
    var hostPatterns: (Extension) -> List<MatchPattern> = { it.manifest.hosts }
    private val ids = mutableMapOf<String, Int>()
    fun number(id: String): Int = ids.getOrPut(id) { ids.size + 1 }
    fun key(id: Int): String = ids.entries.firstOrNull { it.value == id }?.key ?: error("No such tab/download: $id")
    suspend fun tab(id: Int): Tab = tabs.getTab(key(id))?.takeUnless { it.isIncognito } ?: error("No such accessible tab")
    suspend fun current(): Tab = tabs.getAllTabs().first().firstOrNull { it.isActive && !it.isIncognito } ?: error("No active non-private tab")
    fun tabJson(tab: Tab, ext: Extension): JSONObject = JSONObject().put("id", number(tab.id)).put("windowId", 1)
        .put("active", tab.isActive).put("incognito", false).put("index", 0).put("status", "complete").apply {
            if ("tabs" in granted(ext) || hostPatterns(ext).any { it.matches(tab.url) }) {
                put("url", tab.url); put("title", tab.title); put("favIconUrl", tab.faviconUrl)
            }
        }
    suspend fun call(ext: Extension, method: String, args: JSONArray): Any? {
        val details = args.optJSONObject(0) ?: JSONObject()
        if (method == "tabs.query") check(details.keys().asSequence().all { it in setOf("active", "currentWindow", "lastFocusedWindow", "windowId", "url", "incognito") }) { "Unsupported tab query filter" }
        if (method == "downloads.search") check(details.keys().asSequence().all { it == "id" }) { "Only download searches by ID, or an empty query, are supported" }
        return when (method) {
            "tabs.query" -> JSONArray(tabs.getAllTabs().first().filter { t -> !t.isIncognito &&
                !details.optBoolean("incognito") &&
                (!details.has("active") || t.isActive == details.getBoolean("active")) &&
                (!details.has("windowId") || details.getInt("windowId") in listOf(1, -2)) &&
                (!details.has("url") || run {
                    require("tabs" in granted(ext)) { "tabs permission required to query URLs" }
                    val patterns = if (details.opt("url") is JSONArray) details.getJSONArray("url") else JSONArray().put(details.getString("url"))
                    (0 until patterns.length()).any { MatchPattern(patterns.getString(it)).matches(t.url) }
                })
            }.map { tabJson(it, ext) })
            "tabs.get" -> tabJson(tab(args.getInt(0)), ext)
            "tabs.create" -> {
                require(!details.optBoolean("incognito")) { "Extensions cannot access private tabs" }
                val url = details.optString("url", "about:blank"); safeUrl(url, blank = true)
                val tab = Tab(url = url, isActive = details.optBoolean("active", true))
                tabs.createTab(tab); if (tab.isActive) tabs.setActiveTab(tab.id)
                tabJson(tab, ext)
            }
            "tabs.update" -> {
                val target = if (args.opt(0) is Number) tab(args.getInt(0)) else current()
                val changes = if (args.opt(0) is Number) args.getJSONObject(1) else details
                require(!changes.has("pinned") && !changes.has("muted")) { "Pinned/muted tabs are unsupported" }
                val updated = if (changes.has("url")) target.copy(url = changes.getString("url").also { safeUrl(it, true) }) else target
                tabs.updateTab(updated)
                if (changes.optBoolean("active")) tabs.setActiveTab(updated.id)
                tabJson(updated, ext)
            }
            "tabs.remove" -> {
                val list = args.optJSONArray(0) ?: JSONArray().put(args.getInt(0))
                for (i in 0 until list.length()) tabs.deleteTab(tab(list.getInt(i)).id)
                val remaining = tabs.getAllTabs().first()
                if (remaining.none { it.isActive } && remaining.isNotEmpty()) tabs.setActiveTab(remaining.first().id)
                JSONObject.NULL
            }
            "tabs.reload" -> { val target = if (args.opt(0) is Number) tab(args.getInt(0)) else current(); (reload ?: error("Browser view is not available"))(target.id); JSONObject.NULL }
            "windows.get", "windows.getCurrent", "windows.getLastFocused", "windows.getAll" -> {
                if (method == "windows.get") require(args.getInt(0) in listOf(1, -2)) { "No such window" }
                val window = JSONObject().put("id", 1).put("focused", true).put("incognito", false).put("type", "normal").put("state", "normal")
                if (details.optBoolean("populate") || args.optJSONObject(1)?.optBoolean("populate") == true) window.put("tabs", JSONArray(tabs.getAllTabs().first().filterNot { it.isIncognito }.map { tabJson(it, ext) }))
                if (method == "windows.getAll") JSONArray().put(window) else window
            }
            "downloads.download" -> {
                val url = details.getString("url"); safeUrl(url)
                require(!details.has("headers") && !details.has("body") && details.optString("method", "GET") == "GET" && !details.optBoolean("saveAs")) { "Custom download headers, body, method and saveAs are unsupported" }
                val filename = details.optString("filename").ifBlank { URLUtil.guessFileName(url, null, null) }
                require(filename.isNotBlank() && !filename.contains('/') && !filename.contains('\\') && !filename.contains(':') && filename !in listOf(".", "..")) { "Use a filename without directories" }
                number((downloads ?: error("Download manager unavailable")).enqueue(url, filename, useYtDlp = false))
            }
            "downloads.search" -> JSONArray((downloads ?: error("Download manager unavailable")).state.value.downloads.filter { !details.has("id") || number(it.id) == details.getInt("id") }.map {
                JSONObject().put("id", number(it.id)).put("url", it.url).put("filename", it.displayName).put("paused", it.status == ItemStatus.PAUSED)
                    .put("state", when (it.status) { ItemStatus.COMPLETED -> "complete"; ItemStatus.FAILED, ItemStatus.CANCELLED -> "interrupted"; else -> "in_progress" })
            })
            "downloads.pause", "downloads.resume", "downloads.cancel" -> {
                val vm = downloads ?: error("Download manager unavailable"); val id = key(args.getInt(0))
                require(vm.state.value.downloads.any { it.id == id }) { "No such download" }
                when (method) { "downloads.pause" -> vm.pause(id); "downloads.resume" -> vm.resume(id); else -> vm.cancel(id) }; JSONObject.NULL
            }
            "history.search" -> JSONArray(history.getAllHistory().first().filter {
                val text = details.optString("text")
                (it.url.contains(text, true) || it.title.orEmpty().contains(text, true)) && it.lastVisited >= details.optLong("startTime", System.currentTimeMillis() - 86400000) && it.lastVisited <= details.optLong("endTime", Long.MAX_VALUE)
            }.take(details.optInt("maxResults", 100).coerceIn(0, 10000)).map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("lastVisitTime", it.lastVisited).put("visitCount", it.visitCount) })
            "history.addUrl" -> { val url = details.getString("url"); safeUrl(url); history.addHistoryItem(HistoryItem(url = url)); JSONObject.NULL }
            "history.deleteUrl" -> { history.getAllHistory().first().filter { it.url == details.getString("url") }.forEach { history.deleteHistoryItem(it.id) }; JSONObject.NULL }
            "history.deleteAll" -> { history.deleteAllHistory(); JSONObject.NULL }
            "bookmarks.get" -> {
                val list = args.optJSONArray(0) ?: JSONArray().put(args.getString(0))
                JSONArray((0 until list.length()).map { bookmarkJson(bookmarks.getBookmark(list.getString(it)) ?: error("Bookmark not found")) })
            }
            "bookmarks.search" -> {
                val query = args.optString(0, "")
                require(args.opt(0) is String) { "Use a text bookmark search" }
                JSONArray(bookmarks.getAllBookmarks().first().filter { it.url.contains(query, true) || it.title.contains(query, true) }.map(::bookmarkJson))
            }
            "bookmarks.getTree" -> {
                val all = bookmarks.getAllBookmarks().first(); val folders = bookmarks.getAllFolders().first()
                fun children(parent: String?, visited: Set<String>): JSONArray = JSONArray(
                    all.filter { it.folderId == parent }.map(::bookmarkJson) + folders.filter { it.parentId == parent && it.id !in visited }.map {
                        JSONObject().put("id", it.id).put("title", it.name).put("parentId", it.parentId ?: "0").put("children", children(it.id, visited + it.id))
                    })
                JSONArray().put(JSONObject().put("id", "0").put("title", "").put("children", children(null, emptySet())))
            }
            "bookmarks.create" -> {
                require(details.has("url")) { "Folder creation is unsupported" }
                val url = details.getString("url"); safeUrl(url)
                val parent = details.optString("parentId", "0").takeUnless { it == "0" }
                require(parent == null || bookmarks.getAllFolders().first().any { it.id == parent }) { "Bookmark folder not found" }
                val bookmark = Bookmark(url = url, title = details.optString("title"), folderId = parent)
                bookmarks.addBookmark(bookmark); bookmarkJson(bookmark)
            }
            "bookmarks.update" -> {
                val old = bookmarks.getBookmark(args.getString(0)) ?: error("Bookmark not found")
                val change = args.getJSONObject(1)
                val next = old.copy(title = change.optString("title", old.title), url = change.optString("url", old.url).also { safeUrl(it) })
                bookmarks.updateBookmark(next); bookmarkJson(next)
            }
            "bookmarks.remove" -> { val id = args.getString(0); require(bookmarks.getBookmark(id) != null) { "Bookmark not found" }; bookmarks.deleteBookmark(id); JSONObject.NULL }
            else -> error("Unsupported Chrome API: $method")
        }
    }
    private fun bookmarkJson(item: Bookmark) = JSONObject().put("id", item.id).put("url", item.url).put("title", item.title).put("parentId", item.folderId ?: "0").put("dateAdded", item.createdAt)
    companion object {
        fun safeUrl(url: String, blank: Boolean = false) {
            if (blank && url == "about:blank") return
            val uri = URI(url)
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && !uri.host.endsWith(".ax-extension.invalid")) { "Only public HTTP(S) navigation is supported" }
        }
    }
}
