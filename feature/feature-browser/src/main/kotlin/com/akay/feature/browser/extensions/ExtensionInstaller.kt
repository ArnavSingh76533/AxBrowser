package com.akay.feature.browser.extensions

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class PendingExtension(val id: String, val directory: File, val manifest: ExtensionManifest, val source: String)

class ExtensionInstaller(private val context: Context) {
    private val staging = File(context.cacheDir, "extension-staging").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()
    fun importPackage(uri: Uri): PendingExtension = context.contentResolver.openInputStream(uri)!!.use { unpack(readLimited(it, MAX_PACKAGE), null, "Local package") }
    fun importFolder(uri: Uri): PendingExtension {
        val dir = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        var bytes = 0L
        var count = 0
        fun copy(source: DocumentFile, destination: File, depth: Int) {
            require(depth <= 20) { "Folder nesting exceeds limit" }
            for (child in source.listFiles()) {
                require(++count <= 4096) { "Too many extension files" }
                val name = child.name ?: error("Unnamed resource")
                require(!name.contains('/') && name != "." && name != "..")
                val target = resourceFile(destination, name)
                if (child.isDirectory) { target.mkdirs(); copy(child, target, depth + 1) }
                else context.contentResolver.openInputStream(child.uri)!!.use { input ->
                    val data = readLimited(input, MAX_FILE)
                    bytes += data.size; require(bytes <= MAX_EXPANDED) { "Extension exceeds size limit" }
                    target.writeBytes(data)
                }
            }
        }
        return try {
            copy(DocumentFile.fromTreeUri(context, uri) ?: error("Cannot open folder"), dir, 0)
            pending(dir, localId(), "Unpacked local folder (unsigned)")
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    /** Best effort public update service. No cookies/login scraping or third-party download services. */
    fun fromStore(id: String): PendingExtension {
        require(Regex("[a-p]{32}").matches(id)) { "Invalid Chrome Web Store ID" }
        var url = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=148.0.0.0&acceptformat=crx3&x=" + Uri.encode("id=$id&installsource=ondemand&uc")
        repeat(6) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code in 300..399) {
                    val next = response.request.url.resolve(response.header("Location") ?: error("Missing package redirect")) ?: error("Invalid package redirect")
                    require(next.scheme == "https" && (next.host == "google.com" || next.host.endsWith(".google.com") || next.host == "googleusercontent.com" || next.host.endsWith(".googleusercontent.com"))) { "Untrusted package redirect" }
                    url = next.toString()
                } else {
                    require(response.isSuccessful) { "Store package unavailable (HTTP ${response.code}). Import a publisher-provided CRX or folder instead." }
                    val bytes = response.body?.byteStream()?.use { readLimited(it, MAX_PACKAGE) } ?: error("Empty Store response")
                    return unpack(bytes, id, "Chrome Web Store retrieval; CRX identity verified")
                }
            }
        }
        error("Too many Store redirects")
    }
    private fun unpack(bytes: ByteArray, expectedId: String?, source: String): PendingExtension {
        val signed = bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "Cr24"
        require(expectedId == null || signed) { "Store did not return a signed CRX3 package" }
        val verified = if (signed) CrxVerifier.verify(bytes, expectedId) else null
        val dir = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            val seen = mutableSetOf<String>()
            ZipInputStream((verified?.archive ?: bytes).inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 4096) { "Too many package entries" }
                    val file = resourceFile(dir, entry.name.trimEnd('/'))
                    require(seen.add(file.path.lowercase())) { "Duplicate package entry" }
                    if (entry.isDirectory) file.mkdirs() else {
                        val data = readLimited(zip, MAX_FILE)
                        total += data.size; require(total <= MAX_EXPANDED) { "Expanded package exceeds limit" }
                        file.parentFile!!.mkdirs(); file.writeBytes(data)
                    }
                    zip.closeEntry()
                }
            }
            return pending(dir, verified?.id ?: localId(), if (verified == null) "$source (unsigned ZIP)" else source)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    private fun pending(dir: File, id: String, source: String): PendingExtension {
        val manifestFile = resourceFile(dir, "manifest.json")
        require(manifestFile.length() in 1..(1024 * 1024)) { "Place manifest.json at the root of the package/folder" }
        val manifest = ExtensionManifest(JSONObject(manifestFile.readText()))
        manifest.validateFiles(dir)
        return PendingExtension(id, dir, manifest, source)
    }
    companion object {
        const val MAX_PACKAGE = 32 * 1024 * 1024
        const val MAX_FILE = 8 * 1024 * 1024
        const val MAX_EXPANDED = 64 * 1024 * 1024
        fun storeId(url: String): String? = runCatching {
            val u = java.net.URI(url)
            if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443)) return null
            val valid = (u.host == "chromewebstore.google.com" && u.path.startsWith("/detail/")) ||
                (u.host == "chrome.google.com" && u.path.startsWith("/webstore/detail/"))
            if (!valid) null else u.path.trimEnd('/').substringAfterLast('/').takeIf { Regex("[a-p]{32}").matches(it) }
        }.getOrNull()
        fun readLimited(input: InputStream, maximum: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size().toLong() + n <= maximum) { "Resource exceeds size limit" }; out.write(buffer, 0, n) }
            return out.toByteArray()
        }
        private fun localId() = UUID.randomUUID().toString().replace("-", "").map { ('a'.code + it.digitToInt(16)).toChar() }.joinToString("")
    }
}
