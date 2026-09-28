package com.akay.feature.browser.extensions

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private immutable package snapshots. A staged extension never executes before native consent. */
class ExtensionManager(context: Context) {
    private val base = File(context.filesDir, "extensions").apply { mkdirs() }
    private val index = AtomicFile(File(base, "registry.json"))
    private val mutable = MutableStateFlow<List<Extension>>(emptyList())
    val extensions = mutable.asStateFlow()
    fun load() {
        val records = runCatching { JSONArray(index.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONArray())
        mutable.value = records.objects().mapNotNull { record -> runCatching {
            val id = record.getString("id")
            require(Regex("[a-p]{32}").matches(id))
            val root = resourceFile(base, record.getString("directory"))
            val manifest = ExtensionManifest(JSONObject(resourceFile(root, "manifest.json").readText()))
            manifest.validateFiles(root)
            Extension(id, root, manifest, record.getBoolean("enabled"), record.getString("source"))
        }.getOrNull() }
    }
    fun install(pending: PendingExtension): Extension {
        val destination = File(base, "${pending.id}-${java.util.UUID.randomUUID()}")
        check(pending.directory.copyRecursively(destination)) { "Could not copy extension" }
        val installed = Extension(pending.id, destination, pending.manifest, true, pending.source)
        val previous = mutable.value.find { it.id == pending.id }
        try { save(mutable.value.filterNot { it.id == pending.id } + installed) }
        catch (e: Exception) { destination.deleteRecursively(); throw e }
        previous?.root?.deleteRecursively()
        pending.directory.deleteRecursively()
        return installed
    }
    fun setEnabled(id: String, enabled: Boolean) = save(mutable.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    fun uninstall(id: String) {
        val previous = mutable.value.find { it.id == id } ?: return
        save(mutable.value.filterNot { it.id == id })
        previous.root.deleteRecursively()
    }
    fun active(id: String): Extension = mutable.value.firstOrNull { it.id == id && it.enabled } ?: error("Extension disabled or uninstalled")
    private fun save(items: List<Extension>) {
        val array = JSONArray(items.map { JSONObject().put("id", it.id).put("directory", it.root.name).put("enabled", it.enabled).put("source", it.source) })
        val stream = index.startWrite()
        try { stream.write(array.toString().toByteArray()); index.finishWrite(stream) }
        catch (e: Exception) { index.failWrite(stream); throw e }
        mutable.value = items
    }
}
