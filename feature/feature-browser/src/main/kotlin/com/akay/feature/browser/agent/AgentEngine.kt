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
    private val tools: AgentToolExecutor,
    private val baseUrl: String = "https://openrouter.ai/api/v1"
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
        - find_api_requests: {"filter": "optional substring to match in the URL", "method": "optional HTTP method to narrow to, e.g. 'POST'"}
          - like get_network_requests but pre-filtered to requests that look like actual backend/data calls
            (JSON/XHR/fetch/GraphQL/non-GET), with static assets AND CORS preflight (OPTIONS) requests
            stripped out - OPTIONS is never the real call, it's always an empty-bodied browser-generated
            check that happens right before the actual request. This is the right first move whenever the
            user asks what API/endpoint a site uses, or asks you to find "the request" behind some data on
            the page. Pass "method" when a URL has more than one (e.g. both GET and POST to the same path).
        - get_response_body: {"url_filter": "substring identifying the request, from get_network_requests/find_api_requests"}
          - returns the captured response body (truncated) for that request, to inspect the shape of an API's
            JSON payload before explaining or reproducing it.
        - get_curl: {"url_filter": "substring identifying the request, from get_network_requests/find_api_requests", "sanitized": false, "method": "optional HTTP method, e.g. 'POST', to disambiguate when the URL filter matches more than one request"}
          - returns a complete, ready-to-run curl command for that exact request: correct method, full headers
            (including session cookies AND any Authorization/API-key/custom auth headers the page itself set,
            plus cookies pulled from the browser as a fallback when present), and request body/payload if any
            (pretty-printed JSON). OPTIONS preflight is automatically skipped in favor of the real request, so
            you never need to filter it out yourself. The tool result may include NOTE lines - always pass those
            through to the user verbatim (don't summarize them away), especially: a missing body (some sites
            issue the real fetch() from inside a Web Worker, which this app cannot see into at all - not a bug,
            a hard platform limitation) and anti-replay signals (proof-of-work/nonce/signature-looking headers,
            which mean the exact command may stop working shortly after capture even though it looks complete -
            that's the site's own anti-automation design, not something more capture effort would fix).
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
        - export_postman: {} - like export_har but as a Postman Collection v2.1 file, better when the user wants
          to keep building on a discovered API in Postman/Insomnia rather than just replaying one call.
        - get_cookies: {} - lists every cookie set for the current page's domain, name and value. Use this when
          the user specifically wants to see cookies rather than a full curl command.
        - find_rate_limits: {} - scans captured response headers for rate-limit signals (X-RateLimit-*,
          RateLimit-*, Retry-After) and summarizes what the API's real limits look like.
        - list_endpoints: {} - de-duplicated, grouped summary of every distinct method+path this session, with
          call counts and an example URL for each. Use this FIRST when the user asks "what API does this site
          use" or "what endpoints does it have" - it's the overview; find_api_requests/get_curl are for a
          specific one you've already identified.
        - detect_pagination: {} - looks across every endpoint called 3+ times this session and reports which
          query param actually changed between calls (candidate pagination/cursor). Use this instead of
          diff_requests when you don't already have two specific requests picked out to compare.
        - click_element: {"selector": "a CSS selector, e.g. 'button.submit' or '#login-btn'"}
          - clicks the first matching element. Prefer click_link for plain <a> links (matches by visible text,
            more forgiving); use this for buttons/anything needing a real CSS selector.
        - type_text: {"selector": "CSS selector for an input/textarea", "text": "text to type"}
          - fills a form field the way a real user would (dispatches input/change events, so React/Vue-controlled
            forms pick it up, not just raw DOM).
        - scroll_to: {"selector": "optional CSS selector to scroll into view", "pixels": "optional number of px to scroll by if no selector"}
          - use before clicking something off-screen, or repeatedly to trigger infinite-scroll/lazy-loaded content
            you need to see in the network log.
        - wait_for_element: {"selector": "CSS selector to wait for"}
          - polls up to ~5s for something to appear before you act on it, for pages where the next step depends on
            something finishing loading first.
        - take_screenshot: {} - saves a PNG of the current page to device storage and returns the file path, to
          give the user visual confirmation of a result. This does NOT let you (the model) see the image yourself.
        - switch_tab: {"filter": "substring to match against an open tab's title or URL"}
          - switches to and waits on an already-open tab (see list_tabs) - use to act on/inspect a different
            tab (e.g. one a link opened in a popup) without losing your place in this one.
        - save_request: {"label": "short name to save it under", "curl": "the exact curl string from a prior get_curl call"}
          - bookmarks a curl command for later recall (get_saved_request) instead of it only existing in this
            one answer. Use after get_curl when the user says things like "save that" / "remember this request".
        - list_saved_requests: {} - every previously saved request (label, domain, when saved).
        - get_saved_request: {"label": "the label it was saved under"}
        - remember_site_note: {"note": "a short fact worth remembering about this site for NEXT time"}
          - persists across chat sessions, not just this run - e.g. "login form's real field name is 'user_login'
            not 'username'" or "API 403s without an X-Client-Version header". Relevant notes for the current
            domain are automatically added to your context at the start of a run, so you don't need to call
            recall_site_notes yourself unless the user explicitly asks what's remembered about a site.
        - recall_site_notes: {"domain": "optional - defaults to the current page's domain"}
        - watch_page: {"label": "short name for this watch", "url": "the URL to check", "interval_minutes": "how often, minimum/default 15-60"}
          - schedules a recurring BACKGROUND check (works even after this chat closes) that notifies the user
            when the page's raw HTML content changes. This is a plain HTTP fetch, not a live render - it won't
            catch changes that only appear after client-side JS runs, so say so if the user's ask depends on
            JS-rendered content specifically.
        - list_watches: {} - every active background watch.
        - cancel_watch: {"label": "the watch's label"}
        - export_openapi: {} - writes an inferred OpenAPI 3.0 document from every API-like request captured this
          session (grouped by path, with best-guess parameter/body shapes merged across every call to each
          endpoint) and returns its path. Tell the user it's inferred from observed traffic, not the site's real
          spec, so field types and required-ness are guesses to verify.
        - beautify_js: {"url_or_code": "a URL to fetch and reformat, OR raw JS code to reformat directly"}
          - reformats minified/obfuscated JS into readable indented form. This is bracket/statement-based
            reformatting only (adds line breaks and indentation) - NOT a real parser or a deobfuscator, it can't
            undo variable renaming or control-flow obfuscation. Useful for a first readable look at a worker
            script or bundle (e.g. one found via find_auth_flow or a captured Worker's source) before deciding
            whether it's worth understanding further. Always pass the tool's own NOTE about this limitation
            through to the user rather than presenting the output as a real deobfuscation.
        - list_files: {"category": "optional: screenshots | downloads | agent | other, default agent"}
          - lists filenames already saved in that category (the same storage every save/export tool uses -
            whether that's a user-visible folder or app-private storage depends on whether the person has set
            one up in Settings > Storage, but you don't need to know or care which - just use the tool).
        - read_file: {"category": "optional, default agent", "filename": "exact filename from list_files"}
          - reads back a previously saved TEXT file (exports, saved scripts/notes - not screenshots, those
            aren't text). Returns null/not-found if it doesn't exist.
        - write_file: {"category": "optional, default agent", "filename": "name to save as, e.g. script.py or notes.txt", "content": "the text to save"}
          - saves text as an actual file the user can find later - use this whenever the user asks you to save/
            write/create a script, a note, generated code, or any other text output as a file rather than just
            answering in chat. Overwrites a same-named file rather than duplicating it.
        - get_detected_media: {} - direct video/audio URLs already detected on the page (from network responses and
          <video>/<source>/<audio> tags). Often a better download target than the page URL itself, especially for
          sites that stream from a different domain than the page.
        - list_tabs: {} - titles + URLs of all open browser tabs
        - download: {"url": "the COMPLETE URL to send to the built-in downloader"}

        Planning and handoff (v8 - use these on every non-trivial task):
        - todo_write: {"items": [{"text": "short imperative step", "status": "pending|in_progress|done"}]}
          - THE PLAN. For any request needing more than about three actions, write the task list
            BEFORE you start working, then keep it honest as you go: at most one item in_progress,
            flip an item to done the moment it actually lands, and rewrite the list when reality
            diverges from the plan. It is rendered live in the chat, so it is how the user sees
            progress. Calling it REPLACES the whole list - send every item, not just the changed one.
        - todo_read: {} - read the current plan back if you need to re-check it.
        - ask_user: {"question": "what you need from the person, and exactly what they should do"}
          - Ends your turn and waits. Use it the moment only a human can proceed: a captcha your
            tools could not clear, an OTP/2FA code, a login, a payment confirmation, or a choice
            between two paths. The conversation is preserved, so when the user replies you continue
            exactly where you left off - never claim you are stuck without asking first.
        - browser_tap: {"x": 120, "y": 480} - a REAL touch tap at viewport CSS coordinates.
          - The only way into a cross-origin iframe or a canvas app: captcha checkboxes, embedded
            widgets, map pins, custom sliders. Use browser_snapshot + browser_click for normal controls.

        Captcha tools:
        - captcha_detect: {} - every captcha on the page: provider, type, sitekey, whether it is
          already solved, and the tap coordinates of its checkbox. Call this before deciding you
          are blocked; many challenges clear with a tap alone.
        - captcha_solve: {"use_solver": true, "timeout_sec": 120}
          - Native-taps each checkbox, and (when use_solver is true and a solver API key is
            configured) requests a token and injects it into the provider's response field. Reports
            per widget: cleared / still challenging / needs a human. Re-check with captcha_detect
            rather than assuming success, and when it says a human is needed, call ask_user instead
            of retrying the same solve in a loop.

        Playwright-shaped browser tools (prefer these to writing your own selectors):
        - browser_snapshot: {"max_nodes": 150} - compact list of the page's interactive elements
          with stable refs (s12-style). ALWAYS start an interaction flow with this.
        - browser_click: {"ref": "s12"}
        - browser_type: {"ref": "s7", "text": "...", "submit": true}
        - browser_fill_form: {"fields": [{"ref": "s7", "text": "alice"}]} - several fields, one call
        - browser_select_option: {"ref": "s9", "values": ["US"]}
        - browser_hover: {"ref": "s4"}
        - browser_press_key: {"ref": "s7", "key": "Enter"} - ref optional, defaults to the focused element
        - browser_wait_for: {"text": "Welcome", "time_ms": 5000} - or text_gone, or a plain wait
        - browser_handle_dialog: {"accept": true, "prompt_text": "optional"} - answers an
          alert/confirm/prompt that is blocking the page
        - browser_tabs: {"action": "list|new|close|select", "index": 0}
        - browser_console_messages: {} - recent console log/warn/error lines, i.e. client-side
          errors the network log cannot show you
        - Never invent a CSS selector: snapshot, read the ref, act on the ref. If an action reports
          a stale ref, take a fresh snapshot.

        Request and pentest tools (always available, any host, no confirmation needed):
        - http_send: {"raw_request": "POST /path HTTP/1.1\nHost: example.com\n\nbody=1", "include_cookies": true, "follow_redirects": false}
          - actually SENDS the request through the raw-socket sender: custom verbs, exact header
            order, and deliberately malformed requests included, with status/headers/body back.
            This is the real "curl, but it runs". Get the request's shape from a capture first.
        - encode: {"transform": "url|base64|hex|html|unicode|rot13|md5|sha1|sha256|gzip", "input": "..."}
        - decode: {"transform": "...", "input": "..."} - same transforms, opposite direction
        - jwt_decode: {"token": "eyJ..."} - header/payload, alg:none, expiry analysis
        - dump_storage: {} - localStorage + sessionStorage + cookies for the current origin
        - audit_security_headers: {} - passive scorecard (CSP/HSTS/XFO/CORS/cookies) for the origin
        - extract_js_endpoints: {"max_endpoints": 100} - LinkFinder-style endpoint discovery in the
          site's own JavaScript
        - toggle_intercept: {"enable": true} - hold fetch/XHR calls in the Intercept tab so they can
          be edited or dropped before they go out
        - set_match_replace: {"type": "REPLACE_REQ_HEADER|REPLACE_REQ_BODY|REPLACE_RESP_BODY", "match": "...", "replace": "...", "is_regex": false}
        - send_to_repeater: {"url_filter": "substring of a captured request"} - hands it to Repeater

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

    suspend fun run(userGoal: String, maxSteps: Int = 25, onEvent: suspend (AgentEvent) -> Unit) {
        val currentPage = runCatching { tools.currentUrl() }.getOrDefault("")
        val siteNotes = if (currentPage.isNotBlank()) {
            runCatching { tools.recallSiteNotes(null) }.getOrDefault(emptyList())
        } else emptyList()
        val notesBlock = if (siteNotes.isNotEmpty()) {
            "\n\nThings remembered about this site from previous visits:\n" + siteNotes.joinToString("\n") { "- $it" }
        } else ""
        val contextualGoal = if (currentPage.isNotBlank()) {
            "Current browser page: $currentPage$notesBlock\n\nUser request: $userGoal"
        } else {
            userGoal
        }
        history += ChatTurn("user", contextualGoal)
        // Verbatim tool output captured this run that must survive into final_answer unmangled -
        // get_curl (unsanitized), get_graphql_queries, infer_schema. Small free models frequently
        // "helpfully" retype these with values swapped for placeholders, or paraphrase/truncate a
        // long JSON/schema block, even when told explicitly not to. Since we already have the
        // exact right string from the tool call itself, the fix isn't to trust the model's
        // transcription - it's to splice the real one back in below.
        val verbatimBlocks = mutableListOf<String>()
        var lastUsefulObservation: String? = null
        var lastActionSignature: String? = null
        var repeatCount = 0
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        val toolsUsed = mutableListOf<String>()
        repeat(maxSteps) { step ->
            val stepsLeft = maxSteps - step
            if (stepsLeft == 2) {
                history += ChatTurn(
                    "user",
                    "You have 2 steps left. If you already have enough information to answer, call final_answer now with your best current findings instead of continuing to explore."
                )
            }
            val result = client.chat(apiKey, model, history, baseUrl = baseUrl)
            val chatResult = result.getOrElse {
                // Surface the actual failure (HTTP status/body from OpenRouterClient, or the raw
                // exception) instead of a generic "request failed" - this was the #1 confusing
                // error users hit, since the real cause (bad key, rate limit, model down, no
                // network) was being swallowed.
                val detail = it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString()
                onEvent(AgentEvent.Error("Model request failed: $detail"))
                return
            }
            val raw = chatResult.content
            totalPromptTokens += chatResult.promptTokens
            totalCompletionTokens += chatResult.completionTokens
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
                val retryResult = client.chat(apiKey, model, history, baseUrl = baseUrl)
                val retryChatResult = retryResult.getOrElse {
                    val detail = it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString()
                    onEvent(AgentEvent.Error("Model request failed on retry: $detail"))
                    return
                }
                val retryRaw = retryChatResult.content
                totalPromptTokens += retryChatResult.promptTokens
                totalCompletionTokens += retryChatResult.completionTokens
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

            // Human-in-the-loop. Ending the turn here (rather than continuing to burn steps
            // against a wall only a person can get past) works because AgentChatController caches
            // the engine: the whole ReAct history survives, so the user's reply resumes this very
            // run with everything remembered - the thing that makes captchas, OTPs and manual
            // approvals workable on a phone at all.
            if (action == "ask_user") {
                val question = input.optString("question").ifBlank { input.optString("text") }
                history += ChatTurn("user", "Observation: you asked the user a question and your turn ended. Wait for their reply, then continue the plan.")
                onEvent(AgentEvent.FinalAnswer(
                    if (question.isBlank()) "I need your input before I can continue - how would you like me to proceed?"
                    else question
                ))
                return
            }

            if (action == "final_answer") {
                val modelText = input.optString("text").ifBlank { thought }
                var finalText = if (verbatimBlocks.isEmpty()) modelText else injectVerbatimBlocks(modelText, verbatimBlocks)
                // Small recap footer: what it did and roughly what it cost. Token counts are 0 when
                // the underlying model/provider doesn't report usage (common on some free models) -
                // in that case this quietly reduces to just the step/tool count.
                if (toolsUsed.isNotEmpty()) {
                    val tokenPart = if (totalPromptTokens + totalCompletionTokens > 0) {
                        ", ~${totalPromptTokens + totalCompletionTokens} tokens (${totalPromptTokens} in / ${totalCompletionTokens} out)"
                    } else ""
                    finalText += "\n\n---\n*${toolsUsed.size} step${if (toolsUsed.size == 1) "" else "s"}: ${toolsUsed.joinToString(" \u2192 ")}$tokenPart*"
                }
                onEvent(AgentEvent.FinalAnswer(finalText))
                return
            }

            // Same tool + same input twice in a row is a stuck loop (usually a URL filter that
            // isn't matching what the model expects) - nudge it toward a different move instead
            // of silently burning the whole step budget on repeats, like the 9-step DeepSeek
            // OPTIONS-vs-POST run that never converged.
            val signature = "$action:${input}"
            repeatCount = if (signature == lastActionSignature) repeatCount + 1 else 0
            lastActionSignature = signature
            if (repeatCount == 1) {
                history += ChatTurn(
                    "user",
                    "You just called the exact same action with the same input again and got the same result. That filter isn't finding what you need - try get_network_requests with no filter to see everything captured, or a different/broader substring, or add a \"method\" filter."
                )
            }

            onEvent(AgentEvent.ToolCall(action, input.toString()))
            toolsUsed += action
            val observation = runCatching { execute(action, input) }.getOrElse { "Error: ${it.message}" }
            val sanitized = input.optBoolean("sanitized", false)
            when {
                action == "get_curl" && !sanitized && observation.startsWith("curl") -> verbatimBlocks += observation
                action == "get_graphql_queries" && !observation.startsWith("No ") -> verbatimBlocks += observation
                action == "infer_schema" && !observation.startsWith("No ") -> verbatimBlocks += observation
            }
            if (!observation.startsWith("No ") && !observation.startsWith("Error")) lastUsefulObservation = observation
            onEvent(AgentEvent.ToolResult(observation))
            history += ChatTurn("user", "Observation: $observation")
        }
        // Don't just say "stopped" - hand back whatever the agent actually found, so a run that
        // located the right request but ran out of steps before calling final_answer doesn't
        // throw that work away.
        val evidence = lastUsefulObservation?.let { "\n\nBest information found before running out of steps:\n$it" } ?: ""
        onEvent(AgentEvent.Error("Stopped after $maxSteps steps without a final answer.$evidence"))
    }

    /** Replaces whatever code block(s) the model wrote with the real, verbatim tool output(s)
     *  from this run (get_curl, get_graphql_queries, infer_schema) - guaranteeing the user always
     *  gets the actual captured data, never a model-paraphrased or redacted stand-in, regardless
     *  of how faithfully the underlying model transcribed it. Unsanitized get_curl results only;
     *  sanitized ones are supposed to have placeholders. */
    private fun injectVerbatimBlocks(modelText: String, verbatimBlocks: List<String>): String {
        val alreadyVerbatim = verbatimBlocks.all { modelText.contains(it) }
        if (alreadyVerbatim) return modelText
        val withoutCodeFences = modelText.replace(Regex("```[a-zA-Z]*\\n[\\s\\S]*?```"), "").trimEnd()
        val section = verbatimBlocks.joinToString("\n\n") { "```\n$it\n```" }
        return buildString {
            append(withoutCodeFences)
            if (withoutCodeFences.isNotBlank()) append("\n\n")
            append(section)
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
            val method = input.optString("method").ifBlank { null }
            val results = tools.findApiRequests(filter, method)
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
            val method = input.optString("method").ifBlank { null }
            tools.getCurlForRequest(filter, sanitized, method) ?: "No captured request found matching \"$filter\". Try find_api_requests or get_network_requests first to locate the exact request."
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
        "export_postman" -> tools.exportPostman()
        "get_cookies" -> tools.getCookies()
        "find_rate_limits" -> tools.findRateLimits()
        "list_endpoints" -> tools.listEndpoints()
        "detect_pagination" -> tools.detectPagination()
        "click_element" -> {
            val selector = input.optString("selector")
            if (tools.clickElement(selector)) "Clicked \"$selector\". Current URL: ${tools.currentUrl()}"
            else "No element matched selector \"$selector\"."
        }
        "type_text" -> {
            val selector = input.optString("selector")
            val text = input.optString("text")
            if (tools.typeIntoElement(selector, text)) "Typed into \"$selector\"." else "No element matched selector \"$selector\"."
        }
        "scroll_to" -> {
            val selector = input.optString("selector").ifBlank { null }
            val pixels = if (input.has("pixels")) input.optInt("pixels") else null
            if (tools.scrollTo(selector, pixels)) "Scrolled." else "No element matched selector \"$selector\"."
        }
        "wait_for_element" -> {
            val selector = input.optString("selector")
            if (tools.waitForElement(selector)) "\"$selector\" appeared." else "\"$selector\" did not appear within ~5s."
        }
        "take_screenshot" -> tools.takeScreenshot()
        "switch_tab" -> {
            val filter = input.optString("filter")
            if (tools.switchTab(filter)) "Switched to tab matching \"$filter\". Current URL: ${tools.currentUrl()}"
            else "No open tab matched \"$filter\". Use list_tabs to see what's open."
        }
        "save_request" -> {
            val label = input.optString("label")
            val curl = input.optString("curl")
            if (label.isBlank() || curl.isBlank()) "Both \"label\" and \"curl\" are required (get the curl from get_curl first)." else tools.saveRequest(label, curl)
        }
        "list_saved_requests" -> {
            val results = tools.listSavedRequests()
            if (results.isEmpty()) "No saved requests yet." else results.joinToString("\n") { "- $it" }
        }
        "get_saved_request" -> {
            val label = input.optString("label")
            tools.getSavedRequest(label) ?: "No saved request found under \"$label\"."
        }
        "remember_site_note" -> tools.rememberSiteNote(input.optString("note"))
        "recall_site_notes" -> {
            val domain = input.optString("domain").ifBlank { null }
            val notes = tools.recallSiteNotes(domain)
            if (notes.isEmpty()) "No notes remembered for this site yet." else notes.joinToString("\n") { "- $it" }
        }
        "watch_page" -> {
            val label = input.optString("label")
            val url = input.optString("url")
            val interval = input.optInt("interval_minutes", 60)
            if (label.isBlank() || url.isBlank()) "Both \"label\" and \"url\" are required." else tools.watchPage(label, url, interval)
        }
        "list_watches" -> {
            val results = tools.listWatches()
            if (results.isEmpty()) "No active page watches." else results.joinToString("\n") { "- $it" }
        }
        "cancel_watch" -> {
            val label = input.optString("label")
            if (tools.cancelWatch(label)) "Cancelled watch \"$label\"." else "No watch found under \"$label\"."
        }
        "export_openapi" -> tools.exportOpenApi()
        "beautify_js" -> tools.beautifyJs(input.optString("url_or_code"))
        "list_files" -> {
            val category = input.optString("category").ifBlank { null }
            val files = tools.listSavedFiles(category)
            if (files.isEmpty()) "No files saved under ${category ?: "agent"} yet." else files.joinToString("\n") { "- $it" }
        }
        "read_file" -> {
            val category = input.optString("category").ifBlank { null }
            val filename = input.optString("filename")
            tools.readSavedFile(category, filename) ?: "No file named \"$filename\" found under ${category ?: "agent"}."
        }
        "write_file" -> {
            val category = input.optString("category").ifBlank { null }
            val filename = input.optString("filename")
            val content = input.optString("content")
            if (filename.isBlank()) "Need a filename to save this as." else tools.writeSavedFile(category, filename, content)
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
        "todo_write" -> {
            val arr = input.optJSONArray("items")
            if (arr == null) {
                "No items given. Expected: {\"items\":[{\"text\":\"step\",\"status\":\"pending\"}]}"
            } else {
                val entries = (0 until arr.length()).mapNotNull { i ->
                    when (val el = arr.opt(i)) {
                        is String -> el to "pending"
                        is JSONObject -> el.optString("text").ifBlank { el.optString("task") } to el.optString("status", "pending")
                        else -> null
                    }
                }.filter { it.first.isNotBlank() }
                if (entries.isEmpty()) "No usable items - each one needs a \"text\"." else tools.todoWrite(entries)
            }
        }
        "todo_read" -> tools.todoRead()
        "captcha_detect" -> tools.captchaDetect()
        "captcha_solve" -> tools.captchaSolve(
            useSolver = input.optBoolean("use_solver", true),
            timeoutSec = input.optInt("timeout_sec", 120)
        )
        "browser_tap" -> {
            val tapX = input.optDouble("x", -1.0).toFloat()
            val tapY = input.optDouble("y", -1.0).toFloat()
            when {
                tapX < 0f || tapY < 0f -> "Need numeric x and y in viewport CSS pixels."
                tools.tapAt(tapX, tapY) -> "Tapped ($tapX, $tapY)."
                else -> "Could not tap - no page is loaded."
            }
        }
        "browser_snapshot" -> tools.browserSnapshot(input.optInt("max_nodes", 150))
        "browser_click" -> tools.browserClick(input.optString("ref"))
        "browser_type" -> tools.browserType(
            input.optString("ref"), input.optString("text"), input.optBoolean("submit", false)
        )
        "browser_fill_form" -> {
            val arr = input.optJSONArray("fields")
            if (arr == null) {
                "Need a \"fields\" array of {ref, text} objects."
            } else {
                val fields = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val ref = o.optString("ref")
                    if (ref.isBlank()) null else Triple(ref, o.optString("text"), o.optBoolean("submit", false).toString())
                }
                if (fields.isEmpty()) "No usable fields (each needs a ref)." else tools.browserFillForm(fields)
            }
        }
        "browser_select_option" -> {
            val arr = input.optJSONArray("values")
            val values = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }
            tools.browserSelectOption(input.optString("ref"), values)
        }
        "browser_hover" -> tools.browserHover(input.optString("ref"))
        "browser_press_key" -> tools.browserPressKey(
            input.optString("ref").ifBlank { null }, input.optString("key", "Enter")
        )
        "browser_wait_for" -> tools.browserWaitFor(
            input.optString("text").ifBlank { null },
            input.optString("text_gone").ifBlank { null },
            input.optInt("time_ms", 3000)
        )
        "browser_handle_dialog" -> tools.browserHandleDialog(
            input.optBoolean("accept", true), input.optString("prompt_text").ifBlank { null }
        )
        "browser_tabs" -> tools.browserTabs(
            input.optString("action", "list"), if (input.has("index")) input.optInt("index") else null
        )
        "browser_console_messages" -> tools.browserConsoleMessages()
        "http_send" -> tools.httpSend(
            input.optString("raw_request"),
            input.optBoolean("include_cookies", true),
            input.optBoolean("follow_redirects", false)
        )
        "encode" -> tools.encodeDecode(input.optString("transform"), "encode", input.optString("input"))
        "decode" -> tools.encodeDecode(input.optString("transform"), "decode", input.optString("input"))
        "jwt_decode" -> tools.jwtDecode(input.optString("token"))
        "dump_storage" -> tools.dumpStorage()
        "audit_security_headers" -> tools.auditSecurityHeaders()
        "extract_js_endpoints" -> tools.extractJsEndpoints(input.optInt("max_endpoints", 100))
        "toggle_intercept" -> tools.toggleIntercept(input.optBoolean("enable", true))
        "set_match_replace" -> tools.setMatchReplace(
            input.optString("type"), input.optString("match"), input.optString("replace"),
            input.optBoolean("is_regex", false)
        )
        "send_to_repeater" -> tools.sendToRepeater(input.optString("url_filter"))
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
