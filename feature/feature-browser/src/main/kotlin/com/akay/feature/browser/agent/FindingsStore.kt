package com.akay.feature.browser.agent

import com.akay.core.data.storage.AxStorage
import com.akay.core.data.storage.AxStorageCategory
import com.akay.core.data.storage.AxStorageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One recorded vulnerability: what it is, how bad it is, where it lives, and the evidence that
 * proves it. Deliberately small and flat - this is a report's raw material, not a scanner model.
 *
 * @param severity one of [FindingsStore.SEVERITIES] (anything else is coerced to "info").
 * @param description what's wrong *and* why it matters (impact) and, when known, how to fix it -
 *   it's rendered into the report's description/impact section verbatim.
 * @param evidence the exact proof: a `get_curl` command, a request/response pair, a snippet of JS.
 */
data class Finding(
    val id: String,
    val title: String,
    val severity: String,
    val url: String,
    val description: String,
    val evidence: String,
    val createdAt: Long
)

/** A finding plus where it was persisted, so the caller can tell the user honestly. */
data class FindingSaved(
    val finding: Finding,
    val total: Int,
    /** Null when writing findings.json failed outright (the finding is still held in memory). */
    val storage: AxStorageResult?
)

/** A written bug report: where it landed and what the export did to the evidence. */
data class ReportExport(
    val filename: String,
    val displayPath: String,
    val savedToSharedStorage: Boolean,
    val findingCount: Int,
    val bySeverity: Map<String, Int>,
    /** How many auth-looking header values / token params were redacted from the evidence. */
    val redactions: Int,
    val sanitized: Boolean
)

/**
 * The bug-bounty output layer: findings the agent (or the user) records during a session, kept in
 * memory as a StateFlow for live UI and mirrored to `Agent/findings.json` through AxStorage so a
 * session survives process death.
 *
 * Persistence is deliberately best-effort: a storage failure degrades to in-memory findings rather
 * than losing what the agent just found.
 */
@Singleton
class FindingsStore @Inject constructor(
    private val axStorage: AxStorage
) {
    private val mutex = Mutex()
    private var loaded = false

    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    /** Live list for the UI (and for anything that wants to observe findings as they're added). */
    val findings: StateFlow<List<Finding>> = _findings.asStateFlow()

    /** Every recorded finding, newest-session state loaded from disk on first read. */
    suspend fun all(): List<Finding> = mutex.withLock {
        ensureLoaded()
        _findings.value
    }

    /** Findings matching [severity] (case-insensitive), or all of them when it's null/blank. */
    suspend fun all(severity: String?): List<Finding> {
        val all = all()
        if (severity.isNullOrBlank()) return all
        val wanted = normalizeSeverity(severity)
        return all.filter { it.severity == wanted }
    }

    suspend fun add(
        title: String,
        severity: String,
        url: String,
        description: String,
        evidence: String
    ): FindingSaved = mutex.withLock {
        ensureLoaded()
        val finding = Finding(
            id = newId(),
            title = title.trim().take(200),
            severity = normalizeSeverity(severity),
            url = url.trim(),
            description = description.trim(),
            evidence = evidence.trim(),
            createdAt = System.currentTimeMillis()
        )
        val next = _findings.value + finding
        _findings.value = next
        FindingSaved(finding = finding, total = next.size, storage = persist(next))
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        ensureLoaded()
        val next = _findings.value.filterNot { it.id == id }
        if (next.size == _findings.value.size) return@withLock false
        _findings.value = next
        persist(next)
        true
    }

    suspend fun clear(): Int = mutex.withLock {
        ensureLoaded()
        val n = _findings.value.size
        _findings.value = emptyList()
        persist(emptyList())
        n
    }

    /**
     * Writes every recorded finding as one Markdown report shaped to paste into HackerOne /
     * Bugcrowd / YesWeHack, and returns where it landed.
     *
     * [sanitized] (the default, and what the tool uses unless told otherwise) redacts auth-looking
     * header values and token-shaped query/body params from the evidence: a report is a document
     * you hand to someone else, so live cookies and bearer tokens must not ride along. The report
     * always states which mode it used, and how many values were redacted.
     */
    suspend fun exportReport(
        program: String,
        platform: String,
        sanitized: Boolean = true
    ): ReportExport? = mutex.withLock {
        ensureLoaded()
        val current = _findings.value
        if (current.isEmpty()) return@withLock null
        val (markdown, redactions) = buildReport(current, program, platform, sanitized)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val slug = program.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "findings" }
        val filename = "${slug}_report_$stamp.md"
        val storage = runCatching {
            axStorage.writeText(AxStorageCategory.AGENT, filename, markdown, "text/markdown")
        }.getOrNull()
        ReportExport(
            filename = filename,
            displayPath = storage?.displayPath ?: "unwritten (storage failed)",
            savedToSharedStorage = storage?.savedToSharedStorage == true,
            findingCount = current.size,
            bySeverity = current.groupingBy { it.severity }.eachCount(),
            redactions = redactions,
            sanitized = sanitized
        )
    }

    // ---------- internals ----------

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { axStorage.readText(AxStorageCategory.AGENT, FILE_NAME) }.getOrNull()
        if (raw.isNullOrBlank()) return
        _findings.value = runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    private suspend fun persist(list: List<Finding>): AxStorageResult? = runCatching {
        axStorage.writeText(AxStorageCategory.AGENT, FILE_NAME, serialize(list), "application/json")
    }.getOrNull()

    private fun newId(): String = "f" + UUID.randomUUID().toString().replace("-", "").take(8)

    private fun normalizeSeverity(raw: String): String {
        val v = raw.trim().lowercase(Locale.US)
        return when {
            v.startsWith("crit") -> "critical"
            v.startsWith("high") || v == "severe" -> "high"
            v.startsWith("med") || v == "moderate" -> "medium"
            v.startsWith("low") || v == "minor" -> "low"
            else -> "info"
        }
    }

    private fun serialize(list: List<Finding>): String {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("title", f.title)
                    put("severity", f.severity)
                    put("url", f.url)
                    put("description", f.description)
                    put("evidence", f.evidence)
                    put("createdAt", f.createdAt)
                }
            )
        }
        return JSONObject().apply {
            put("version", 1)
            put("findings", arr)
        }.toString(2)
    }

    private fun parse(raw: String): List<Finding> {
        // Tolerates both the document shape we write and a bare JSON array (e.g. a hand-edited file).
        val arr = runCatching { JSONObject(raw).optJSONArray("findings") }.getOrNull() ?: JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            if (title.isBlank()) return@mapNotNull null
            Finding(
                id = o.optString("id").ifBlank { newId() },
                title = title,
                severity = normalizeSeverity(o.optString("severity")),
                url = o.optString("url"),
                description = o.optString("description"),
                evidence = o.optString("evidence"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    companion object {
        const val FILE_NAME = "findings.json"

        /** Accepted severities, worst first - also the report's section order. */
        val SEVERITIES = listOf("critical", "high", "medium", "low", "info")

        private fun rank(severity: String) = SEVERITIES.indexOf(severity).let { if (it < 0) SEVERITIES.lastIndex else it }

        private val AUTH_HEADER = Regex(
            "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|" +
                "x-auth-token|x-access-token|x-session-token|x-csrf-token|x-xsrf-token|x-amz-security-token)" +
                "\\s*:\\s*)(.+)$"
        )
        private val SECRET_PARAM = Regex(
            "(?i)\\b(access_token|refresh_token|id_token|api[_-]?key|apikey|auth|session|sessionid|" +
                "password|passwd|pwd|secret|signature|sig|token)=([^&\\s\"'`]+)"
        )

        /**
         * Redacts auth-looking header values and token-shaped params. Returns the scrubbed text
         * and how many values were replaced, so the report can say what it did.
         */
        fun sanitize(text: String): Pair<String, Int> {
            if (text.isBlank()) return text to 0
            var count = 0
            val headers = AUTH_HEADER.replace(text) { m ->
                count++
                m.groupValues[1] + "<redacted>"
            }
            val params = SECRET_PARAM.replace(headers) { m ->
                count++
                "${m.groupValues[1]}=<redacted>"
            }
            return params to count
        }

        /**
         * Builds the Markdown report. Pure (no IO) so the shape can be unit-tested and inspected
         * without touching storage.
         */
        fun buildReport(
            findings: List<Finding>,
            program: String,
            platform: String,
            sanitized: Boolean
        ): Pair<String, Int> {
            var redactions = 0
            val ordered = findings.sortedWith(compareBy({ rank(it.severity) }, { -it.createdAt }))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            val counts = findings.groupingBy { it.severity }.eachCount()
            val bar = counts.entries.sortedBy { rank(it.key) }
                .joinToString(" · ") { "${it.key} ${it.value}" }
                .ifBlank { "none" }

            val md = StringBuilder()
            md.append("# ${program.trim().ifBlank { "Security findings" }} — vulnerability report\n\n")
            md.append("| | |\n|---|---|\n")
            md.append("| **Program / target** | ${program.trim().ifBlank { "_(not specified)_" }} |\n")
            if (platform.isNotBlank()) md.append("| **Platform** | ${platform.trim()} |\n")
            md.append("| **Generated** | $stamp |\n")
            md.append("| **Findings** | ${findings.size} ($bar) |\n")
            val hygiene = if (sanitized) "auth headers and token params redacted at export" else "included as captured"
            md.append("| **Evidence hygiene** | $hygiene |\n")
            md.append("\nCaptured on-device with AxBrowser (request/response evidence comes from its network capture).\n\n")

            md.append("## Summary\n\n")
            md.append("| # | Severity | Finding | URL |\n|---|---|---|---|\n")
            ordered.forEachIndexed { i, f ->
                // Redaction counts are only accumulated in the detail sections below - the summary
                // table redacts the same title/URL values, which would double-count them.
                val (t, _) = if (sanitized) sanitize(f.title) else f.title to 0
                val (u, _) = if (sanitized) sanitize(f.url) else f.url to 0
                md.append("| ${i + 1} | **${f.severity.uppercase(Locale.US)}** | ${t.replace("|", "\\|")} | ${u.replace("|", "\\|")} |\n")
            }
            md.append("\n---\n\n")

            ordered.forEachIndexed { i, f ->
                val (title, tR) = if (sanitized) sanitize(f.title) else f.title to 0
                val (url, uR) = if (sanitized) sanitize(f.url) else f.url to 0
                val (desc, dR) = if (sanitized) sanitize(f.description) else f.description to 0
                val (evidence, eR) = if (sanitized) sanitize(f.evidence) else f.evidence to 0
                redactions += tR + uR + dR + eR

                md.append("## ${i + 1}. [${f.severity.uppercase(Locale.US)}] $title\n\n")
                if (url.isNotBlank()) md.append("**URL:** $url\n\n")
                md.append("**Finding ID:** ${f.id}  \n")
                md.append("**Recorded:** ${ts(f.createdAt)}\n\n")
                md.append("### Description, impact and suggested fix\n\n")
                md.append(desc.ifBlank { "_(no description recorded)_" }.trim()).append("\n\n")
                md.append("### Evidence\n\n")
                md.append("```\n").append(evidence.ifBlank { "(no evidence recorded)" }.trim()).append("\n```\n\n")
                md.append("---\n\n")
            }

            if (sanitized && redactions > 0) {
                md.append(
                    "_$redactions auth-looking value(s) (cookie / authorization / token params) were redacted " +
                        "from this report. Re-run with sanitized: false only if the recipient may see live credentials._\n"
                )
            }
            return md.toString() to redactions
        }

        private fun ts(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(millis))
    }
}
