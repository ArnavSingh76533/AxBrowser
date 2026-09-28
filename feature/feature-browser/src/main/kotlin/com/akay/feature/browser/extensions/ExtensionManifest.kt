package com.akay.feature.browser.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

/** Chrome match patterns, deliberately restricted to HTTP(S). File/private schemes are never granted. */
class MatchPattern(val value: String) {
    private val parts = Regex("^(\\*|http|https)://(\\*|\\*\\.[A-Za-z0-9.-]+|[A-Za-z0-9.-]+)(/.*)$").matchEntire(value)
    init { require(value == "<all_urls>" || parts != null) { "Unsupported host pattern: $value" } }
    fun matches(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme !in listOf("http", "https") || uri.host == null || uri.userInfo != null) return false
        if (value == "<all_urls>") return true
        val (scheme, host, path) = parts!!.destructured
        val name = uri.host.lowercase()
        (scheme == "*" || scheme == uri.scheme) &&
            (host == "*" || name == host.lowercase() ||
                (host.startsWith("*.") && (name == host.drop(2).lowercase() || name.endsWith("." + host.drop(2).lowercase())))) &&
            glob(path, (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: ""))
    }.getOrDefault(false)
    fun origins(): Set<String> {
        if (value == "<all_urls>") return setOf("https://*", "http://*")
        val (scheme, host) = parts!!.destructured
        val hosts = if (host.startsWith("*.")) listOf(host, host.drop(2)) else listOf(host)
        return (if (scheme == "*") listOf("http", "https") else listOf(scheme)).flatMap { s -> hosts.map { "$s://$it" } }.toSet()
    }
    companion object {
        fun glob(pattern: String, value: String) = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(value)
    }
}

data class ContentScript(val matches: List<MatchPattern>, val excludes: List<MatchPattern>, val js: List<String>, val css: List<String>, val runAt: String) {
    fun matches(url: String) = matches.any { it.matches(url) } && excludes.none { it.matches(url) }
}

data class ExtensionManifest(val json: JSONObject) {
    val name = json.getString("name")
    val version = json.getString("version")
    val description = json.optString("description")
    val permissions = json.optJSONArray("permissions").strings().toSet()
    val optionalPermissions = json.optJSONArray("optional_permissions").strings().toSet()
    val hosts = json.optJSONArray("host_permissions").strings().map(::MatchPattern)
    val optionalHosts = json.optJSONArray("optional_host_permissions").strings().map(::MatchPattern)
    val worker = json.optJSONObject("background")?.optString("service_worker")?.takeIf { it.isNotBlank() }
    val moduleWorker = json.optJSONObject("background")?.optString("type") == "module"
    val popup = json.optJSONObject("action")?.optString("default_popup")?.takeIf { it.isNotBlank() }
    val options = (json.optJSONObject("options_ui")?.optString("page") ?: json.optString("options_page")).takeIf { it.isNotBlank() }
    val scripts = json.optJSONArray("content_scripts").objects().map {
        require(!it.optBoolean("all_frames")) { "Subframe content scripts are not supported yet" }
        require(it.optString("world", "ISOLATED") == "ISOLATED") { "MAIN-world extension scripts are not supported" }
        require(!it.has("include_globs") && !it.has("exclude_globs") && !it.optBoolean("match_about_blank") && !it.optBoolean("match_origin_as_fallback")) { "This content-script matching mode is not supported" }
        val runAt = it.optString("run_at", "document_idle")
        require(runAt in listOf("document_start", "document_end", "document_idle")) { "Invalid run_at" }
        ContentScript(it.getJSONArray("matches").strings().map(::MatchPattern), it.optJSONArray("exclude_matches").strings().map(::MatchPattern), it.optJSONArray("js").strings(), it.optJSONArray("css").strings(), runAt)
    }
    init {
        require(json.getInt("manifest_version") == 3) { "Only Manifest V3 extensions are supported" }
        require(name.isNotBlank() && name.length <= 256 && Regex("[0-9]+(\\.[0-9]+){0,3}").matches(version)) { "Invalid extension name/version" }
    }
    fun validateFiles(root: File) {
        (listOfNotNull(worker, popup, options) + scripts.flatMap { it.js + it.css } +
            (json.optJSONObject("icons")?.let { o -> o.keys().asSequence().map { o.getString(it) }.toList() } ?: emptyList())).forEach {
            require(resourceFile(root, it).isFile) { "Missing extension resource: $it" }
        }
    }
}

fun resourceFile(root: File, path: String): File {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000')) { "Invalid resource path" }
    val base = root.canonicalFile
    return File(base, path).canonicalFile.also { require(it.path.startsWith(base.path + File.separator)) { "Resource escapes extension directory" } }
}

data class Extension(val id: String, val root: File, val manifest: ExtensionManifest, val enabled: Boolean, val source: String) {
    val origin get() = "https://$id.ax-extension.invalid"
}
