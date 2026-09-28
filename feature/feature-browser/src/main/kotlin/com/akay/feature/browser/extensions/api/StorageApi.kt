package com.akay.feature.browser.extensions.api

import android.content.Context
import android.util.AtomicFile
import com.akay.feature.browser.extensions.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StorageApi(context: Context, private val changed: (String, String, JSONObject) -> Unit) {
    private val base = File(context.filesDir, "extension-storage").apply { mkdirs() }
    private val session = mutableMapOf<String, JSONObject>()
    private val local = mutableMapOf<String, JSONObject>()
    fun call(id: String, area: String, method: String, args: JSONArray): Any {
        require(area in listOf("local", "session")) { "storage.$area is unsupported" }
        val data = if (area == "session") session.getOrPut(id) { JSONObject() } else local.getOrPut(id) {
            runCatching { JSONObject(AtomicFile(File(base, "$id.json")).openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
        }
        val keys = when (val value = args.opt(0)) {
            is String -> listOf(value)
            is JSONArray -> value.strings()
            is JSONObject -> value.keys().asSequence().toList()
            else -> data.keys().asSequence().toList()
        }
        if (method == "get") return JSONObject().apply { keys.forEach { key ->
            if (data.has(key)) put(key, data.get(key)) else (args.opt(0) as? JSONObject)?.let { put(key, it.get(key)) }
        } }
        if (method == "getBytesInUse") return keys.sumOf { key -> if (data.has(key)) key.toByteArray().size + data.get(key).toString().toByteArray().size else 0 }
        val next = JSONObject(data.toString())
        when (method) {
            "set" -> args.getJSONObject(0).let { values -> values.keys().forEach { next.put(it, values.get(it)) } }
            "remove" -> keys.forEach { next.remove(it) }
            "clear" -> next.keys().asSequence().toList().forEach { next.remove(it) }
            else -> error("Unsupported storage method")
        }
        require(next.toString().toByteArray().size <= 5 * 1024 * 1024) { "QUOTA_BYTES exceeded (5 MiB)" }
        if (area == "local") {
            val file = AtomicFile(File(base, "$id.json")); val out = file.startWrite()
            try { out.write(next.toString().toByteArray()); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
            local[id] = next
        } else session[id] = next
        val changes = JSONObject()
        (data.keys().asSequence().toSet() + next.keys().asSequence().toSet()).forEach { key ->
            if (data.opt(key)?.toString() != next.opt(key)?.toString()) changes.put(key, JSONObject().apply {
                if (data.has(key)) put("oldValue", data.get(key)); if (next.has(key)) put("newValue", next.get(key))
            })
        }
        if (changes.length() > 0) changed(id, area, changes)
        return JSONObject.NULL
    }
    fun clearSession(id: String) { session.remove(id) }
    fun uninstall(id: String) { session.remove(id); local.remove(id); AtomicFile(File(base, "$id.json")).delete() }
}
