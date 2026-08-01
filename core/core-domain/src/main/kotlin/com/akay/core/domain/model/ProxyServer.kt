package com.akay.core.domain.model

enum class ProxyType { HTTP, HTTPS, SOCKS4, SOCKS5 }

enum class ProxyRotationMode { MANUAL, PER_NAVIGATION, TIMED }

data class ProxyServer(
    val id: String,
    val label: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val enabled: Boolean,
    val sortOrder: Int
) {
    /** The rule string Android WebView's ProxyController expects, e.g. "socks5://1.2.3.4:1080". */
    fun toProxyRule(): String {
        val scheme = when (type) {
            ProxyType.HTTP -> "http"
            ProxyType.HTTPS -> "https"
            ProxyType.SOCKS4 -> "socks4"
            ProxyType.SOCKS5 -> "socks5"
        }
        val auth = if (!username.isNullOrBlank()) "$username:${password.orEmpty()}@" else ""
        return "$scheme://$auth$host:$port"
    }
}
