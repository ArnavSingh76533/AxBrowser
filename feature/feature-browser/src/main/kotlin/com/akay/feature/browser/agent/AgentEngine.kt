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
        - scrape_structured: {"item_selector": "CSS selector matching each repeated item/card/row",
            "fields": {"fieldName": "selector relative to the item, optionally '@attr' to pull an attribute"}}
          - reverse-engineers a listing into structured records in one call, e.g. for search results:
            {"item_selector": "div.result", "fields": {"title": "h3", "url": "a@href", "price": "span.price"}}
          - use this instead of many single scrape calls whenever the page has a repeated list/grid/table of items.
        - run_js: {"code": "JS statements, e.g. return document.title;"} - last resort for anything the other
          tools can't do (reading obscure DOM state, computed values, etc). The code runs inside a function body.
        - get_network_requests: {"filter": "optional substring to match in the URL, e.g. 'm3u8' or 'api/video'"}
          - lists ALL requests the page has actually made (method, url, status, mime type, size) - like the dev
            console's network tab, including static assets (css/js/images/fonts).
        - find_api_requests: {"filter": "optional substring to match in the URL"}
          - like get_network_requests but pre-filtered to requests that look like actual backend/data calls
            (JSON/XHR/fetch/GraphQL/non-GET), with static assets stripped out. This is the right first move
            whenever the user asks what API/endpoint a site uses, or asks you to find "the request" behind
            some data on the page.
        - get_response_body: {"url_filter": "substring identifying the request, from get_network_requests/find_api_requests"}
          - returns the captured response body (truncated) for that request, to inspect the shape of an API's
            JSON payload before explaining or reproducing it.
        - get_curl: {"url_filter": "substring identifying the request, from get_network_requests/find_api_requests", "sanitized": false}
          - returns a complete, ready-to-run curl command for that exact request: correct method, full headers
            (including session cookies AND any Authorization/API-key/custom auth headers the page itself set,
            plus cookies pulled from the browser as a fallback when present), and request body/payload if any.
          - THIS is what to call whenever the user asks you to "give me this API", "give me the request/endpoint",
            "how do I call this in curl", etc. Always locate the exact request first via find_api_requests /
            get_network_requests (do not guess a URL), then call get_curl on it, then put the returned curl
            command verbatim in your final_answer inside a code block. Briefly note the payload/body content
            type (e.g. JSON, form-encoded) and any header the user should know is sensitive (auth token, cookie)
            since it's tied to their own logged-in session and shouldn't be shared publicly.
          - set "sanitized": true instead when the user wants to document/share/publish the request (e.g. in a
            bug report or a README) rather than actually run it - this masks cookie/token/api-key header values
            with a placeholder so nothing live leaks.
        - find_auth_flow: {} - traces which captured request(s) established the session (set a cookie, or returned
          a token/access_token-shaped field) and which later requests depend on that auth. Call this when the user
          asks "how does login work here" / "what token does this site use" / before reproducing a flow that needs
          to log in first, instead of guessing which request matters.
        - diff_requests: {"filter_a": "substring for request A", "filter_b": "substring for request B"}
          - compares two captured requests to the same-shaped endpoint (e.g. page 1 vs page 2 of results, or two
            different filter states) and reports exactly which query params / headers / body differ. This is the
            right move whenever the user asks "what changes between these two calls" or you need to isolate a
            pagination cursor or filter param instead of eyeballing two long requests yourself.
        - infer_schema: {"url_filter": "substring identifying the request"}
          - infers a rough typed data-class shape from a captured JSON response body. Use when the user wants a
            typed model/interface for an API's response, not just the raw curl/JSON.
        - get_graphql_queries: {"filter": "optional substring to match in the URL"}
          - GraphQL-specific view: extracts and pretty-prints the operation name, the query/mutation document, and
            variables from captured GraphQL POST bodies. Use this instead of get_curl/get_response_body when
            find_api_requests shows a POST to a URL containing "/graphql" - a plain curl view just shows one
            opaque JSON blob and hides the actual operation being called.
        - export_har: {} - writes everything captured so far to a HAR file on device and returns its path, for
          the user to load into Chrome DevTools / Postman / Insomnia / Charles when they want the whole session,
          not one request at a time.
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
        // Verbatim get_curl results captured this run (unsanitized only - sanitized ones are
        // *meant* to have placeholders). Small free models frequently "helpfully" retype a
        // captured curl with the cookie/token swapped for something like <YOUR_TOKEN> even when
        // told explicitly not to, because it pattern-matches as a secret worth redacting. Since
        // we already have the exact right string from the tool call itself, the fix isn't to
        // trust the model's transcription - it's to splice the real one back in below.
        val realCurlBlocks = mutableListOf<String>()
        repeat(maxSteps) { step ->
            val result = client.chat(apiKey, model, history)
            val raw = result.getOrElse {
                // Surface the actual failure (HTTP status/body from OpenRouterClient, or the raw
                // exception) instead of a generic "request failed" - this was the #1 confusing
                // error users hit, since the real cause (bad key, rate limit, model down, no
                // network) was being swallowed.
                val detail = it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString()
                onEvent(AgentEvent.Error("Model request failed: $detail"))
                return
            }
            history += ChatTurn("assistant", raw)

            var json = extractJson(raw)
            if (json == null) {
                // The model replied with something that isn't valid JSON (prose, a broken/partial
                // object, markdown fences it was told not to use, etc). Instead of giving up
                // immediately, show the user what the model actually said and give the model one
                // chance to correct itself before stopping for real.
                onEvent(AgentEvent.ToolResult("Model reply wasn't valid JSON, asking it to correct itself. Raw reply was:\n${raw.take(1500)}"))
                history += ChatTurn(
                    "user",
                    "Your last reply wasn't a single valid JSON object as instructed. Reply again with ONLY the JSON object described in the system prompt - no prose, no markdown fences."
                )
                val retryResult = client.chat(apiKey, model, history)
                val retryRaw = retryResult.getOrElse {
                    val detail = it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString()
                    onEvent(AgentEvent.Error("Model request failed on retry: $detail"))
                    return
                }
                history += ChatTurn("assistant", retryRaw)
                json = extractJson(retryRaw)
                if (json == null) {
                    onEvent(AgentEvent.Error(
                        "Model didn't return a valid action after a retry, stopping. Its raw (non-JSON) reply was:\n\n${retryRaw.take(2000)}"
                    ))
                    return
                }
            }

            val thought = json.optString("thought")
            if (thought.isNotBlank()) onEvent(AgentEvent.Thinking(thought))

            val action = json.optString("action")
            val input = json.optJSONObject("action_input") ?: JSONObject()

            if (action == "final_answer") {
                val modelText = input.optString("text").ifBlank { thought }
                val finalText = if (realCurlBlocks.isEmpty()) modelText else injectRealCurl(modelText, realCurlBlocks)
                onEvent(AgentEvent.FinalAnswer(finalText))
                return
            }

            onEvent(AgentEvent.ToolCall(action, input.toString()))
            val observation = runCatching { execute(action, input) }.getOrElse { "Error: ${it.message}" }
            if (action == "get_curl" && !input.optBoolean("sanitized", false) && observation.startsWith("curl")) {
                realCurlBlocks += observation
            }
            onEvent(AgentEvent.ToolResult(observation))
            history += ChatTurn("user", "Observation: $observation")
        }
        onEvent(AgentEvent.Error("Stopped after $maxSteps steps without a final answer."))
    }

    /** Replaces whatever code block(s) the model wrote with the real, verbatim get_curl
     *  output(s) from this run - guaranteeing the user always gets a runnable command with the
     *  actual captured cookie/token/session values, never a model-invented `<YOUR_TOKEN>`
     *  placeholder, regardless of how faithfully the underlying model transcribed it. Only
     *  applies to unsanitized get_curl results; sanitized ones are supposed to have placeholders. */
    private fun injectRealCurl(modelText: String, realCurlBlocks: List<String>): String {
        val alreadyVerbatim = realCurlBlocks.all { modelText.contains(it) }
        if (alreadyVerbatim) return modelText
        val withoutCodeFences = modelText.replace(Regex("```[a-zA-Z]*\\n[\\s\\S]*?```"), "").trimEnd()
        val curlSection = realCurlBlocks.joinToString("\n\n") { "```bash\n$it\n```" }
        return buildString {
            append(withoutCodeFences)
            if (withoutCodeFences.isNotBlank()) append("\n\n")
            append(curlSection)
        }
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
        "scrape_structured" -> {
            val itemSelector = input.optString("item_selector")
            val fieldsObj = input.optJSONObject("fields") ?: JSONObject()
            val fields = fieldsObj.keys().asSequence().associateWith { fieldsObj.optString(it) }
            val results = tools.scrapeStructured(itemSelector, fields)
            if (results.isEmpty()) "No elements matched item_selector \"$itemSelector\"."
            else results.joinToString("\n") { record -> "- " + record.entries.joinToString(", ") { "${it.key}: ${it.value}" } }
        }
        "run_js" -> tools.runJs(input.optString("code")).take(4000)
        "get_network_requests" -> {
            val filter = input.optString("filter").ifBlank { null }
            val results = tools.getNetworkRequests(filter)
            if (results.isEmpty()) "No matching network requests captured yet."
            else results.joinToString("\n") { "- $it" }
        }
        "find_api_requests" -> {
            val filter = input.optString("filter").ifBlank { null }
            val results = tools.findApiRequests(filter)
            if (results.isEmpty()) "No API-like requests captured yet (try browsing/interacting with the page first, or widen the filter)."
            else results.joinToString("\n") { "- $it" }
        }
        "get_response_body" -> {
            val filter = input.optString("url_filter")
            tools.getResponseBody(filter) ?: "No captured response body found matching \"$filter\"."
        }
        "get_curl" -> {
            val filter = input.optString("url_filter")
            val sanitized = input.optBoolean("sanitized", false)
            tools.getCurlForRequest(filter, sanitized) ?: "No captured request found matching \"$filter\". Try find_api_requests or get_network_requests first to locate the exact request."
        }
        "find_auth_flow" -> tools.findAuthFlow()
        "diff_requests" -> {
            val a = input.optString("filter_a")
            val b = input.optString("filter_b")
            if (a.isBlank() || b.isBlank()) "Both \"filter_a\" and \"filter_b\" are required." else tools.diffRequests(a, b)
        }
        "infer_schema" -> {
            val filter = input.optString("url_filter")
            tools.inferSchema(filter)
        }
        "get_graphql_queries" -> {
            val filter = input.optString("filter").ifBlank { null }
            val results = tools.getGraphQlQueries(filter)
            if (results.isEmpty()) "No GraphQL requests captured yet (try find_api_requests to check for a /graphql endpoint first)."
            else results.joinToString("\n\n---\n\n")
        }
        "export_har" -> tools.exportHar()
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
