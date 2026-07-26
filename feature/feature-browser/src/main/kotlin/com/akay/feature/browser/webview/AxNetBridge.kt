package com.akay.feature.browser.webview

import android.webkit.JavascriptInterface
import com.akay.feature.browser.devconsole.NetworkInterceptor
import org.json.JSONObject

/**
 * Receives fetch/XHR capture events from net_capture.js and feeds them into the
 * dev console. Only a single logging method is exposed to JavaScript, so no
 * privileged native capability is reachable from the page.
 */
class AxNetBridge {

    @JavascriptInterface
    fun log(json: String) {
        runCatching {
            val o = JSONObject(json)
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("respHeaders")?.let { hj ->
                hj.keys().forEach { k -> headers[k] = hj.optString(k) }
            }
            val status = if (o.has("status")) o.optInt("status") else null
            NetworkInterceptor.onCapturedRequest(
                url = o.optString("url"),
                method = o.optString("method", "GET"),
                status = status,
                requestBody = o.optString("reqBody", ""),
                responseBody = o.optString("respBody", ""),
                responseHeaders = headers,
                mimeType = o.optString("type", "").ifBlank { null },
                durationMs = o.optLong("durationMs", 0L),
                source = o.optString("source", "js")
            )
        }
    }
}
