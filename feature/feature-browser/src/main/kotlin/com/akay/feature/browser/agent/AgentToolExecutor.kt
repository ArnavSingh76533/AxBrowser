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
}

data class AgentLink(val text: String, val href: String)
