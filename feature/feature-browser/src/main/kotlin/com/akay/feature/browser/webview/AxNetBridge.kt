package com.akay.feature.browser.webview

import android.webkit.JavascriptInterface
import com.akay.feature.browser.devconsole.NetworkInterceptor
import com.akay.feature.pentest.intercept.InterceptController
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
            val respHeaders = mutableMapOf<String, String>()
            o.optJSONObject("respHeaders")?.let { hj ->
                hj.keys().forEach { k -> respHeaders[k] = hj.optString(k) }
            }
            // Headers the page itself set on the request (Authorization, X-Api-Key, X-CSRF-Token,
            // custom session headers, ...). Without these, get_curl can only ever reproduce the
            // cookie jar, not header-based auth - which most modern JSON APIs actually use.
            val reqHeaders = mutableMapOf<String, String>()
            o.optJSONObject("reqHeaders")?.let { hj ->
                hj.keys().forEach { k -> reqHeaders[k] = hj.optString(k) }
            }
            val status = if (o.has("status")) o.optInt("status") else null
            NetworkInterceptor.onCapturedRequest(
                url = o.optString("url"),
                method = o.optString("method", "GET"),
                status = status,
                requestBody = o.optString("reqBody", ""),
                requestHeaders = reqHeaders,
                responseBody = o.optString("respBody", ""),
                responseHeaders = respHeaders,
                mimeType = o.optString("type", "").ifBlank { null },
                durationMs = o.optLong("durationMs", 0L),
                source = o.optString("source", "js"),
                wsDirection = o.optString("wsDirection").ifBlank { null }
            )
        }
    }

    /**
     * A request parked by net_capture.js while intercept is on. Unlike [log] - which records
     * traffic that has already happened - this is a live request waiting for a Forward/Drop
     * decision, so it goes to the Intercept queue rather than the Network history. The page's
     * own fetch/XHR promise stays pending until InterceptController pushes a decision back.
     */
    @JavascriptInterface
    fun hold(json: String) {
        // Ignore holds when interception is off: the page can call this interface at any time,
        // and a stray/spoofed call must not be able to fill the operator's pending queue.
        if (!InterceptController.enabled.value) return
        runCatching {
            val o = JSONObject(json)
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("reqHeaders")?.let { hj ->
                hj.keys().forEach { k -> headers[k] = hj.optString(k) }
            }
            InterceptController.hold(
                id = o.optString("id"),
                url = o.optString("url"),
                method = o.optString("method", "GET"),
                headers = headers,
                body = o.optString("reqBody", "")
            )
        }
    }
}
