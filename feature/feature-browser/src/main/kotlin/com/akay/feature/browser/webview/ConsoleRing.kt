package com.akay.feature.browser.webview

import java.util.ArrayDeque

/**
 * Ring buffer of the last N console messages from the active page, fed by AxWebChromeClient's
 * onConsoleMessage. Powers the agent's browser_console_messages tool (browser_console_messages
 * in Playwright-MCP terms). Process-lifetime singleton; cleared on page navigation by the client.
 */
object ConsoleRing {

    private const val MAX = 200
    private val buffer = ArrayDeque<String>(MAX)

    /** Called from AxWebChromeClient.onConsoleMessage. */
    fun push(level: String, message: String, sourceId: String, line: Int) {
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            val src = sourceId.substringAfterLast('/').take(60)
            buffer.addLast("[$level] $message${if (src.isNotBlank()) " ($src:$line)" else ""}")
        }
    }

    /** Called on page load start so entries never bleed across pages. */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    fun recent(): String {
        val snapshot = synchronized(buffer) { buffer.toList() }
        if (snapshot.isEmpty()) return "No console messages captured on this page."
        return snapshot.joinToString("\n")
    }
}
