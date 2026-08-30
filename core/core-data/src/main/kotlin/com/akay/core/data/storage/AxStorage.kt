package com.akay.core.data.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.akay.core.data.datastore.AxPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The subfolders AxStorage organizes files into inside the user's chosen root folder (or inside
 *  app-private storage as a fallback) - matches how the agent's own tools already group their
 *  output (screenshots, exports, saved requests) into.
 */
enum class AxStorageCategory(val folderName: String) {
    SCREENSHOTS("Screenshots"),
    DOWNLOADS("Downloads"),
    AGENT("Agent"),
    OTHER("Other")
}

data class AxStorageResult(
    /** True if this actually landed in the user-visible shared folder; false means it fell back
     *  to app-private storage (no folder chosen yet, or the chosen one became inaccessible - e.g.
     *  an SD card was removed, or the user revoked the permission from system settings). */
    val savedToSharedStorage: Boolean,
    /** A human-readable location description, for telling the user/agent where a file landed. */
    val displayPath: String,
    /** The actual on-disk File when it fell back to internal storage (existing tools that already
     *  work with java.io.File paths - e.g. attaching to Telegram, opening for read - can keep
     *  using this directly). Null when saved to shared storage instead (that's a content:// URI,
     *  not a File - see [documentUri]). */
    val internalFile: File?,
    /** The content:// URI string when saved to shared storage. Null when it fell back internal. */
    val documentUri: String?
)

/**
 * Opt-in shared storage for everything AxBrowser's agent (and eventually downloads) writes -
 * screenshots, HAR/Postman/OpenAPI exports, saved curl requests, agent-created script/text files.
 *
 * Android 10+ gives no way for an app to silently create or write to a raw path like
 * /storage/emulated/0/AxBrowser - that needs either the heavyweight MANAGE_EXTERNAL_STORAGE
 * permission (disproportionate for this) or a one-time SAF folder picker the user taps through
 * once. This class is written around that constraint rather than against it: if the user has
 * picked a folder (see AxPreferences.axStorageRootUri, set via Settings), everything gets written
 * there as real files under that folder, visible in any file manager, surviving app uninstall. If
 * they haven't - or the previously-granted folder became inaccessible - every write silently
 * falls back to app-private storage exactly like before this feature existed. The person is never
 * blocked either way; the shared folder is a strict upgrade, never a requirement.
 */
@Singleton
class AxStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AxPreferences
) {
    private suspend fun rootTree(): DocumentFile? {
        val uriString = preferences.axStorageRootUri.first()
        if (uriString.isBlank()) return null
        return runCatching {
            val uri = android.net.Uri.parse(uriString)
            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree != null && tree.exists() && tree.canWrite()) tree else null
        }.getOrNull()
    }

    private fun categoryDir(root: DocumentFile, category: AxStorageCategory): DocumentFile? =
        root.findFile(category.folderName) ?: root.createDirectory(category.folderName)

    private fun internalCategoryDir(category: AxStorageCategory): File =
        File(context.filesDir, category.folderName.lowercase()).apply { mkdirs() }

    /** Writes [bytes] as [filename] under [category]. Falls back to app-private storage
     *  automatically - callers don't need to branch on whether a folder is configured. */
    suspend fun writeFile(category: AxStorageCategory, filename: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): AxStorageResult =
        withContext(Dispatchers.IO) {
            val root = rootTree()
            if (root != null) {
                val result = runCatching {
                    val dir = categoryDir(root, category) ?: error("Couldn't create ${category.folderName} folder")
                    // Overwrite semantics: replace an existing file of the same name rather than
                    // accumulating "file (1).png", "file (2).png", ... on every re-save.
                    dir.findFile(filename)?.delete()
                    val doc = dir.createFile(mimeType, filename) ?: error("Couldn't create $filename")
                    context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
                        ?: error("Couldn't open output stream for $filename")
                    AxStorageResult(
                        savedToSharedStorage = true,
                        displayPath = "AxBrowser/${category.folderName}/$filename",
                        internalFile = null,
                        documentUri = doc.uri.toString()
                    )
                }
                if (result.isSuccess) return@withContext result.getOrThrow()
                // Fall through to internal storage on any failure (folder revoked mid-session,
                // storage full, etc.) rather than losing the file entirely.
            }
            val dir = internalCategoryDir(category)
            val file = File(dir, filename)
            file.writeBytes(bytes)
            AxStorageResult(
                savedToSharedStorage = false,
                displayPath = file.absolutePath,
                internalFile = file,
                documentUri = null
            )
        }

    suspend fun writeText(category: AxStorageCategory, filename: String, text: String, mimeType: String = "text/plain"): AxStorageResult =
        writeFile(category, filename, text.toByteArray(Charsets.UTF_8), mimeType)

    /** Lists filenames under [category], from wherever they're actually stored (shared folder if
     *  configured, otherwise app-private storage) - so the agent/user always sees the real state
     *  regardless of which backend is active. */
    suspend fun listFiles(category: AxStorageCategory): List<String> = withContext(Dispatchers.IO) {
        val root = rootTree()
        if (root != null) {
            val dir = categoryDir(root, category)
            val names = dir?.listFiles()?.mapNotNull { it.name }?.sorted()
            if (names != null) return@withContext names
        }
        internalCategoryDir(category).listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    /** Reads a previously-written text file back, checking the shared folder first (if
     *  configured) then falling back to app-private storage - mirrors [writeFile]'s fallback so a
     *  file written before a folder was configured (or after one was revoked) can still be found. */
    suspend fun readText(category: AxStorageCategory, filename: String): String? = withContext(Dispatchers.IO) {
        rootTree()?.let { root ->
            val doc = categoryDir(root, category)?.findFile(filename)
            if (doc != null && doc.exists()) {
                runCatching {
                    context.contentResolver.openInputStream(doc.uri)?.use { it.bufferedReader().readText() }
                }.getOrNull()?.let { return@withContext it }
            }
        }
        val file = File(internalCategoryDir(category), filename)
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    /** True once the user has picked a folder AND it's still actually accessible - what Settings/
     *  the agent should check before saying "saving to your chosen folder" vs "saving in-app". */
    suspend fun hasConfiguredFolder(): Boolean = rootTree() != null

    /** Display name of the configured root folder, for Settings' status line - e.g. "AxBrowser"
     *  if that's literally what the user named the folder they picked. Null if none configured. */
    suspend fun rootFolderDisplayName(): String? = rootTree()?.name
}
