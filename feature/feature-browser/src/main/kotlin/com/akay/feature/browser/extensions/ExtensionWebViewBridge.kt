package com.akay.feature.browser.extensions

import android.annotation.SuppressLint
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A private HTTPS origin per extension. Every request is intercepted; no DNS/network fallback. */
class ExtensionWebViewBridge(private val runtime: ExtensionRuntime) {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(ext: Extension, path: String, kind: ExtensionContext = ExtensionContext.PAGE): WebView {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "Update Android System WebView to run extension pages" }
        if (path != "__ax_background.html") require(resourceFile(ext.root, path).isFile)
        return WebView(runtime.context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            val target = this
            WebViewCompat.addWebMessageListener(this, "AxExtension", setOf(ext.origin)) { _, message, origin, main, proxy ->
                fun allowed() = main && origin.toString().trimEnd('/') == ext.origin &&
                    runCatching { runtime.manager.active(ext.id).root == ext.root }.getOrDefault(false) &&
                    target.url?.startsWith(ext.origin + "/") == true
                if (allowed()) {
                    val ep = runtime.endpoint(target, ext.id, proxy) ?: ExtensionEndpoint(ext.id, kind, target, proxy, { null }, ::allowed)
                    runtime.bridge.receive(ep, message.data ?: "")
                }
            }
            WebViewCompat.addDocumentStartJavaScript(this, runtime.shim(ext), setOf(ext.origin))
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) runtime.report(ext.id, message.message())
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !(request.url.scheme == "https" && request.url.host == "${ext.id}.ax-extension.invalid" && request.url.port in listOf(-1, 443))
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (url.scheme != "https" || url.host != "${ext.id}.ax-extension.invalid" || url.port !in listOf(-1, 443) || request.method != "GET") return response("text/plain", ByteArray(0), 403)
                    return runCatching {
                        val resource = url.path.orEmpty().trimStart('/')
                        when (resource) {
                            "__ax_background.html" -> {
                                val worker = ext.manifest.worker ?: error("No background script")
                                val workerUrl = android.net.Uri.encode(worker, "/")
                                val type = if (ext.manifest.moduleWorker) " type=\"module\"" else ""
                                response("text/html", "<!doctype html><meta charset=utf-8><script src=\"/__ax_imports.js\"></script><script$type src=\"/$workerUrl\"></script>".toByteArray(), background = true)
                            }
                            "__ax_imports.js" -> response("text/javascript", IMPORTS.replace("__WORKER_URL__", JSONObject.quote(ext.origin + "/" + ext.manifest.worker)).toByteArray(), background = true)
                            else -> {
                                val file = resourceFile(ext.root, resource)
                                require(file.isFile)
                                val mime = when (file.extension.lowercase()) {
                                    "js", "mjs" -> "text/javascript"
                                    "html", "htm" -> "text/html"
                                    "css" -> "text/css"
                                    "json" -> "application/json"
                                    "svg" -> "image/svg+xml"
                                    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                                }
                                response(mime, file.readBytes())
                            }
                        }
                    }.getOrElse { response("text/plain", "Resource unavailable".toByteArray(), 404) }
                }
            }
            loadUrl(ext.origin + "/" + path)
        }
    }
    private fun response(mime: String, bytes: ByteArray, status: Int = 200, background: Boolean = false) = WebResourceResponse(
        mime, "UTF-8", status, when(status) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Not Found" },
        mapOf("Content-Security-Policy" to ("default-src 'self'; script-src 'self'" + (if (background) " 'unsafe-eval'" else "") + "; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"), "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
    companion object {
        // Classic-worker adapter: synchronous imports are restricted to the same private package origin.
        private val IMPORTS = """
            window.addEventListener('load', () => self.__axBackgroundReady());
            self.importScripts = function(...paths) { for (const path of paths) {
              const url = new URL(path, __WORKER_URL__); if (url.origin !== location.origin) throw new Error('Remote imports are forbidden');
              const xhr = new XMLHttpRequest(); xhr.open('GET',url.href,false); xhr.send();
              if(xhr.status !== 200) throw new Error('Missing import '+path); (0,eval)(xhr.responseText);
            }};
        """.trimIndent()
    }
}
