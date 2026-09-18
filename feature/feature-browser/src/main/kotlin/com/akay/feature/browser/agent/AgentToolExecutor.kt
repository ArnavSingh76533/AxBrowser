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

    /** Scrapes a repeated listing into structured JSON-like records in one call. See AgentJs.scrapeStructured. */
    suspend fun scrapeStructured(itemSelector: String, fields: Map<String, String>): List<Map<String, String>>

    /** Runs an arbitrary JS expression in the page and returns its string result - the agent's escape hatch. */
    suspend fun runJs(code: String): String

    /** Dev-console network log: requests the page has made, optionally filtered by a URL substring. */
    suspend fun getNetworkRequests(filter: String?): List<String>

    /** Same network log, narrowed to requests that look like backend/API calls rather than static assets
     *  (css/js/img/fonts). CORS preflight (OPTIONS) requests are always excluded - they're never the real
     *  call. [method] optionally narrows to one HTTP method (e.g. "POST") when a URL matches several. */
    suspend fun findApiRequests(filter: String?, method: String? = null): List<String>

    /** Builds a full runnable curl command (method, URL, headers incl. cookies, body) for the best-matching
     *  captured request, so the agent can hand the user a request they can replay outside the app.
     *  [method] optionally narrows to one HTTP method when a URL filter matches several requests (e.g. a
     *  path that has both a GET and a POST, or a real POST alongside its OPTIONS preflight).
     *  [sanitized] swaps auth-looking header values (cookie/bearer/api-key/csrf) for a placeholder -
     *  for when the user wants to share/document the request rather than actually run it themselves. */
    suspend fun getCurlForRequest(urlFilter: String, sanitized: Boolean = false, method: String? = null): String?

    /** The captured response body for the best-matching request (truncated), to inspect an API's payload shape. */
    suspend fun getResponseBody(urlFilter: String): String?

    /** Direct video/audio URLs already detected on the page (network + DOM), often better than the page URL for downloads. */
    suspend fun getDetectedMedia(): List<String>

    suspend fun listTabs(): List<String>

    /** Traces which captured request(s) established the session - i.e. which response set a
     *  cookie, or returned a token/access_token-shaped field in its JSON body - and which later
     *  requests then depend on it (send that cookie, or an Authorization/X-*-Token header). This
     *  is how you find the actual login->token->API call chain instead of guessing it. */
    suspend fun findAuthFlow(): String

    /** Compares two captured requests to the same-shaped endpoint (e.g. page 1 vs page 2 of a
     *  paginated listing) and reports exactly which URL params / body fields / headers differ -
     *  the fast way to isolate pagination cursors, filters, or signature params without manually
     *  eyeballing two long requests. */
    suspend fun diffRequests(filterA: String, filterB: String): String

    /** Infers a rough field/type schema (Kotlin-data-class-shaped) from the best-matching
     *  captured response body, for when the user wants a typed model instead of just the raw curl. */
    suspend fun inferSchema(urlFilter: String): String

    /** GraphQL-specific view of captured requests: extracts and pretty-prints the operation name
     *  plus the `query`/`mutation` document and `variables` from the POST body, since a plain
     *  get_curl on a GraphQL call just shows one opaque JSON blob at one single endpoint URL. */
    suspend fun getGraphQlQueries(filter: String?): List<String>

    /** Writes everything captured so far as a HAR 1.2 file (loadable in DevTools/Postman/Charles/
     *  Insomnia) into app storage and returns the path, or an explanation if nothing to export. */
    suspend fun exportHar(): String

    /** Writes captured requests as a Postman Collection v2.1 JSON file into app storage and
     *  returns the path - more useful than raw curl/HAR when the user wants to keep building on
     *  a discovered API in Postman/Insomnia rather than just replaying one call. */
    suspend fun exportPostman(): String

    /** All cookies (name, value, and flags like HttpOnly/Secure/SameSite where available) for the
     *  current page's domain, as its own inspectable result - previously only visible bundled
     *  inside a get_curl header dump. */
    suspend fun getCookies(): String

    /** Scans captured response headers across all requests for rate-limit signals
     *  (X-RateLimit-*, RateLimit-*, Retry-After) and summarizes what the API's actual limits
     *  look like, instead of the agent having to notice/compute this itself from raw headers. */
    suspend fun findRateLimits(): String

    /** De-duplicated, path-grouped summary of every distinct API endpoint captured this session
     *  (method + path, with a count and an example full URL) - the "what does this site's API
     *  surface look like" answer, as opposed to a raw chronological request log. Also flags when
     *  responses look like WebAssembly, since that's a sign the real logic isn't reverse-
     *  engineerable from network traffic alone. */
    suspend fun listEndpoints(): String

    /** Looks across every captured call to each distinct endpoint path (3+ calls) and reports
     *  which single query/body param changed between them, in case a pagination/cursor pattern
     *  is visible from natural browsing alone rather than needing a manual two-request diff. */
    suspend fun detectPagination(): String

    /** Clicks the first element matching a CSS selector (or containing given text, for buttons/
     *  links without a stable selector) and waits briefly for any resulting navigation/DOM change. */
    suspend fun clickElement(selector: String): Boolean

    /** Types text into the first element matching a CSS selector (input/textarea), dispatching
     *  proper input/change events so frameworks like React pick up the change, not just the DOM. */
    suspend fun typeIntoElement(selector: String, text: String): Boolean

    /** Scrolls the first element matching a CSS selector into view (or scrolls the page by a
     *  given number of pixels when no selector is given), useful before clicking something
     *  currently off-screen or to trigger infinite-scroll/lazy-load content. */
    suspend fun scrollTo(selector: String?, pixels: Int?): Boolean

    /** Polls (up to ~5s) for a CSS selector to appear in the DOM before returning, for pages
     *  where the next action depends on something finishing loading first. */
    suspend fun waitForElement(selector: String): Boolean

    /** Captures the current WebView content to a PNG file in app storage and returns the path -
     *  for visually confirming a result (a toast, a modal, a redirect) rather than only trusting
     *  network/DOM state. This captures a file for the user to open; it is not fed back to the
     *  model for visual inspection (that needs a vision-capable model + multimodal message
     *  support, which isn't wired up yet). */
    suspend fun takeScreenshot(): String

    /** Switches the active tab to the first open tab whose title or URL contains [filter], and
     *  waits briefly for it to be ready - so the agent can act across multiple already-open tabs
     *  (e.g. "check the API call that happened in the popup tab") rather than only the one it
     *  started in. Returns false if no open tab matched. */
    suspend fun switchTab(filter: String): Boolean

    /** Saves the given curl command under [label] for later recall via getSavedRequest, so a
     *  request the agent worked to locate doesn't need re-discovering next time. */
    suspend fun saveRequest(label: String, curl: String): String

    /** Lists every request saved via saveRequest (label + domain + when saved). */
    suspend fun listSavedRequests(): List<String>

    /** The exact curl command previously saved under [label], or null if no such label exists. */
    suspend fun getSavedRequest(label: String): String?

    /** Records a short note about the current site's domain that will be available again in a
     *  FUTURE chat session on this same domain (injected into context automatically at the start
     *  of a run) - not just later in this one run. Use for things worth remembering across visits:
     *  quirks of a site's auth flow, a header it requires, a login form's actual field names. */
    suspend fun rememberSiteNote(note: String): String

    /** Explicitly looks up saved notes for a domain (defaults to the current page's domain if
     *  none given) - normally unnecessary since relevant notes are auto-injected at run start,
     *  but useful when the user asks "what do you remember about this site". */
    suspend fun recallSiteNotes(domain: String?): List<String>

    /** Schedules a recurring background check of [url] that notifies the user when its raw HTML
     *  content changes (checked via plain HTTP fetch, not a live WebView render - won't catch
     *  changes that only appear after client-side JS runs). [intervalMinutes] is clamped up to
     *  15, Android's minimum for periodic background work. */
    suspend fun watchPage(label: String, url: String, intervalMinutes: Int): String

    /** Lists every active background watch (label, URL, interval, last checked time). */
    suspend fun listWatches(): List<String>

    /** Cancels the background watch registered under [label]. */
    suspend fun cancelWatch(label: String): Boolean

    /** Generates a minimal OpenAPI 3.0 document from every API-like request captured this
     *  session (grouped by path, with inferred parameter/body shapes) and writes it to app
     *  storage, returning the path - for the user who wants a real spec to build a client
     *  against rather than one-off curl commands. */
    suspend fun exportOpenApi(): String

    /** Reformats minified/obfuscated JavaScript (either fetched from a URL, or passed directly as
     *  a code string) into indented, readable form - separating "hard to read" from "hard to
     *  understand". This is a lightweight bracket/statement-based reformatter, not a real AST
     *  parser or a variable-renaming deobfuscator - it can't undo control-flow flattening, string
     *  encoding, or actual obfuscation techniques, only the "everything on one line" minification
     *  that makes even simple scripts unreadable. Genuinely useful for a first look at a worker
     *  script or bundle before deciding whether closer manual analysis is worth it. */
    suspend fun beautifyJs(urlOrCode: String): String

    /** Lists filenames the agent (or user) has saved under [category] ("screenshots", "downloads",
     *  "agent", or "other" - default "agent" when omitted). This is the same storage every other
     *  save/export tool writes into (Settings > Storage decides whether that's a user-visible
     *  folder or app-private storage) - use this to see what's already there before creating a
     *  new file, or to point the user at something saved earlier. */
    suspend fun listSavedFiles(category: String?): List<String>

    /** Reads back a text file previously saved under [category] via this tool, write_file, or any
     *  export tool (export_har, export_postman, export_openapi, save_request, screenshots aren't
     *  text so won't read as anything useful). Null if no such file exists. */
    suspend fun readSavedFile(category: String?, filename: String): String?

    /** Saves [content] as a new text file named [filename] under [category] (default "agent") -
     *  for when the user asks to save a script, a note, generated code, or any other text output
     *  as an actual file rather than just showing it in chat. Overwrites a file of the same name
     *  rather than creating a duplicate. Returns where it landed. */
    suspend fun writeSavedFile(category: String?, filename: String, content: String): String

    // ---------- Playwright-MCP-shaped tools (v7) ----------

    /** Fresh accessibility-style snapshot of the page: compact node list with stable refs.
     *  The model then acts by ref (browser_click/browser_type), never by writing selectors. */
    suspend fun browserSnapshot(maxNodes: Int): String

    /** Clicks the element under [ref] from the last browser_snapshot. Reports post-click state. */
    suspend fun browserClick(ref: String): String

    /** Types [text] into the element under [ref]; optionally presses Enter (submit) after. */
    suspend fun browserType(ref: String, text: String, submit: Boolean): String

    /** Batch fill: [fields] of ref -> value, one pass. Returns per-field status lines. */
    suspend fun browserFillForm(fields: List<Triple<String, String, String>>): String

    /** Selects [values] on the <select> under [ref]. */
    suspend fun browserSelectOption(ref: String, values: List<String>): String

    /** Hovers the element under [ref] (synthetic mouseover/mouseenter/mousemove). */
    suspend fun browserHover(ref: String): String

    /** Presses [key] (Enter/Tab/Escape/text) on the element under [ref] or the active element. */
    suspend fun browserPressKey(ref: String?, key: String): String

    /** Waits for [text] to appear, [textGone] to disappear, or just [timeMs] to elapse. */
    suspend fun browserWaitFor(text: String?, textGone: String?, timeMs: Int): String

    /** Answers a pending JS dialog (alert/confirm/prompt): accept/reject + prompt text. */
    suspend fun browserHandleDialog(accept: Boolean, promptText: String?): String

    /** Tab operations: list | new | close | select. */
    suspend fun browserTabs(action: String, index: Int?): String

    /** Recent console messages (log/warn/error ring buffer). */
    suspend fun browserConsoleMessages(): String

    // ---------- Pentest tools (v7): always available, any host, no gating ----------

    /** Sends a raw/structured HTTP request via the raw-socket sender and returns status + headers + body. */
    suspend fun httpSend(rawRequest: String, includeCookies: Boolean, followRedirects: Boolean): String

    /** Encodes/decodes via the transform chain (url/base64/hex/html/unicode/rot13/...). */
    suspend fun encodeDecode(transform: String, direction: String, input: String): String

    /** Decodes a JWT: header/payload/signature + alg:none + expiry analysis. */
    suspend fun jwtDecode(token: String): String

    /** Dumps localStorage/sessionStorage + cookies for the current origin. */
    suspend fun dumpStorage(): String

    /** Passive security-headers/cookie scorecard for the current page's captured traffic. */
    suspend fun auditSecurityHeaders(): String

    /** LinkFinder-lite over same-origin scripts: discovered endpoint strings. */
    suspend fun extractJsEndpoints(maxEndpoints: Int): String

    /** Toggles request interception on/off. */
    suspend fun toggleIntercept(enable: Boolean): String

    /** Adds a Match&Replace rule (REPLACE_REQ_HEADER | REPLACE_REQ_BODY | REPLACE_RESP_BODY). */
    suspend fun setMatchReplace(type: String, match: String, replace: String, isRegex: Boolean): String

    /** Hands a captured request (matched by [urlFilter]) to the Repeater tab. */
    suspend fun sendToRepeater(urlFilter: String): String

    // ---------- Agent v8: plan, handoff, real touch ----------

    /** Replaces the agent's working plan for this session. [items] are (text, status) pairs,
     *  status being "pending" | "in_progress" | "done". The list is rendered live in the agent
     *  sheet, so the operator can watch a multi-step job progress instead of guessing. Returns
     *  the rendered checklist. */
    suspend fun todoWrite(items: List<Pair<String, String>>): String

    /** The current plan, for re-reading it mid-run instead of relying on memory. */
    suspend fun todoRead(): String

    /** Every captcha on the current page: type, provider, sitekey, kind, and the viewport
     *  coordinates of its checkbox - enough for the agent to decide between a native tap, a
     *  token solve, or handing off to the user. */
    suspend fun captchaDetect(): String

    /** Tries to clear the page's captchas: a native tap on each visible checkbox, then (when
     *  [useSolver] and a key is configured) a token solve plus injection. Widgets it cannot
     *  clear are reported honestly so the agent can hand off instead of looping. */
    suspend fun captchaSolve(useSolver: Boolean, timeoutSec: Int): String

    /** A real ACTION_DOWN/ACTION_UP touch at viewport CSS coordinates ([x], [y]). This is the
     *  only way to reach inside a cross-origin iframe - captcha checkboxes, embedded payment
     *  widgets, canvas apps - because same-document JS cannot click across an origin boundary. */
    suspend fun tapAt(x: Float, y: Float): Boolean

    // ---------- Bug-bounty findings (v8) ----------

    /** Records one vulnerability finding in the session's findings list, persisted to
     *  Agent/findings.json so it survives process death. [severity] is normalized to
     *  critical|high|medium|low|info (anything unrecognized becomes "info"). [url] defaults to the
     *  current page when blank. [description] should state what's wrong, the impact, and the
     *  suggested fix; [evidence] should be the proof - a get_curl command, a request/response, a
     *  JS snippet. Returns a confirmation with the assigned finding id and the running total. */
    suspend fun saveFinding(
        title: String,
        severity: String,
        url: String,
        description: String,
        evidence: String
    ): String

    /** Every recorded finding, one line each (id, severity, title, URL, when, evidence preview),
     *  optionally narrowed to a single [severity]. Empty when nothing has been recorded. */
    suspend fun listFindings(severity: String?): List<String>

    /** Writes every recorded finding as one Markdown report shaped for HackerOne/Bugcrowd/
     *  YesWeHack into app storage and returns where it landed. [sanitized] (default true) redacts
     *  auth-looking header values and token params from the evidence - the report is a document
     *  meant to be shared, so live cookies/tokens are not included unless explicitly asked for. */
    suspend fun exportBugReport(program: String?, platform: String?, sanitized: Boolean): String
}

data class AgentLink(val text: String, val href: String)

