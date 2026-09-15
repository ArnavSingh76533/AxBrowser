package com.akay.feature.browser.webview

import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

class AxWebChromeClient(
    private val onProgressChange: (Int) -> Unit,
    private val onTitleChange: (String) -> Unit,
    private val onUrlChange: (String) -> Unit,
    private val onShowFullscreen: (View, CustomViewCallback) -> Unit = { _, _ -> },
    private val onHideFullscreen: () -> Unit = {},
    private val onConsoleLog: (level: String, message: String, sourceId: String, line: Int) -> Unit =
        { _, _, _, _ -> }
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChange(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        title?.let { onTitleChange(it) }
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            callback?.onCustomViewHidden()
            return
        }
        onShowFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        onHideFullscreen()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            val level = when (it.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                ConsoleMessage.MessageLevel.TIP -> "info"
                else -> "log"
            }
            ConsoleRing.push(level, it.message() ?: "", it.sourceId() ?: "", it.lineNumber())
            onConsoleLog(level, it.message() ?: "", it.sourceId() ?: "", it.lineNumber())
        }
        return true
    }

    /** JS dialogs go to the agent's DialogBridge queue while the agent is active; stock dialogs otherwise. */
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (result == null || !DialogBridge.enabled.value) return super.onJsAlert(view, url, message, result)
        return DialogBridge.enqueue("alert", url ?: "", message ?: "", "") { accepted, _ -> if (accepted) result.confirm() else result.cancel() }
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        if (result == null || !DialogBridge.enabled.value) return super.onJsConfirm(view, url, message, result)
        return DialogBridge.enqueue("confirm", url ?: "", message ?: "", "") { accepted, _ -> if (accepted) result.confirm() else result.cancel() }
    }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
        if (result == null || !DialogBridge.enabled.value) return super.onJsPrompt(view, url, message, defaultValue, result)
        return DialogBridge.enqueue("prompt", url ?: "", message ?: "", defaultValue ?: "") { accepted, v -> if (accepted) result.confirm(v) else result.cancel() }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        // Grant media capture (camera/mic) so sites like video-call and
        // recorder pages work; other resource requests are denied.
        request?.let {
            val safe = it.resources.filter { res ->
                res == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                    res == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }.toTypedArray()
            if (safe.isNotEmpty()) it.grant(safe) else it.deny()
        }
    }
}
