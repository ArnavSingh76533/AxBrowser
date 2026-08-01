package com.akay.feature.browser.agent

import com.akay.core.data.ai.ChatTurn
import com.akay.core.data.ai.OpenRouterClient
import org.json.JSONObject

sealed class AgentEvent {
    data class Thinking(val thought: String) : AgentEvent()
    data class ToolCall(val action: String, val input: String) : AgentEvent()
    data class ToolResult(val observation: String) : AgentEvent()
    data class FinalAnswer(val text: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

/**
 * Drives a small ReAct (reason+act) loop against any OpenRouter chat model.
 * Deliberately avoids relying on native "function calling" - support for it
 * is inconsistent across OpenRouter's free-tier models - and instead asks
 * the model to reply with one JSON object per turn describing the next
 * action, which is far more broadly compatible.
 */
class AgentEngine(
    private val client: OpenRouterClient,
    private val apiKey: String,
    private val model: String,
    private val tools: AgentToolExecutor
) {
    private val systemPrompt = """
        You are AxBrowser's in-app AI agent. You control a real mobile web browser for the user,
        including its dev-console network log, page scraping, and raw JS execution.
        On every turn you must reply with ONLY a single JSON object (no markdown fences, no prose outside it) shaped like:
        {"thought": "brief reasoning", "action": "<action name>", "action_input": { ... }}

        Actions:
        - navigate: {"url": "https://..."}
        - search: {"engine": "youtube" | "google", "query": "..."}
        - go_back: {} - browser back button
        - go_forward: {} - browser forward button
        - get_page_text: {} - the current page's visible text, for reading/summarizing
        - get_links: {} - visible links (text + href) on the current page
        - click_link: {"text": "substring of the link text to click"}
        - scrape: {"selector": "CSS selector", "attribute": "optional attribute name, e.g. href/src - omit for text content"}
          - a more precise, structured alternative to get_links/get_page_text, e.g. {"selector": "h1.title"} or
            {"selector": "video source", "attribute": "src"}
        - run_js: {"code": "JS statements, e.g. return document.title;"} - last resort for anything the other
          tools can't do (reading obscure DOM state, computed values, etc). The code runs inside a function body.
        - get_network_requests: {"filter": "optional substring to match in the URL, e.g. 'm3u8' or 'api/video'"}
          - lists requests the page has actually made (method, url, status, mime type, size) - like the dev console's
            network tab. Use this to find real media/API URLs that aren't visible in the page's HTML.
        - get_detected_media: {} - direct video/audio URLs already detected on the page (from network responses and
          <video>/<source>/<audio> tags). Often a better download target than the page URL itself, especially for
          sites that stream from a different domain than the page.
        - list_tabs: {} - titles + URLs of all open browser tabs
        - download: {"url": "the COMPLETE URL to send to the built-in downloader"}
        - final_answer: {"text": "your final reply to the user, plain text"}

        Every user message includes a line like "Current browser page: <url>" showing exactly what
        page is open right now. Use it directly:
        - If the user says "this video", "the current page", "what I'm watching", "download this", etc.,
          that means the page at "Current browser page" - use that URL immediately, do NOT ask the user
          for a link and do NOT call navigate/search first, they are already there.
        - Only call navigate/search when the user asks to go somewhere new or find something you don't
          already have the URL for.

        Rules for the "download" action's url field specifically:
        - It MUST always be a complete URL starting with "http://" or "https://" (e.g.
          "https://www.youtube.com/watch?v=dQw4w9WgXcQ"), covering the whole address.
        - NEVER pass just a bare video ID, slug, or partial path (e.g. never just "dQw4w9WgXcQ").
        - If you are unsure of the exact URL, first use navigate/search/get_links/click_link to actually
          land on that exact page, then read its real URL from the tool's "Current URL:" observation or
          from the "Current browser page" line, and use that full URL - never guess or shorten it.
        - If a normal page URL might not be directly downloadable (e.g. a page that streams from elsewhere),
          try get_detected_media or get_network_requests first to find the actual media file URL.

        Other rules:
        - Always take exactly one action per turn.
        - Use get_page_text, get_links, or scrape right after navigating/searching before assuming what's on the page.
        - When asked to find and download something (e.g. a YouTube video) that isn't already open, first
          search, then get_links/scrape to find the right result, click it, then call download with that
          exact page's full URL (or the URL from get_detected_media if the page itself isn't a direct file).
        - Prefer scrape over get_links/get_page_text when you need precise, structured data (e.g. a specific
          attribute), and reach for run_js only when nothing else can get what you need.
        - Finish with final_answer as soon as the user's request is satisfied, summarizing what you did.
        - Never invent URLs or page contents you haven't actually observed via a tool or the context line.
    """.trimIndent()

    private val history = mutableListOf(ChatTurn("system", systemPrompt))

    suspend fun run(userGoal: String, maxSteps: Int = 9, onEvent: suspend (AgentEvent) -> Unit) {
        val currentPage = runCatching { tools.currentUrl() }.getOrDefault("")
        val contextualGoal = if (currentPage.isNotBlank()) {
            "Current browser page: $currentPage\n\nUser request: $userGoal"
        } else {
            userGoal
        }
        history += ChatTurn("user", contextualGoal)
        repeat(maxSteps) { step ->
            val result = client.chat(apiKey, model, history)
            val raw = result.getOrElse {
                onEvent(AgentEvent.Error(it.message ?: "Model request failed"))
                return
            }
            history += ChatTurn("assistant", raw)

            val json = extractJson(raw)
            if (json == null) {
                onEvent(AgentEvent.Error("Model didn't return a valid action, stopping."))
                return
            }

            val thought = json.optString("thought")
            if (thought.isNotBlank()) onEvent(AgentEvent.Thinking(thought))

            val action = json.optString("action")
            val input = json.optJSONObject("action_input") ?: JSONObject()

            if (action == "final_answer") {
                onEvent(AgentEvent.FinalAnswer(input.optString("text").ifBlank { thought }))
                return
            }

            onEvent(AgentEvent.ToolCall(action, input.toString()))
            val observation = runCatching { execute(action, input) }.getOrElse { "Error: ${it.message}" }
            onEvent(AgentEvent.ToolResult(observation))
            history += ChatTurn("user", "Observation: $observation")
        }
        onEvent(AgentEvent.Error("Stopped after $maxSteps steps without a final answer."))
    }

    private suspend fun execute(action: String, input: JSONObject): String = when (action) {
        "navigate" -> {
            val url = input.optString("url")
            tools.navigate(url)
            "Navigated to $url. Current URL: ${tools.currentUrl()}"
        }
        "search" -> {
            val engine = input.optString("engine", "google")
            val query = input.optString("query")
            tools.searchAndOpen(engine, query)
            "Opened $engine search for \"$query\". Current URL: ${tools.currentUrl()}"
        }
        "get_page_text" -> tools.getPageText().take(4000)
        "get_links" -> {
            val links = tools.getPageLinks().take(40)
            if (links.isEmpty()) "No links found on the page."
            else links.joinToString("\n") { "- ${it.text} -> ${it.href}" }
        }
        "click_link" -> {
            val text = input.optString("text")
            val clicked = tools.clickLinkContaining(text)
            if (clicked) "Clicked link containing \"$text\". Current URL: ${tools.currentUrl()}"
            else "No link containing \"$text\" was found."
        }
        "go_back" -> {
            val moved = tools.goBack()
            if (moved) "Went back. Current URL: ${tools.currentUrl()}" else "Can't go back further."
        }
        "go_forward" -> {
            val moved = tools.goForward()
            if (moved) "Went forward. Current URL: ${tools.currentUrl()}" else "Can't go forward further."
        }
        "scrape" -> {
            val selector = input.optString("selector")
            val attribute = input.optString("attribute").ifBlank { null }
            val results = tools.scrape(selector, attribute)
            if (results.isEmpty()) "No elements matched selector \"$selector\"."
            else results.joinToString("\n") { "- $it" }
        }
        "run_js" -> tools.runJs(input.optString("code")).take(4000)
        "get_network_requests" -> {
            val filter = input.optString("filter").ifBlank { null }
            val results = tools.getNetworkRequests(filter)
            if (results.isEmpty()) "No matching network requests captured yet."
            else results.joinToString("\n") { "- $it" }
        }
        "get_detected_media" -> {
            val results = tools.getDetectedMedia()
            if (results.isEmpty()) "No direct media URLs detected on this page yet."
            else results.joinToString("\n") { "- $it" }
        }
        "list_tabs" -> {
            val tabs = tools.listTabs()
            if (tabs.isEmpty()) "No open tabs." else tabs.joinToString("\n") { "- $it" }
        }
        "download" -> tools.startDownload(input.optString("url").ifBlank { tools.currentUrl() })
        else -> "Unknown action \"$action\"."
    }

    private fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }
}
