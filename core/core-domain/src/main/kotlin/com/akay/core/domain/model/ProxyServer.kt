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
    /**
     * The rule string Android WebView's ProxyController expects, e.g.
     * "socks5://1.2.3.4:1080". Deliberately excludes credentials -
     * Chromium's proxy rule grammar (scheme=host:port / scheme://host:port)
     * has no concept of embedded userinfo, and will reject the whole rule
     * with "Invalid Proxy URL" if you try. Authenticated proxies are instead
     * handled by answering the HTTP 407 challenge WebView raises via
     * WebViewClient.onReceivedHttpAuthRequest (see AxWebViewClient).
     */
    fun toProxyRule(): String {
        val scheme = when (type) {
            ProxyType.HTTP -> "http"
            ProxyType.HTTPS -> "https"
            ProxyType.SOCKS4 -> "socks4"
            ProxyType.SOCKS5 -> "socks5"
        }
        val safeHost = host.trim()
        return "$scheme://$safeHost:$port"
    }

    val requiresAuth: Boolean get() = !username.isNullOrBlank()
}
