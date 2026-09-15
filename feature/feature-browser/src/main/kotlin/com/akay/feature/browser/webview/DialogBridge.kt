package com.akay.feature.browser.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JS dialog queue for agent control (browser_handle_dialog). AxWebChromeClient enqueues
 * alert/confirm/prompt dialogs here and suppresses the system UI; the agent answers them
 * from chat via [respond]. A 10s auto-accept safety timeout keeps the page from wedging
 * when nobody is watching the queue, mirroring InterceptController's auto-forward.
 */
object DialogBridge {

    data class PendingDialog(
        val id: String,
        val url: String,
        val kind: String, // alert | confirm | prompt
        val message: String,
        val defaultValue: String = "",
        val heldAt: Long = System.currentTimeMillis()
    )

    private val _enabled = MutableStateFlow(false)
    /** When true, JS dialogs are parked for the agent instead of shown as stock dialogs. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (!on) resolveAll(accept = true)
    }

    private val _pending = MutableStateFlow<List<PendingDialog>>(emptyList())
    val pending: StateFlow<List<PendingDialog>> = _pending.asStateFlow()

    private val callbacks = ConcurrentHashMap<String, (accepted: Boolean, value: String) -> Unit>()
    private val idCounter = AtomicLong(1)

    /** Called from AxWebChromeClient (main thread) instead of showing the system dialog. Returns true if queued. */
    fun enqueue(kind: String, url: String, message: String, defaultValue: String, onResult: (accepted: Boolean, value: String) -> Unit): Boolean {
        val id = "d${idCounter.getAndIncrement()}"
        callbacks[id] = onResult
        _pending.update { it + PendingDialog(id, url, kind, message, defaultValue) }
        // Safety timeout: auto-accept (with the dialog's default value) so a parked
        // dialog can never wedge the WebView, like Burp's auto-forward.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            resolveById(id, accept = true, value = defaultValue)
        }, 10_000L)
        return true
    }

    /** Agent-facing answer: applies to the oldest pending dialog. */
    fun respond(accept: Boolean, promptText: String? = null): String {
        val oldest = _pending.value.firstOrNull()
            ?: return "No pending dialog."
        val value = if (accept) (promptText ?: oldest.defaultValue) else ""
        return if (resolveById(oldest.id, accept, value)) "Dialog ${oldest.kind} ${if (accept) "accepted" else "dismissed"}." else "No pending dialog."
    }

    /** Resolves every pending dialog as accepted (used when the agent turns the bridge off). */
    private fun resolveAll(accept: Boolean) {
        while (_pending.value.isNotEmpty()) resolveById(_pending.value.first().id, accept = accept, value = "")
    }

    private fun resolveById(id: String, accept: Boolean, value: String): Boolean {
        val cb = callbacks.remove(id) ?: return false
        _pending.update { list -> list.filterNot { it.id == id } }
        runCatching { cb(accept, value) }
        return true
    }
}
