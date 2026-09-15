package com.akay.core.domain.model

/**
 * A single request/response pair — the shared currency of the pentest tools.
 * Captured traffic, Repeater sends, Intruder results and history persistence all
 * speak this one shape. Headers are an ordered list so duplicates and exact
 * ordering survive (raw-socket replay needs both).
 */
data class HttpTransaction(
    val id: Long = 0,
    val sessionId: String = "",
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val responseStatus: Int? = null,
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val responseBody: ByteArray? = null,
    val responseTimeMs: Long = 0,
    val source: String = "capture", // capture | repeater | intruder | manual
    val createdAt: Long = System.currentTimeMillis()
) {
    val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""
    val responseBodyText: String get() = responseBody?.toString(Charsets.UTF_8) ?: ""
    val host: String get() = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    /** Serializes headers + body into a raw HTTP/1.1 request string (request line, CRLF headers, body). */
    fun toRawRequest(): String = buildString {
        val pathQuery = runCatching {
            val uri = java.net.URI(url)
            val path = uri.rawPath ?: "/"
            if (uri.rawQuery.isNullOrBlank()) path else "$path?${uri.rawQuery}"
        }.getOrDefault("/")
        append("${method.uppercase()} $pathQuery $httpVersion\r\n")
        if (headers.none { it.first.equals("Host", ignoreCase = true) } && host.isNotBlank()) {
            append("Host: $host\r\n")
        }
        headers.forEach { (k, v) -> append("$k: $v\r\n") }
        append("\r\n")
        if (body != null) append(bodyText)
    }.toString()

    override fun equals(other: Any?): Boolean = other is HttpTransaction &&
        id == other.id && sessionId == other.sessionId && method == other.method && url == other.url &&
        httpVersion == other.httpVersion && headers == other.headers &&
        (body?.contentEquals(other.body) ?: (other.body == null)) &&
        responseStatus == other.responseStatus && responseHeaders == other.responseHeaders &&
        (responseBody?.contentEquals(other.responseBody) ?: (other.responseBody == null)) &&
        responseTimeMs == other.responseTimeMs && source == other.source && createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + (responseStatus ?: 0)
        result = 31 * result + responseHeaders.hashCode()
        result = 31 * result + (responseBody?.contentHashCode() ?: 0)
        result = 31 * result + responseTimeMs.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    companion object {
        /** Parses a raw HTTP request (request line + headers + optional body) back into a transaction. */
        fun fromRawRequest(raw: String, sessionId: String = "", source: String = "repeater"): HttpTransaction {
            val normalized = raw.replace("\r\n", "\n")
            val parts = normalized.split("\n\n", limit = 2)
            val headLines = parts[0].split("\n").filter { it.isNotBlank() }
            val requestLine = headLines.firstOrNull()?.trim() ?: ""
            val regex = Regex("^([A-Za-z]+)\\s+(\\S+)\\s+(HTTP/\\S+)$")
            val match = regex.find(requestLine)
            val method = match?.groupValues?.get(1)?.uppercase() ?: "GET"
            val target = match?.groupValues?.get(2) ?: "/"
            val version = match?.groupValues?.get(3) ?: "HTTP/1.1"
            val headers = headLines.drop(1).mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            val bodyBytes = parts.getOrNull(1)?.toByteArray(Charsets.UTF_8)
            // Absolute target -> full URL; relative -> rebuild from Host header.
            val url = if (target.startsWith("http://") || target.startsWith("https://")) target else {
                val hostHeader = headers.firstOrNull { it.first.equals("Host", true) }?.second ?: ""
                val scheme = if (hostHeader.endsWith(":443")) "https" else "http"
                "$scheme://${hostHeader}$target"
            }
            return HttpTransaction(
                sessionId = sessionId, method = method, url = url, httpVersion = version,
                headers = headers, body = bodyBytes, source = source
            )
        }
    }
}

/**
 * One-way glue interface: feature-browser hands captured requests to the pentest
 * module (Send to Repeater) without feature-pentest depending on feature-browser.
 */
interface TransactionSink {
    suspend fun accept(transaction: HttpTransaction)
}
