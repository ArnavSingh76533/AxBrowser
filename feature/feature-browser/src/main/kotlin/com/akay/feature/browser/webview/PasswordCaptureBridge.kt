package com.akay.feature.browser.webview

import android.webkit.JavascriptInterface

/**
 * Added to the WebView as "AxPasswordBridge". A small JS snippet injected on
 * every page load watches for submission of any form that contains a
 * password field and reports the captured username/password pair here so
 * the browser can offer to save it. Nothing is sent anywhere off-device;
 * the callback just updates in-memory state that BrowserViewModel observes.
 */
class PasswordCaptureBridge(
    private val onCaptured: (origin: String, username: String, password: String) -> Unit
) {
    @JavascriptInterface
    fun onFormSubmit(origin: String, username: String, password: String) {
        if (password.isBlank()) return
        onCaptured(origin, username, password)
    }

    companion object {
        const val INTERFACE_NAME = "AxPasswordBridge"

        /** Injected once per page load (call from onPageFinished). */
        val CAPTURE_JS = """
            (function() {
                if (window.__axPasswordHooked) return;
                window.__axPasswordHooked = true;
                document.addEventListener('submit', function(e) {
                    try {
                        var form = e.target;
                        if (!form || !form.querySelectorAll) return;
                        var pwField = form.querySelector('input[type="password"]');
                        if (!pwField || !pwField.value) return;
                        var userField = form.querySelector('input[type="email"], input[type="text"], input[autocomplete="username"], input[name*="user" i], input[name*="email" i]');
                        var username = userField ? userField.value : "";
                        if (window.AxPasswordBridge) {
                            window.AxPasswordBridge.onFormSubmit(window.location.origin, username, pwField.value);
                        }
                    } catch (err) {}
                }, true);
            })();
        """.trimIndent()

        /** Fills the first matching login form on the page with a saved credential. */
        fun fillCredentialJs(username: String, password: String): String {
            val escapedUser = username.replace("\\", "\\\\").replace("'", "\\'")
            val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")
            return """
                (function() {
                    var pwField = document.querySelector('input[type="password"]');
                    if (!pwField) return;
                    var form = pwField.form;
                    var userField = form ? form.querySelector('input[type="email"], input[type="text"], input[autocomplete="username"], input[name*="user" i], input[name*="email" i]') : null;
                    function setVal(el, val) {
                        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                        nativeSetter.call(el, val);
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    if (userField) setVal(userField, '$escapedUser');
                    setVal(pwField, '$escapedPass');
                })();
            """.trimIndent()
        }
    }
}
