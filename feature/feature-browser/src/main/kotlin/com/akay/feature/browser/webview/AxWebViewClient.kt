package com.akay.feature.browser.webview

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.akay.feature.browser.adblock.AdBlockEngine
import com.akay.feature.browser.devconsole.NetworkInterceptor
import com.akay.feature.browser.devconsole.NetworkRequest
import java.io.ByteArrayInputStream

class AxWebViewClient(
    private val context: Context,
    private val onPageStarted: (url: String) -> Unit,
    private val onPageFinished: (url: String, title: String?) -> Unit,
    private val onError: (String) -> Unit,
    private val adBlockerEnabled: () -> Boolean = { true },
    private val httpsUpgradeEnabled: () -> Boolean = { true },
    private val onMediaDetected: (url: String, mimeType: String?) -> Unit = { _, _ -> }
) : WebViewClient() {

    private val blockedDomains = mutableSetOf<String>()

    private val videoExtensions = listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m3u8", ".mpd", ".ts", ".flv")
    private val audioExtensions = listOf(".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".opus")

    fun setBlockedDomains(domains: Set<String>) {
        blockedDomains.clear()
        blockedDomains.addAll(domains)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        val url = req.url.toString()

        val netReq = NetworkRequest(
            url = url,
            method = req.method ?: "GET",
            requestHeaders = req.requestHeaders ?: emptyMap()
        )
        NetworkInterceptor.onRequest(netReq)

        if (adBlockerEnabled() && (AdBlockEngine.shouldBlock(url) || isBlocked(url))) {
            AdBlockEngine.onBlocked()
            NetworkInterceptor.markBlocked(url)
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
        }

        val lower = url.lowercase()
        val isVideo = videoExtensions.any { lower.contains(it) }
        val isAudio = audioExtensions.any { lower.contains(it) }
        if (isVideo || isAudio) {
            val mime = guessMime(lower)
            onMediaDetected(url, mime)
        }

        val accept = req.requestHeaders?.get("Accept") ?: ""
        if (accept.contains("video/") || accept.contains("audio/")) {
            onMediaDetected(url, null)
        }

        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (httpsUpgradeEnabled() && url.startsWith("http://")) {
            view?.loadUrl(url.replaceFirst("http://", "https://"))
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onPageStarted(it) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished(url ?: "", view?.title)
    }

    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        onError(description ?: "Unknown error")
    }

    private fun isBlocked(url: String): Boolean {
        if (blockedDomains.isEmpty()) return false
        return try {
            val host = java.net.URI(url).host ?: return false
            blockedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
        } catch (e: Exception) { false }
    }

    private fun guessMime(url: String): String? = when {
        url.contains(".mp4") -> "video/mp4"
        url.contains(".webm") -> "video/webm"
        url.contains(".mkv") -> "video/x-matroska"
        url.contains(".m3u8") -> "application/x-mpegurl"
        url.contains(".mpd") -> "application/dash+xml"
        url.contains(".mp3") -> "audio/mpeg"
        url.contains(".m4a") -> "audio/mp4"
        url.contains(".aac") -> "audio/aac"
        url.contains(".ogg") -> "audio/ogg"
        else -> null
    }
}
