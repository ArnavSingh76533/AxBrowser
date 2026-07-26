package com.akay.core.data.userscript

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A userscript (Tampermonkey/Greasemonkey style). This is how a WebView-based
 * browser is extended — Android WebView cannot load real Chrome/Firefox
 * extensions, but userscripts (content scripts) cover most of the same use
 * cases: site tweaks, custom features, blockers, automation.
 */
data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    /** URL glob pattern. Use "*" for every page, or a host glob like *.example.com */
    val urlPattern: String = "*",
    /** true = run at document end (page finished), false = at document start. */
    val runAtEnd: Boolean = true,
    val code: String
) {
    fun matches(url: String): Boolean = UserScriptCodec.globMatches(urlPattern, url)
}

object UserScriptCodec {

    fun encode(scripts: List<UserScript>): String {
        val arr = JSONArray()
        scripts.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("enabled", s.enabled)
                put("urlPattern", s.urlPattern)
                put("runAtEnd", s.runAtEnd)
                put("code", s.code)
            })
        }
        return arr.toString()
    }

    fun decode(json: String): List<UserScript> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserScript(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", "Untitled"),
                    enabled = o.optBoolean("enabled", true),
                    urlPattern = o.optString("urlPattern", "*"),
                    runAtEnd = o.optBoolean("runAtEnd", true),
                    code = o.optString("code", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Matches a URL glob (asterisk wildcards) against a URL. */
    fun globMatches(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*" || p == "<all_urls>") return true
        val regex = buildString {
            append('^')
            p.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '.', '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '\\', '|' -> {
                        append('\\'); append(c)
                    }
                    else -> append(c)
                }
            }
            append('$')
        }
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
    }

    /**
     * Parses a .user.js file, reading the ==UserScript== metadata block for the
     * name and first @match/@include pattern.
     */
    fun fromUserJs(source: String): UserScript {
        var name = "Imported script"
        var pattern = "*"
        val nameRegex = Regex("""//\s*@name\s+(.+)""")
        val matchRegex = Regex("""//\s*@(?:match|include)\s+(.+)""")
        source.lineSequence().take(200).forEach { line ->
            nameRegex.find(line)?.let { name = it.groupValues[1].trim() }
            if (pattern == "*") matchRegex.find(line)?.let { pattern = it.groupValues[1].trim() }
        }
        return UserScript(name = name, urlPattern = pattern, code = source)
    }
}
