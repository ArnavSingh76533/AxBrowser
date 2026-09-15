package com.akay.feature.browser.devconsole

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class NetworkRequest(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val method: String = "GET",
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseStatus: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val startTime: Long = System.currentTimeMillis(),
    val isBlocked: Boolean = false,
    val requestBody: String = "",
    val responseBody: String = "",
    val source: String = "webview",
    /** URL of the page the request was issued from. Used only as a resolution base for [url]
     *  when it is not already absolute - net_capture.js reports absolute URLs, so this is a
     *  safety net, not the primary path. */
    val pageUrl: String = ""
) {
    val isMedia: Boolean
        get() {
            val lowerUrl = url.lowercase()
            val contentType = mimeType?.lowercase() ?: ""
            val videoExts = listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m3u8", ".mpd", ".ts", ".flv")
            val audioExts = listOf(".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".opus")
            val mediaTypes = listOf("video/", "audio/", "application/x-mpegurl", "application/dash+xml", "application/octet-stream")

            return videoExts.any { lowerUrl.contains(it) }
                || audioExts.any { lowerUrl.contains(it) }
                || mediaTypes.any { contentType.contains(it) }
        }

    /** True for CORS preflight requests - the browser's own OPTIONS check before the real
     *  request, always empty-bodied and never carrying the actual payload/auth the user wants.
     *  Excluded from [isApiLike] so the agent doesn't have to (weak models otherwise regularly
     *  grab the empty preflight instead of the real POST when both match a URL filter). */
    val isPreflight: Boolean
        get() = method.equals("OPTIONS", ignoreCase = true)

    /**
     * Heuristic for "this looks like a backend API call, not a static asset" - JSON/XHR/fetch
     * traffic, GraphQL, REST-ish paths. Used by the agent to separate the actual data endpoints
     * from images/css/js/fonts noise when the user asks "what API does this site use".
     */
    val isApiLike: Boolean
        get() {
            if (isMedia || isPreflight) return false
            val lowerUrl = url.lowercase()
            val contentType = mimeType?.lowercase() ?: ""
            val staticExts = listOf(".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2", ".ttf", ".ico")
            if (staticExts.any { lowerUrl.substringBefore('?').endsWith(it) }) return false

            val apiSignals = listOf("application/json", "application/graphql", "application/ld+json")
            val pathSignals = listOf("/api/", "/graphql", "/v1/", "/v2/", "/v3/", ".json", "/rest/", "/gql")
            return source != "webview"
                || apiSignals.any { contentType.contains(it) }
                || pathSignals.any { lowerUrl.contains(it) }
                || !method.equals("GET", ignoreCase = true)
        }

    /** True for GraphQL calls - a POST whose body is `{"query": ...}`/`{"operationName": ...}`
     *  or whose URL contains /graphql. These need separate handling from REST since the "real"
     *  endpoint is opaque (one URL for everything) and the actual API surface is inside the body. */
    val isGraphQl: Boolean
        get() = url.lowercase().let { it.contains("/graphql") || it.contains("/gql") } ||
            (requestBody.trimStart().startsWith("{") &&
                (requestBody.contains("\"query\"") || requestBody.contains("\"operationName\"")))

    /** Names of headers on this request that look like they carry auth/session state, so the
     *  agent (and the sanitized curl variant) can call them out instead of treating every header
     *  the same way. Covers cookie-based sessions, bearer/JWT tokens, and common API-key headers. */
    val authHeaderNames: List<String>
        get() {
            val authLike = setOf(
                "cookie", "authorization", "x-api-key", "api-key", "x-auth-token",
                "x-access-token", "x-csrf-token", "x-xsrf-token", "x-session-token", "x-auth"
            )
            return requestHeaders.keys.filter { it.lowercase() in authLike }
        }

    /** True when this request carries headers that look like a solved anti-bot challenge or a
     *  short-lived signed/rotating token (proof-of-work responses, nonces, per-request
     *  signatures) rather than a stable, reusable credential. A captured curl for a request like
     *  this can look complete and still fail on replay once the challenge/nonce expires - that's
     *  the site's anti-automation working as intended, not a capture bug, and the agent should
     *  say so instead of implying the command is guaranteed to work. */
    val hasLikelyAntiReplayHeaders: Boolean
        get() {
            val signals = listOf("pow", "challenge", "nonce", "signature", "hif-", "captcha", "fp-", "fingerprint", "attestation")
            return requestHeaders.keys.any { key -> signals.any { key.lowercase().contains(it) } }
        }

    /** Builds a runnable curl command reproducing this request. [extraHeaders] (e.g. Cookie
     *  pulled from CookieManager when the page never set it via JS) are merged in without
     *  overriding anything already captured on the request itself. When [sanitize] is true,
     *  auth-looking header values (see [authHeaderNames]) are replaced with placeholders so the
     *  command is safe to paste into a bug report/doc instead of leaking the user's live session.
     *  JSON bodies are pretty-printed (multi-line inside the single-quoted --data-raw value,
     *  which bash preserves fine) instead of dumped as one dense line, and a Content-Type header
     *  is added automatically if the page set one implicitly (e.g. fetch() with a plain object
     *  body) but it never made it into the captured headers. */
    fun toCurl(extraHeaders: Map<String, String> = emptyMap(), sanitize: Boolean = false): String {
        val sb = StringBuilder("curl --compressed")
        if (!method.equals("GET", ignoreCase = true)) sb.append(" -X ").append(method)
        // Always emit a full URL. A bare path like '/api/v0/chat/completion' produces a curl
        // command with no scheme and no host that cannot be replayed anywhere; resolve it
        // against the page it came from instead.
        sb.append(" '").append(absoluteUrl()).append("'")
        val merged = LinkedHashMap<String, String>()
        requestHeaders.forEach { (k, v) -> merged[k] = v }
        extraHeaders.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val looksLikeJson = requestBody.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        if (looksLikeJson && merged.keys.none { it.equals("content-type", ignoreCase = true) }) {
            merged["Content-Type"] = "application/json"
        }
        val authLower = authHeaderNames.map { it.lowercase() }.toSet()
        merged.forEach { (k, v) ->
            val value = if (sanitize && k.lowercase() in authLower) "<REDACTED>" else v
            sb.append(" \\\n  -H '").append(k).append(": ").append(value.replace("'", "'\\''")).append("'")
        }
        if (requestBody.isNotBlank()) {
            val bodyForCurl = if (looksLikeJson) prettyJsonOrRaw(requestBody) else requestBody
            sb.append(" \\\n  --data-raw '").append(bodyForCurl.replace("'", "'\\''")).append("'")
        }
        return sb.toString()
    }

    private fun prettyJsonOrRaw(body: String): String = runCatching {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) JSONArray(body).toString(2) else JSONObject(body).toString(2)
    }.getOrDefault(body)

    /** This request's URL, guaranteed to carry a scheme+host when it can possibly be derived.
     *  Absolute URLs pass through untouched; a relative one is resolved against [pageUrl]. */
    fun absoluteUrl(): String = absolutize(url, pageUrl)
}

/** Resolves [raw] against [base] when [raw] is relative. Returns [raw] unchanged when it is
 *  already absolute or when no usable base is known - never invents a host. */
internal fun absolutize(raw: String, base: String): String {
    val r = raw.trim()
    if (r.isEmpty()) return raw
    val lower = r.lowercase()
    val schemes = listOf("http://", "https://", "ws://", "wss://", "data:", "blob:", "file:", "content://")
    if (schemes.any { lower.startsWith(it) }) return r
    if (base.isBlank()) return raw
    return runCatching { java.net.URL(java.net.URL(base), r).toString() }
        .getOrElse { runCatching { java.net.URI(base).resolve(r).toString() }.getOrDefault(raw) }
}

data class WebSocketFrame(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    /** open | send | recv | close */
    val direction: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class DetectedMedia(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val filename: String,
    val mimeType: String?,
    val isVideo: Boolean,
    val source: String
)

object NetworkInterceptor {
    private val _requests = MutableStateFlow<List<NetworkRequest>>(emptyList())
    val requests: StateFlow<List<NetworkRequest>> = _requests.asStateFlow()

    private val _detectedMedia = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val detectedMedia: StateFlow<List<DetectedMedia>> = _detectedMedia.asStateFlow()

    private val _rules = MutableStateFlow<List<InterceptorRule>>(emptyList())
    val rules: StateFlow<List<InterceptorRule>> = _rules.asStateFlow()

    private val _webSocketFrames = MutableStateFlow<List<WebSocketFrame>>(emptyList())
    val webSocketFrames: StateFlow<List<WebSocketFrame>> = _webSocketFrames.asStateFlow()

    private const val MAX_ENTRIES = 500
    private const val MAX_WS_FRAMES = 300
    private val seenMediaUrls = mutableSetOf<String>()

    fun onRequest(request: NetworkRequest) {
        _requests.update { current ->
            // If the JS bridge already captured this exact request (same URL+method) with a real
            // body/headers very recently, don't add a second, emptier entry for it - Android's
            // WebResourceRequest has no API to read POST bodies, so the native intercept is always
            // body-less; keeping both around let get_curl/find_api_requests randomly land on the
            // useless one instead of the one with the actual payload.
            val recentJsTwin = current.lastOrNull {
                it.url == request.url && it.method.equals(request.method, ignoreCase = true) &&
                    it.source != "webview" && it.requestBody.isNotBlank() &&
                    request.startTime - it.startTime in 0..5000
            }
            if (recentJsTwin != null) return@update current
            val updated = current + request
            if (updated.size > MAX_ENTRIES) updated.drop(updated.size - MAX_ENTRIES) else updated
        }
        maybeAddMedia(request.url, request.mimeType)
    }

    /** Feeds a request/response captured by the in-page JS bridge (fetch/XHR/WebSocket).
     *  [requestHeaders] are headers the page itself attached (Authorization, X-Api-Key, custom
     *  session headers, ...) - required for get_curl to reproduce header-based auth, not just
     *  cookies. [wsDirection] is set only for source == "websocket" frames (open/send/recv/close)
     *  and those are routed to the separate WebSocket log instead of the request table, since
     *  they aren't request/response pairs. */
    fun onCapturedRequest(
        url: String,
        method: String,
        status: Int?,
        requestBody: String,
        requestHeaders: Map<String, String> = emptyMap(),
        responseBody: String,
        responseHeaders: Map<String, String>,
        mimeType: String?,
        durationMs: Long,
        source: String,
        wsDirection: String? = null,
        pageUrl: String = ""
    ) {
        val resolvedUrl = absolutize(url, pageUrl)
        // WebSocket and Server-Sent-Events frames are both "messages on a connection", not
        // request/response pairs, so they share the frame log instead of the request table.
        if (source == "websocket" || source == "sse") {
            val direction = wsDirection ?: "recv"
            val message = if (direction == "send") requestBody else responseBody
            _webSocketFrames.update { current ->
                val updated = current + WebSocketFrame(url = resolvedUrl, direction = direction, message = message)
                if (updated.size > MAX_WS_FRAMES) updated.drop(updated.size - MAX_WS_FRAMES) else updated
            }
            return
        }
        val req = NetworkRequest(
            url = resolvedUrl,
            method = method,
            responseStatus = status,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            mimeType = mimeType,
            durationMs = durationMs,
            requestBody = requestBody,
            responseBody = responseBody,
            source = source,
            pageUrl = pageUrl
        )
        _requests.update { current ->
            // The native WebViewClient intercept (source == "webview") already logged this exact
            // request when it started, but Android gives it no way to read the POST body - it's
            // always blank there. This JS-bridge callback is the one call site that actually has
            // the real body/headers (fetch/XHR capture in net_capture.js). Update that earlier
            // placeholder entry IN PLACE instead of appending a second record for the same call,
            // so get_curl/get_network_requests/export_har only ever see ONE entry per request -
            // and it's always the complete one, never a coin-flip between the two.
            val placeholderIdx = current.indexOfLast {
                it.url == resolvedUrl && it.method.equals(method, ignoreCase = true) &&
                    it.source == "webview" && it.requestBody.isBlank() &&
                    System.currentTimeMillis() - it.startTime in 0..15000
            }
            if (placeholderIdx >= 0) {
                current.toMutableList().also { it[placeholderIdx] = req.copy(startTime = current[placeholderIdx].startTime) }
            } else {
                val updated = current + req
                if (updated.size > MAX_ENTRIES) updated.drop(updated.size - MAX_ENTRIES) else updated
            }
        }
        maybeAddMedia(resolvedUrl, mimeType)
    }

    /** Minimal HAR 1.2 export of everything captured so far, for loading into Charles/Postman/
     *  Insomnia/browser DevTools instead of copy-pasting individual curl commands one at a time. */
    fun toHar(): String {
        val entries = _requests.value.map { req ->
            JSONObject().apply {
                put("startedDateTime", java.time.Instant.ofEpochMilli(req.startTime).toString())
                put("time", req.durationMs)
                put("request", JSONObject().apply {
                    put("method", req.method)
                    put("url", req.url)
                    put("httpVersion", "HTTP/1.1")
                    put("headers", JSONArray(req.requestHeaders.map {
                        JSONObject().put("name", it.key).put("value", it.value)
                    }))
                    put("queryString", JSONArray())
                    put("cookies", JSONArray())
                    put("headersSize", -1)
                    put("bodySize", req.requestBody.toByteArray().size)
                    if (req.requestBody.isNotBlank()) {
                        put("postData", JSONObject().apply {
                            put("mimeType", req.requestHeaders.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "text/plain")
                            put("text", req.requestBody)
                        })
                    }
                })
                put("response", JSONObject().apply {
                    put("status", req.responseStatus ?: 0)
                    put("statusText", "")
                    put("httpVersion", "HTTP/1.1")
                    put("headers", JSONArray(req.responseHeaders.map {
                        JSONObject().put("name", it.key).put("value", it.value)
                    }))
                    put("cookies", JSONArray())
                    put("content", JSONObject().apply {
                        put("size", req.responseBody.toByteArray().size)
                        put("mimeType", req.mimeType ?: "")
                        put("text", req.responseBody)
                    })
                    put("redirectURL", "")
                    put("headersSize", -1)
                    put("bodySize", req.sizeBytes)
                })
                put("cache", JSONObject())
                put("timings", JSONObject().apply {
                    put("send", 0); put("wait", req.durationMs); put("receive", 0)
                })
            }
        }
        val har = JSONObject().apply {
            put("log", JSONObject().apply {
                put("version", "1.2")
                put("creator", JSONObject().put("name", "AxBrowser").put("version", "1.0"))
                put("entries", JSONArray(entries))
            })
        }
        return har.toString(2)
    }

    private fun maybeAddMedia(url: String, mimeType: String?) {
        val fake = NetworkRequest(url = url, mimeType = mimeType)
        if (fake.isMedia && !seenMediaUrls.contains(url)) {
            seenMediaUrls.add(url)
            val filename = url.substringAfterLast("/").substringBefore("?")
                .ifBlank { "media_${System.currentTimeMillis()}" }
            val isVideo = mimeType?.startsWith("video") == true
                || listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m3u8", ".mpd", ".ts").any { url.lowercase().contains(it) }
            _detectedMedia.update { it + DetectedMedia(url = url, filename = filename, mimeType = mimeType, isVideo = isVideo, source = "network") }
        }
    }

    fun onResponse(requestId: String, status: Int, headers: Map<String, String>, sizeBytes: Long) {
        _requests.update { list ->
            list.map { req ->
                if (req.id == requestId) req.copy(
                    responseStatus = status,
                    responseHeaders = headers,
                    sizeBytes = sizeBytes,
                    durationMs = System.currentTimeMillis() - req.startTime
                ) else req
            }
        }
    }

    fun addDomMedia(url: String, type: String) {
        if (seenMediaUrls.contains(url)) return
        seenMediaUrls.add(url)
        val filename = url.substringAfterLast("/").substringBefore("?")
            .ifBlank { "media_${System.currentTimeMillis()}" }
        val isVideo = type == "video" || type == "source"
            || listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m3u8").any { url.lowercase().contains(it) }

        _detectedMedia.update { it + DetectedMedia(url = url, filename = filename, mimeType = null, isVideo = isVideo, source = "dom") }
    }

    fun clearDetectedMedia() {
        _detectedMedia.value = emptyList()
        seenMediaUrls.clear()
    }

    fun clear() {
        _requests.value = emptyList()
        _webSocketFrames.value = emptyList()
        clearDetectedMedia()
    }

    fun markBlocked(url: String) {
        _requests.update { list ->
            list.map { if (it.url == url) it.copy(isBlocked = true) else it }
        }
    }

    // --- Interceptor: user-defined runtime rules (block / redirect / header) ---

    fun addRule(rule: InterceptorRule) {
        if (rule.pattern.isBlank()) return
        _rules.update { it + rule }
    }

    fun removeRule(id: String) {
        _rules.update { it.filterNot { r -> r.id == id } }
    }

    fun toggleRule(id: String) {
        _rules.update { it.map { r -> if (r.id == id) r.copy(enabled = !r.enabled) else r } }
    }

    /** First enabled rule whose pattern matches the URL, or null. */
    fun matchRule(url: String): InterceptorRule? {
        val list = _rules.value
        if (list.isEmpty()) return null
        val lower = url.lowercase()
        return list.firstOrNull { it.enabled && lower.contains(it.pattern.lowercase()) }
    }

    /** Suggests a concise pattern (host) for a URL. */
    fun suggestRule(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

enum class RuleAction { BLOCK, REDIRECT, ADD_HEADER }

data class InterceptorRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    val action: RuleAction,
    /** For REDIRECT: target URL. For ADD_HEADER: "Name: Value". Unused for BLOCK. */
    val value: String = "",
    val enabled: Boolean = true
) {
    val summary: String
        get() = when (action) {
            RuleAction.BLOCK -> "Block"
            RuleAction.REDIRECT -> "Redirect → $value"
            RuleAction.ADD_HEADER -> "Header $value"
        }
}
