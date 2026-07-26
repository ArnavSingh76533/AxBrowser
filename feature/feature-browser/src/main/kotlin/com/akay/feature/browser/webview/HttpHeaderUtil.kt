package com.akay.feature.browser.webview

/**
 * Parses a user-entered custom-headers block. Each non-empty line is
 * "Header-Name: value"; lines starting with '#' are comments.
 */
object HttpHeaderUtil {
    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf(':')
            if (idx > 0) {
                val name = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (name.isNotEmpty()) out[name] = value
            }
        }
        return out
    }
}
