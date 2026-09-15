package com.akay.feature.browser.agent

import com.akay.feature.browser.webview.unwrapJsString
import org.json.JSONObject

/**
 * Kotlin side of the Playwright-MCP-shaped snapshot engine: parses the SNAPSHOT JS result,
 * keeps a ref→path map for the current page state, and renders a compact token-friendly
 * text for the model. Refs are invalidated on navigation (call [invalidate] from
 * onPageStarted) and whenever a fresh snapshot is taken (which re-numbers refs).
 */
class PageSnapshot {

    data class Node(
        val ref: String,
        val path: String,
        val role: String,
        val name: String,
        val value: String?,
        val extra: String?
    )

    private val refs = mutableMapOf<String, Node>()
    private var snapshotText: String = ""
    var lastUrl: String = ""
        private set

    /** Runs [snapshotJs] (AgentJs.SNAPSHOT) via [evalJs], stores refs, returns the compact text for the model. */
    suspend fun take(evalJs: suspend (String) -> String, maxNodes: Int = 150): String {
        val raw = evalJs(AgentJs.SNAPSHOT)
        return parse(raw, maxNodes)
    }

    /** Parses the SNAPSHOT JSON result and returns the model-facing text. */
    fun parse(rawJson: String, maxNodes: Int = 150): String {
        refs.clear()
        val cleaned = unwrapJsString(rawJson)
        val obj = runCatching { JSONObject(cleaned) }.getOrNull()
            ?: return "(snapshot failed: unparseable result)"
        lastUrl = obj.optString("url")
        val nodesArray = obj.optJSONArray("nodes") ?: org.json.JSONArray()
        val forms = obj.optJSONArray("forms") ?: org.json.JSONArray()
        val sb = StringBuilder()
        sb.append("Page: ${obj.optString("title")} ($lastUrl)\n")
        sb.append("URL: $lastUrl\n")
        for (i in 0 until forms.length()) {
            val f = forms.optJSONObject(i) ?: continue
            val action = f.optString("form_action")
            if (action.isNotBlank()) {
                sb.append("form: ${f.optString("form_method")} $action\n")
            }
        }
        var included = 0
        for (i in 0 until nodesArray.length()) {
            val n = nodesArray.optJSONObject(i) ?: continue
            val role = n.optString("role")
            val name = n.optString("name")
            val path = n.optString("path")
            val ref = n.optString("ref")
            val value = n.optString("value").takeIf { it.isNotBlank() }
            val extra = n.optString("extra").takeIf { it.isNotBlank() }
            refs[ref] = Node(ref, path, role, name, value, extra)
            if (included >= maxNodes) continue
            included++
            val valuePart = value?.let { " value=\"$it\"" } ?: ""
            val extraPart = extra?.let { "  [$it]" } ?: ""
            sb.append("- ${role} \"$name\" ref=$ref$valuePart$extraPart\n")
        }
        if (nodesArray.length() > maxNodes) {
            sb.append("(showing $maxNodes of ${nodesArray.length()} nodes; pass {\"maxNodes\": 300} to see more)\n")
        }
        snapshotText = sb.toString()
        return snapshotText
    }

    /** Resolves [ref] ("s12") to its CSS path, or null if stale/unknown. */
    fun pathFor(ref: String): String? = refs[ref]?.path

    fun nodeFor(ref: String): Node? = refs[ref]

    fun refCount(): Int = refs.size

    fun invalidate() {
        refs.clear()
    }
}
