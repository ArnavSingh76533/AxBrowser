package com.akay.feature.browser.captcha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Token-based captcha solving through a **2Captcha-compatible** endpoint (`/in.php` + `/res.php`).
 *
 * That API shape is the de-facto standard the cheap solvers speak, so pointing [baseUrl] at a
 * mirror or a self-hosted gateway works without any code change. Nothing here ships a key or a
 * default account: with no key configured the app simply reports that a human (or the operator's
 * own account) is needed, and the agent hands off through `ask_user` instead.
 *
 * Only widgets that actually expose a sitekey are solvable this way. Image grids, sliders and
 * text captchas have no token API at all - they are detected, and then deliberately handed to
 * the person at the keyboard.
 */
class CaptchaSolver(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Submits the widget and polls until a token arrives or [timeoutSec] runs out. */
    suspend fun solve(widget: CaptchaWidget, pageUrl: String, timeoutSec: Int = 120): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (apiKey.isBlank()) error("No captcha solver API key is configured.")
                if (!widget.tokenSolvable) {
                    error("${widget.label} has no token API (needs a solve in the browser).")
                }
                val taskId = submit(widget, pageUrl)
                poll(taskId, timeoutSec)
            }
        }

    private fun submit(widget: CaptchaWidget, pageUrl: String): String {
        val form = FormBody.Builder()
            .add("key", apiKey)
            .add("pageurl", pageUrl)
            .add("json", "1")
            .apply {
                when (widget.type) {
                    "recaptcha_v2" -> {
                        add("method", "userrecaptcha")
                        add("googlekey", widget.sitekey)
                    }
                    "recaptcha_v3" -> {
                        add("method", "userrecaptcha")
                        add("googlekey", widget.sitekey)
                        add("version", "v3")
                        add("action", "verify")
                        add("min_score", "0.5")
                    }
                    "hcaptcha" -> {
                        add("method", "hcaptcha")
                        add("sitekey", widget.sitekey)
                    }
                    "turnstile" -> {
                        add("method", "turnstile")
                        add("sitekey", widget.sitekey)
                    }
                    else -> add("method", "userrecaptcha").add("googlekey", widget.sitekey)
                }
            }
            .build()

        val body = execute("$trimmedBaseUrl/in.php", form)
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: error("Solver returned an unexpected response: ${body.take(200)}")
        if (json.optInt("status", 0) != 1) {
            error("Solver rejected the task: ${json.optString("request", "unknown error")}")
        }
        return json.optString("request").ifBlank { error("Solver returned no task id.") }
    }

    private fun poll(taskId: String, timeoutSec: Int): String {
        val deadline = System.currentTimeMillis() + timeoutSec.coerceIn(10, 600) * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000L)
            val request = Request.Builder()
                .url("$trimmedBaseUrl/res.php?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}" +
                    "&action=get&id=${java.net.URLEncoder.encode(taskId, "UTF-8")}&json=1")
                .get()
                .build()
            val body = runCatching { client.newCall(request).execute().use { it.body?.string() ?: "" } }
                .getOrElse { "" }
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val status = json.optInt("status", 0)
                val value = json.optString("request")
                // "CAPCHA_NOT_READY" (the vendor's own spelling) just means keep polling.
                if (status == 1 && value.isNotBlank()) return value
                if (value.startsWith("ERROR") && !value.contains("NOT_READY", ignoreCase = true)) {
                    error("Solver error: $value")
                }
            }
        }
        error("Solver timed out after ${timeoutSec}s.")
    }

    private fun execute(url: String, form: FormBody): String {
        val request = Request.Builder().url(url).post(form).build()
        return client.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private val trimmedBaseUrl: String get() = baseUrl.trimEnd('/')

    companion object {
        const val DEFAULT_BASE_URL = "https://2captcha.com"

        /** Provider presets offered in the Captcha tab. Only 2Captcha-API-compatible gateways
         *  work with this client - the API shape is what matters, not the brand. */
        val PROVIDERS: List<Pair<String, String>> = listOf(
            "2captcha" to "https://2captcha.com",
            "rucaptcha" to "https://rucaptcha.com",
            "capmonster (2captcha-compatible mode)" to "https://api.capmonster.cloud",
            "custom" to ""
        )

        /**
         * Writes a solved token into the provider's response field and fires its callback.
         *
         * Deliberately best-effort: the checkbox widget lives in a cross-origin iframe, so the
         * only things a page script can touch are the hidden response field and the global
         * callback tree (`___grecaptcha_cfg.clients` for reCAPTCHA). That covers the standard
         * integrations; a page that validates the token out-of-band is reported as such rather
         * than pretended to be solved.
         */
        fun tokenInjectionJs(widget: CaptchaWidget, token: String): String {
            val tokenJson = JSONObject.quote(token)
            val typeJson = JSONObject.quote(widget.type)
            return """
                (function () {
                    var token = $tokenJson;
                    var type = $typeJson;
                    function fill(name) {
                        var found = false;
                        try {
                            var els = document.querySelectorAll('[name="' + name + '"]');
                            for (var i = 0; i < els.length; i++) { els[i].value = token; found = true; }
                            var byId = document.getElementById(name);
                            if (byId) { byId.value = token; found = true; }
                            var fire = document.querySelector('[name="' + name + '"]') || byId;
                            if (fire) {
                                fire.dispatchEvent(new Event('input', { bubbles: true }));
                                fire.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        } catch (e) {}
                        return found;
                    }
                    var filled = false;
                    if (type === 'recaptcha_v2' || type === 'recaptcha_v3') {
                        filled = fill('g-recaptcha-response');
                        try {
                            var cfg = window.___grecaptcha_cfg;
                            if (cfg && cfg.clients) {
                                Object.keys(cfg.clients).forEach(function (k) {
                                    var client = cfg.clients[k];
                                    Object.keys(client).forEach(function (k2) {
                                        var node = client[k2];
                                        if (node && typeof node.callback === 'function') {
                                            try { node.callback(token); } catch (e) {}
                                        }
                                    });
                                });
                            }
                        } catch (e) {}
                    } else if (type === 'hcaptcha') {
                        filled = fill('h-captcha-response');
                    } else if (type === 'turnstile') {
                        filled = fill('cf-turnstile-response');
                    }
                    return JSON.stringify({ filled: filled, tokenLength: token.length, type: type });
                })();
            """.trimIndent()
        }
    }
}
