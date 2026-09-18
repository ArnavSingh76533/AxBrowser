package com.akay.feature.browser.agent

import com.akay.core.data.storage.AxStorage
import com.akay.core.data.storage.AxStorageCategory
import com.akay.core.data.storage.AxStorageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What the session dataset currently holds, so the agent can check before exporting. */
data class DatasetSnapshot(
    val rowCount: Int,
    val columns: List<String>,
    /** Where the rows came from (page URL / "crawl" / "auto_paginate" / "agent"), most rows first. */
    val sources: List<Pair<String, Int>>,
    /** Rows the cap refused to take. Non-zero means the run outgrew the session buffer. */
    val droppedRows: Int
)

/** A written dataset: where it landed, how big it is, and whether the buffer was reset. */
data class DatasetExport(
    val format: String,
    val filename: String,
    val displayPath: String,
    val savedToSharedStorage: Boolean,
    val rowCount: Int,
    val byteCount: Int,
    val cleared: Boolean
)

/** Rows accepted into the buffer and the new total. */
data class DatasetAdd(val added: Int, val total: Int)

/**
 * The session's scraped rows, held in memory and written out on demand.
 *
 * This exists because the agent's per-action step budget made multi-page scraping impossible: every
 * `scrape_structured` result lived only in the chat transcript, so collecting 200 rows across 12
 * pages meant the model re-reading (and re-emitting) all of it. Here the rows accumulate as a side
 * effect of each scrape, and `export_dataset` writes the whole thing once.
 *
 * Rows are kept in memory only - a scrape is a session's work, not a database. Nothing is lost until
 * the process dies, and the export is what makes it durable.
 */
@Singleton
class DatasetStore @Inject constructor(
    private val axStorage: AxStorage
) {
    private val mutex = Mutex()
    private val rows = mutableListOf<Map<String, String>>()
    private var dropped = 0

    /** Rows accepted and the total now held. [source] (usually the page URL) and [page] are added as
     *  columns so an export says where each row came from - unless the scraped fields already use
     *  those names, in which case the scraped values win. */
    suspend fun add(newRows: List<Map<String, String>>, source: String? = null, page: Int? = null): DatasetAdd =
        mutex.withLock {
            var added = 0
            newRows.forEach { row ->
                if (rows.size >= MAX_ROWS) {
                    dropped += 1
                    return@forEach
                }
                val merged = LinkedHashMap<String, String>()
                row.forEach { (key, value) ->
                    val name = key.trim()
                    if (name.isNotEmpty()) merged[name] = value
                }
                if (!source.isNullOrBlank() && !merged.containsKey(SOURCE_COLUMN)) merged[SOURCE_COLUMN] = source
                if (page != null && !merged.containsKey(PAGE_COLUMN)) merged[PAGE_COLUMN] = page.toString()
                rows += merged
                added += 1
            }
            DatasetAdd(added = added, total = rows.size)
        }

    suspend fun snapshot(): DatasetSnapshot = mutex.withLock {
        DatasetSnapshot(
            rowCount = rows.size,
            columns = ScrapePipeline.columnsOf(rows),
            sources = rows.groupingBy { it[SOURCE_COLUMN] ?: "(unattributed)" }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value },
            droppedRows = dropped
        )
    }

    suspend fun clear(): Int = mutex.withLock {
        val n = rows.size
        rows.clear()
        dropped = 0
        n
    }

    /**
     * Writes every held row to a file and returns where it landed, or null when there is nothing to
     * write. [format] is one of [FORMATS] (anything else falls back to CSV). [clearAfter] resets the
     * buffer, for when a run is finished and the next task should start from empty.
     */
    suspend fun export(format: String, filename: String, clearAfter: Boolean): DatasetExport? = mutex.withLock {
        if (rows.isEmpty()) return@withLock null
        val fmt = normalizeFormat(format)
        val columns = ScrapePipeline.columnsOf(rows)
        val body = when (fmt) {
            "jsonl" -> ScrapePipeline.jsonl(rows, columns)
            "json" -> ScrapePipeline.json(rows, columns)
            "tsv" -> ScrapePipeline.tsv(rows, columns)
            "md" -> ScrapePipeline.markdown(rows, columns)
            else -> ScrapePipeline.csv(rows, columns)
        }
        val mime = when (fmt) {
            "jsonl", "json" -> "application/json"
            "tsv" -> "text/tab-separated-values"
            "md" -> "text/markdown"
            else -> "text/csv"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = if (filename.isBlank()) {
            "dataset_$stamp.$fmt"
        } else {
            "${ScrapePipeline.slug(filename.substringBeforeLast('.'), "dataset")}.$fmt"
        }
        val written: AxStorageResult? = runCatching {
            axStorage.writeText(AxStorageCategory.AGENT, name, body, mime)
        }.getOrNull()
        val count = rows.size
        if (clearAfter) {
            rows.clear()
            dropped = 0
        }
        DatasetExport(
            format = fmt,
            filename = name,
            displayPath = written?.displayPath ?: "unwritten (storage failed)",
            savedToSharedStorage = written?.savedToSharedStorage == true,
            rowCount = count,
            byteCount = body.toByteArray(Charsets.UTF_8).size,
            cleared = clearAfter
        )
    }

    private fun normalizeFormat(raw: String): String {
        val v = raw.trim().lowercase(Locale.US).trimStart('.')
        return when (v) {
            "jsonl", "ndjson", "json-lines", "jsonlines" -> "jsonl"
            "json" -> "json"
            "tsv", "tab", "tsvfile" -> "tsv"
            "md", "markdown" -> "md"
            else -> "csv"
        }
    }

    companion object {
        /** Hard cap on the session buffer - 4 fields x 20k rows is still a small file, and this only
         *  exists so a runaway crawl can't exhaust the app's heap. Overflow is counted, not silent. */
        const val MAX_ROWS = 20_000

        /** Export formats the tool accepts (CSV is the default and the one that opens anywhere). */
        val FORMATS = listOf("csv", "jsonl", "json", "tsv", "md")

        private const val SOURCE_COLUMN = "source"
        private const val PAGE_COLUMN = "page"
    }
}
