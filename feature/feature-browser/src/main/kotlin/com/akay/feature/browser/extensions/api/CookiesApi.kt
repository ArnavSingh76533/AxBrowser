package com.akay.feature.browser.extensions.api

import android.webkit.CookieManager
import com.akay.feature.browser.extensions.Extension
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.coroutines.resume

/** WebView exposes a Cookie header, not a full cookie database. Metadata is intentionally omitted. */
class CookiesApi(private val permissions: PermissionsApi) {
    suspend fun call(ext: Extension, method: String, details: JSONObject): Any {
        val url = details.optString("url")
        check(url.isNotBlank()) { "WebView cookies require an explicit URL; global enumeration is unsupported" }
        BrowserApis.safeUrl(url); permissions.requireHost(ext, url)
        check(!details.has("storeId") && !details.has("partitionKey")) { "Cookie stores/partition keys are unsupported" }
        val manager = CookieManager.getInstance()
        val cookies = manager.getCookie(url).orEmpty().split(';').mapNotNull {
            val pair = it.trim().split('=', limit = 2)
            if (pair.size == 2) JSONObject().put("name", pair[0]).put("value", pair[1]).put("domain", URI(url).host).put("storeId", "0") else null
        }
        return when (method) {
            "get" -> cookies.firstOrNull { it.getString("name") == details.getString("name") } ?: JSONObject.NULL
            "getAll" -> {
                check(!details.has("domain") && !details.has("path") && !details.has("secure") && !details.has("session")) { "Cookie metadata filters are unavailable in WebView" }
                JSONArray(cookies.filter { !details.has("name") || it.getString("name") == details.getString("name") })
            }
            "set", "remove" -> {
                val name = details.getString("name")
                val value = if (method == "remove") "" else details.optString("value")
                check(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name) && value.none { it == ';' || it == '\r' || it == '\n' }) { "Invalid cookie" }
                check(!details.has("domain")) { "Domain cookies are not supported; use a host-only cookie" }
                val path = details.optString("path", "/")
                check(path.startsWith('/') && path.none { it == ';' || it == '\r' || it == '\n' })
                val header = buildString {
                    append("$name=$value; Path=$path")
                    if (method == "remove") append("; Max-Age=0")
                    else if (details.has("expirationDate")) append("; Max-Age=${(details.getDouble("expirationDate") - System.currentTimeMillis() / 1000.0).toLong().coerceAtLeast(0)}")
                    if (details.optBoolean("secure")) append("; Secure")
                    if (details.optBoolean("httpOnly")) append("; HttpOnly")
                    when (details.optString("sameSite")) {
                        "strict" -> append("; SameSite=Strict")
                        "lax" -> append("; SameSite=Lax")
                        "no_restriction" -> { check(details.optBoolean("secure")); append("; SameSite=None") }
                        "", "unspecified" -> Unit
                        else -> error("Invalid sameSite")
                    }
                }
                val accepted = suspendCancellableCoroutine<Boolean> { c -> manager.setCookie(url, header) { if (c.isActive) c.resume(it) } }
                check(accepted) { "WebView rejected the cookie" }; manager.flush()
                if (method == "remove") JSONObject().put("url", url).put("name", name).put("storeId", "0")
                else JSONObject().put("name", name).put("value", value).put("domain", URI(url).host).put("path", path).put("storeId", "0")
            }
            else -> error("Unsupported cookies method")
        }
    }
}
