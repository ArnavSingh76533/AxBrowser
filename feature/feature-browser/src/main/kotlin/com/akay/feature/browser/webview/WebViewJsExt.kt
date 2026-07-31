package com.akay.feature.browser.webview

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Runs [script] and suspends until the JS result comes back (must be called from the main thread, like WebView itself). */
suspend fun WebView.evalJs(script: String): String = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { result ->
        if (cont.isActive) cont.resume(result ?: "null")
    }
}

/** Unwraps the JSON-quoted string evaluateJavascript returns for scripts that themselves return a JS string. */
fun unwrapJsString(raw: String): String {
    if (raw == "null") return ""
    return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault(raw)
}
