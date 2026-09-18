package com.akay.feature.browser.agent

import java.net.URI
import java.util.Locale

/**
 * Pure helpers behind the agent's scraping tools: URL handling, what's worth crawling, and turning
 * rows into a file the user can actually open (CSV in Excel/Sheets, JSONL for anything programmatic).
 *
 * Deliberately IO-free so the fiddly parts - dedupe keys, CSV quoting, column ordering - are
 * testable in isolation, and so the crawl loop in the UI layer stays readable.
 */
object ScrapePipeline {

    /** Path extensions that are never a page to crawl: they're assets, binaries or feeds. Keeping
     *  these out of the BFS is the difference between a crawl of pages and a crawl of every icon. */
    private val NOT_A_PAGE = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif", "tif", "tiff",
        "css", "js", "mjs", "map", "json", "xml", "rss", "atom", "txt", "csv", "pdf",
        "ttf", "otf", "woff", "woff2", "eot",
        "mp3", "wav", "ogg", "m4a", "flac", "mp4", "webm", "mov", "avi", "mkv",
        "zip", "rar", "7z", "gz", "tar", "bz2", "xz", "apk", "exe", "msi", "dmg", "iso", "bin"
    )

    /** Fonts/scripts/stylesheets are also served from paths without an extension - catch the usual
     *  CDN/asset path segments too, since a crawl that wanders into /static/js/ burns the page cap. */
    private val ASSET_HINTS = listOf("/static/", "/assets/", "/_next/static/", "/dist/", "/build/", "/cdn-cgi/")

    /**
     * Turns a raw href into a stable absolute http(s) URL, or null when it isn't one (mailto:,
     * javascript:, data:, blob:, tel:, or nothing usable). The fragment is dropped so `#section`
     * links don't inflate the crawl as separate pages.
     */
    fun normalizeUrl(raw: String, base: String? = null): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:") ||
            lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("about:")
        ) return null

        val absolute = if (base.isNullOrBlank()) {
            trimmed
        } else {
            runCatching { URI(base).resolve(trimmed).toString() }.getOrNull() ?: trimmed
        }

        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.query?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        return "$scheme://${host.lowercase(Locale.US)}$port$path$query"
    }

    /** scheme://host[:port] - the cookie/CORS unit, used for the same-origin crawl filter. */
    fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching null
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val port = if (uri.port == -1 || defaultPort) "" else ":${uri.port}"
        "$scheme://$host$port"
    }.getOrNull()

    /** False for assets/binaries and for URLs whose path points at a known asset tree. */
    fun isCrawlable(url: String): Boolean {
        val pathAndQuery = url.substringAfter("://", "").substringAfter("/", "")
        val path = pathAndQuery.substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 1..5 }
        if (ext != null && ext in NOT_A_PAGE) return false
        return ASSET_HINTS.none { pathAndQuery.startsWith(it.trimStart('/')) || pathAndQuery.contains(it) }
    }

    /** Column order = first-seen order across rows, so an export's shape follows the scrape. */
    fun columnsOf(rows: List<Map<String, String>>): List<String> {
        val seen = LinkedHashSet<String>()
        rows.forEach { row -> row.keys.forEach { seen.add(it) } }
        return seen.toList()
    }

    /** Excel/Sheets-friendly CSV: CRLF rows, embedded newlines flattened, quotes doubled. */
    fun csv(rows: List<Map<String, String>>, columns: List<String> = columnsOf(rows)): String {
        val out = StringBuilder()
        out.append(columns.joinToString(",") { csvCell(it) }).append("\r\n")
        rows.forEach { row ->
            out.append(columns.joinToString(",") { csvCell(row[it].orEmpty()) }).append("\r\n")
        }
        return out.toString()
    }

    /** One JSON object per line - the format every scraper pipeline (pandas, jq, BigQuery) eats. */
    fun jsonl(rows: List<Map<String, String>>, columns: List<String> = columnsOf(rows)): String {
        val out = StringBuilder()
        rows.forEach { row ->
            val obj = org.json.JSONObject()
            columns.forEach { col -> obj.put(col, row[col].orEmpty()) }
            out.append(obj.toString()).append("\n")
        }
        return out.toString()
    }

    /** Same rows as one JSON array (handy when the consumer wants valid JSON, not lines). */
    fun json(rows: List<Map<String, String>>, columns: List<String> = columnsOf(rows)): String {
        val arr = org.json.JSONArray()
        rows.forEach { row ->
            val obj = org.json.JSONObject()
            columns.forEach { col -> obj.put(col, row[col].orEmpty()) }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    /** Tab-separated: the format people paste into Excel from a phone without quoting surprises. */
    fun tsv(rows: List<Map<String, String>>, columns: List<String> = columnsOf(rows)): String {
        val out = StringBuilder()
        out.append(columns.joinToString("\t") { it.replace('\t', ' ') }).append("\n")
        rows.forEach { row ->
            out.append(columns.joinToString("\t") { row[it].orEmpty().replace("\t", " ").replace("\n", " ") }).append("\n")
        }
        return out.toString()
    }

    /** Markdown table, for dropping straight into a report (and for the finding/evidence flow). */
    fun markdown(rows: List<Map<String, String>>, columns: List<String> = columnsOf(rows)): String {
        val out = StringBuilder()
        out.append("| ").append(columns.joinToString(" | ") { it.replace("|", "\\|") }).append(" |\n")
        out.append("|").append(columns.joinToString("|") { "---" }).append("|\n")
        rows.forEach { row ->
            out.append("| ").append(
                columns.joinToString(" | ") { (row[it].orEmpty()).replace("\n", " ").replace("|", "\\|") }
            ).append(" |\n")
        }
        return out.toString()
    }

    /** A filesystem-safe stem for an export filename. */
    fun slug(text: String, fallback: String = "dataset"): String = text.trim().lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .ifBlank { fallback }

    private fun csvCell(value: String): String {
        val cleaned = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuotes = cleaned.contains(',') || cleaned.contains('"') || cleaned.contains(';') ||
            cleaned.startsWith(" ") || cleaned.endsWith(" ") || cleaned.contains('\t')
        return if (needsQuotes) "\"" + cleaned.replace("\"", "\"\"") + "\"" else cleaned
    }
}
