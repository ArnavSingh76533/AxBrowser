package com.akay.feature.browser.agent

/**
 * Everything the AI agent is allowed to do to the browser. Implemented by
 * BrowserScreen with closures over the live WebView/ViewModels so the agent
 * never touches WebView directly (and never off the main thread).
 */
interface AgentToolExecutor {
    suspend fun navigate(url: String)
    suspend fun searchAndOpen(engine: String, query: String)
    suspend fun getPageText(): String
    suspend fun getPageLinks(): List<AgentLink>
    suspend fun clickLinkContaining(text: String): Boolean
    suspend fun startDownload(url: String): String
    suspend fun currentUrl(): String
    suspend fun goBack(): Boolean
    suspend fun goForward(): Boolean

    /** CSS-selector scrape: text content, or a specific attribute (e.g. "href", "src") if given. */
    suspend fun scrape(selector: String, attribute: String?): List<String>

    /** Runs an arbitrary JS expression in the page and returns its string result - the agent's escape hatch. */
    suspend fun runJs(code: String): String

    /** Dev-console network log: requests the page has made, optionally filtered by a URL substring. */
    suspend fun getNetworkRequests(filter: String?): List<String>

    /** Direct video/audio URLs already detected on the page (network + DOM), often better than the page URL for downloads. */
    suspend fun getDetectedMedia(): List<String>

    suspend fun listTabs(): List<String>
}

data class AgentLink(val text: String, val href: String)

