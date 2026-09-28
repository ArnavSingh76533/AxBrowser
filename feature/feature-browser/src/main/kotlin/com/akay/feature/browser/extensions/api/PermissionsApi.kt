package com.akay.feature.browser.extensions.api

import android.content.Context
import com.akay.feature.browser.extensions.Extension
import com.akay.feature.browser.extensions.MatchPattern
import com.akay.feature.browser.extensions.strings
import org.json.JSONArray
import org.json.JSONObject

class PermissionsApi(context: Context) {
    private val prefs = context.getSharedPreferences("extension-grants", Context.MODE_PRIVATE)
    var requestApproval: suspend (Extension, Set<String>, Set<String>) -> Boolean = { _, _, _ -> false }
    fun granted(ext: Extension) = ext.manifest.permissions + prefs.getStringSet("${ext.id}:permissions", emptySet()).orEmpty()
    fun hosts(ext: Extension) = ext.manifest.hosts + prefs.getStringSet("${ext.id}:origins", emptySet()).orEmpty().map(::MatchPattern)
    fun require(ext: Extension, permission: String) { check(permission in granted(ext)) { "Permission required: $permission" } }
    fun requireHost(ext: Extension, url: String) { check(hosts(ext).any { it.matches(url) }) { "Host permission required for $url" } }
    suspend fun call(ext: Extension, method: String, details: JSONObject): Any {
        val permissions = details.optJSONArray("permissions").strings().toSet()
        val origins = details.optJSONArray("origins").strings().toSet()
        return when (method) {
            "getAll" -> JSONObject().put("permissions", JSONArray(granted(ext).toList())).put("origins", JSONArray(hosts(ext).map { it.value }))
            "contains" -> granted(ext).containsAll(permissions) && hosts(ext).map { it.value }.containsAll(origins)
            "request" -> {
                check((ext.manifest.optionalPermissions + ext.manifest.permissions).containsAll(permissions) &&
                    (ext.manifest.optionalHosts + ext.manifest.hosts).map { it.value }.containsAll(origins)) { "Permissions must be declared in the manifest" }
                check(CapabilityRegistry.permissions.containsAll(permissions)) { "Requested permission has no implementation" }
                if (granted(ext).containsAll(permissions) && hosts(ext).map { it.value }.containsAll(origins)) true
                else if (requestApproval(ext, permissions, origins)) {
                    prefs.edit().putStringSet("${ext.id}:permissions", prefs.getStringSet("${ext.id}:permissions", emptySet()).orEmpty() + permissions)
                        .putStringSet("${ext.id}:origins", prefs.getStringSet("${ext.id}:origins", emptySet()).orEmpty() + origins).commit()
                    true
                } else false
            }
            "remove" -> {
                check(permissions.intersect(ext.manifest.permissions).isEmpty() && origins.intersect(ext.manifest.hosts.map { it.value }.toSet()).isEmpty()) { "Required permissions cannot be removed; disable the extension instead" }
                prefs.edit().putStringSet("${ext.id}:permissions", prefs.getStringSet("${ext.id}:permissions", emptySet()).orEmpty() - permissions)
                    .putStringSet("${ext.id}:origins", prefs.getStringSet("${ext.id}:origins", emptySet()).orEmpty() - origins).commit()
                true
            }
            else -> error("Unsupported permissions method")
        }
    }
    fun clear(id: String) { prefs.edit().remove("$id:permissions").remove("$id:origins").commit() }
}
