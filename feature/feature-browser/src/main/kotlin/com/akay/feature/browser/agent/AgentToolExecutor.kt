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
}

data class AgentLink(val text: String, val href: String)

