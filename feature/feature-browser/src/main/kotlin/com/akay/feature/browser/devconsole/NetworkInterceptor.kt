package com.akay.feature.browser.devconsole

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
    val source: String = "webview"
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

    /** Builds a runnable curl command reproducing this request. */
    fun toCurl(): String {
        val sb = StringBuilder("curl")
        if (!method.equals("GET", ignoreCase = true)) sb.append(" -X ").append(method)
        sb.append(" '").append(url).append("'")
        requestHeaders.forEach { (k, v) ->
            sb.append(" \\\n  -H '").append(k).append(": ").append(v.replace("'", "'\\''")).append("'")
        }
        if (requestBody.isNotBlank()) {
            sb.append(" \\\n  --data-raw '").append(requestBody.replace("'", "'\\''")).append("'")
        }
        return sb.toString()
    }
}

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

    private const val MAX_ENTRIES = 500
    private val seenMediaUrls = mutableSetOf<String>()

    fun onRequest(request: NetworkRequest) {
        _requests.update { current ->
            val updated = current + request
            if (updated.size > MAX_ENTRIES) updated.drop(updated.size - MAX_ENTRIES) else updated
        }
        maybeAddMedia(request.url, request.mimeType)
    }

    /** Feeds a request/response captured by the in-page JS bridge (fetch/XHR). */
    fun onCapturedRequest(
        url: String,
        method: String,
        status: Int?,
        requestBody: String,
        responseBody: String,
        responseHeaders: Map<String, String>,
        mimeType: String?,
        durationMs: Long,
        source: String
    ) {
        val req = NetworkRequest(
            url = url,
            method = method,
            responseStatus = status,
            responseHeaders = responseHeaders,
            mimeType = mimeType,
            durationMs = durationMs,
            requestBody = requestBody,
            responseBody = responseBody,
            source = source
        )
        _requests.update { current ->
            val updated = current + req
            if (updated.size > MAX_ENTRIES) updated.drop(updated.size - MAX_ENTRIES) else updated
        }
        maybeAddMedia(url, mimeType)
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
