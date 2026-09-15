# AxBrowser — Build Prompt v7 · Phase 0 + Phase 1 (Execution)
# Paste this into the coding agent working on the Android repo.
# Implements planv07.md foundations (§4) + the Playwright-shaped agent toolset (§3).
# NO gating of any kind. All tools always available.

---

## 0. GROUND RULES

1. Modify existing files; create new files only where listed. Do not rewrite working code.
2. All async = Coroutines + Flow. All UI = Jetpack Compose. Hilt DI everywhere.
3. No new third-party dependencies. OkHttp 4.12 (already present), Compose, Hilt, Room.
4. Write every file completely. No `// TODO` stubs.
5. Do NOT add consent prompts, confirmations, Pentest Mode toggles, or scope checks.
   No tool asks, no tool confirms, no tool checks a setting before running.

---

## PHASE 0 — FOUNDATIONS (Room v7 + HttpTransaction + CurlParser)

### 0.1 Domain model

**Create file:** `core/core-domain/src/main/kotlin/com/akay/core/domain/model/HttpTransaction.kt`

```kotlin
package com.akay.core.domain.model

data class HttpTransaction(
    val id: Long = 0,
    val sessionId: String,
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<Pair<String, String>>,   // ordered, duplicates allowed
    val body: ByteArray? = null,
    val responseStatus: Int? = null,
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val responseBody: ByteArray? = null,
    val responseTimeMs: Long = 0,
    val source: String,                        // capture | repeater | intruder | manual
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean = other is HttpTransaction && other.id == id && other.createdAt == createdAt
    override fun hashCode(): Int = (id * 31 + createdAt).toInt()
}
```

### 0.2 Room v7 entities

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/entity/HttpTransactionEntity.kt`

```kotlin
package com.akay.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "http_transactions")
data class HttpTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val method: String,
    val url: String,
    val httpVersion: String,
    val headersJson: String,          // ordered JSON array of [name, value] pairs
    val bodyB64: String?,             // base64; bodies may be binary
    val responseStatus: Int?,
    val responseHeadersJson: String,
    val responseBodyB64: String?,
    val responseTimeMs: Long,
    val source: String,
    val createdAt: Long
)
```

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/entity/FuzzJobEntity.kt`

```kotlin
package com.akay.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fuzz_jobs")
data class FuzzJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val requestJson: String,          // serialized HttpTransaction of the template
    val engine: String,               // SNIPER | CLUSTERBOMB
    val positionsJson: String,        // ["§param§", ...]
    val payloadSetsJson: String,      // [[...], [...]] one array per position
    val status: String,               // QUEUED | RUNNING | DONE | STOPPED
    val createdAt: Long
)
```

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/entity/FuzzResultEntity.kt`

```kotlin
package com.akay.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fuzz_results")
data class FuzzResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: String,
    val payloadJson: String,          // position → payload map
    val status: Int,
    val length: Long,
    val timeMs: Long,
    val matched: Boolean,
    val createdAt: Long
)
```

### 0.3 DAOs

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/dao/HttpTransactionDao.kt`

```kotlin
package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.HttpTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HttpTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: HttpTransactionEntity): Long

    @Query("SELECT * FROM http_transactions WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    fun observeSession(sessionId: String, limit: Int = 2000): Flow<List<HttpTransactionEntity>>

    @Query("SELECT * FROM http_transactions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 2000): List<HttpTransactionEntity>

    @Query("SELECT * FROM http_transactions WHERE id = :id")
    suspend fun byId(id: Long): HttpTransactionEntity?

    @Query("DELETE FROM http_transactions WHERE id NOT IN (SELECT id FROM http_transactions ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 2000)

    @Query("DELETE FROM http_transactions")
    suspend fun clear()
}
```

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/dao/FuzzDao.kt`

```kotlin
package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.FuzzJobEntity
import com.akay.core.data.db.entity.FuzzResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FuzzDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: FuzzJobEntity)

    @Query("SELECT * FROM fuzz_jobs ORDER BY createdAt DESC")
    fun observeJobs(): Flow<List<FuzzJobEntity>>

    @Query("UPDATE fuzz_jobs SET status = :status WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String)

    @Insert
    suspend fun insertResult(result: FuzzResultEntity)

    @Query("SELECT * FROM fuzz_results WHERE jobId = :jobId ORDER BY id ASC")
    fun observeResults(jobId: String): Flow<List<FuzzResultEntity>>

    @Query("DELETE FROM fuzz_results WHERE jobId = :jobId")
    suspend fun clearResults(jobId: String)

    @Query("DELETE FROM fuzz_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)
}
```

### 0.4 Database v6 → v7

**Modify file:** `core/core-data/src/main/kotlin/com/akay/core/data/db/AxDatabase.kt`

Add to the `@Database` annotation (keep version incremented — currently 6):

```kotlin
@Database(
    version = 7,
    entities = [
        // ... all 12 existing entities stay ...
        HttpTransactionEntity::class,
        FuzzJobEntity::class,
        FuzzResultEntity::class
    ]
)
```

Add the migration and register it wherever migrations are provided to the builder:

```kotlin
val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `http_transactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `method` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `httpVersion` TEXT NOT NULL,
                `headersJson` TEXT NOT NULL,
                `bodyB64` TEXT,
                `responseStatus` INTEGER,
                `responseHeadersJson` TEXT NOT NULL,
                `responseBodyB64` TEXT,
                `responseTimeMs` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `fuzz_jobs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `requestJson` TEXT NOT NULL,
                `engine` TEXT NOT NULL,
                `positionsJson` TEXT NOT NULL,
                `payloadSetsJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `fuzz_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `jobId` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `status` INTEGER NOT NULL,
                `length` INTEGER NOT NULL,
                `timeMs` INTEGER NOT NULL,
                `matched` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_transactions_createdAt` ON `http_transactions` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_transactions_sessionId` ON `http_transactions` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuzz_results_jobId` ON `fuzz_results` (`jobId`)")
    }
}
```

> If the DB builds with `fallbackToDestructiveMigration` in debug only, keep that — but the
> `Migration(6,7)` must exist and be registered regardless, and the `core-testing` migration
> test must validate it.

### 0.5 Mappers + repository

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/mapper/TransactionMapper.kt`

```kotlin
package com.akay.core.data.mapper

import com.akay.core.data.db.entity.HttpTransactionEntity
import com.akay.core.domain.model.HttpTransaction
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import android.util.Base64

object TransactionMapper {

    private val json = Json { ignoreUnknownKeys = true }
    private val pairList = ListSerializer(ListSerializer(String.serializer()))

    fun toDomain(e: HttpTransactionEntity): HttpTransaction = HttpTransaction(
        id = e.id,
        sessionId = e.sessionId,
        method = e.method,
        url = e.url,
        httpVersion = e.httpVersion,
        headers = json.decodeFromString(pairList, e.headersJson).map { it[0] to it[1] },
        body = e.bodyB64?.let { Base64.decode(it, Base64.NO_WRAP) },
        responseStatus = e.responseStatus,
        responseHeaders = json.decodeFromString(pairList, e.responseHeadersJson).map { it[0] to it[1] },
        responseBody = e.responseBodyB64?.let { Base64.decode(it, Base64.NO_WRAP) },
        responseTimeMs = e.responseTimeMs,
        source = e.source,
        createdAt = e.createdAt
    )

    fun toEntity(t: HttpTransaction): HttpTransactionEntity = HttpTransactionEntity(
        id = t.id,
        sessionId = t.sessionId,
        method = t.method,
        url = t.url,
        httpVersion = t.httpVersion,
        headersJson = json.encodeToString(pairList, t.headers.map { listOf(it.first, it.second) }),
        bodyB64 = t.body?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        responseStatus = t.responseStatus,
        responseHeadersJson = json.encodeToString(pairList, t.responseHeaders.map { listOf(it.first, it.second) }),
        responseBodyB64 = t.responseBody?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        responseTimeMs = t.responseTimeMs,
        source = t.source,
        createdAt = t.createdAt
    )
}
```

**Create file:** `core/core-domain/src/main/kotlin/com/akay/core/domain/repository/TransactionRepository.kt`

```kotlin
package com.akay.core.domain.repository

import com.akay.core.domain.model.HttpTransaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    suspend fun save(tx: HttpTransaction): Long
    fun observeSession(sessionId: String): Flow<List<HttpTransaction>>
    suspend fun recent(limit: Int = 2000): List<HttpTransaction>
    suspend fun byId(id: Long): HttpTransaction?
    suspend fun trim(keep: Int = 2000)
    suspend fun clear()
}
```

**Create file:** `core/core-data/src/main/kotlin/com/akay/core/data/repository/TransactionRepositoryImpl.kt`

```kotlin
package com.akay.core.data.repository

import com.akay.core.data.db.dao.HttpTransactionDao
import com.akay.core.data.mapper.TransactionMapper.toDomain
import com.akay.core.data.mapper.TransactionMapper.toEntity
import com.akay.core.domain.model.HttpTransaction
import com.akay.core.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val dao: HttpTransactionDao
) : TransactionRepository {

    override suspend fun save(tx: HttpTransaction): Long = dao.insert(tx.toEntity())
    override fun observeSession(sessionId: String): Flow<List<HttpTransaction>> =
        dao.observeSession(sessionId).map { list -> list.map { it.toDomain() } }
    override suspend fun recent(limit: Int): List<HttpTransaction> = dao.recent(limit).map { it.toDomain() }
    override suspend fun byId(id: Long): HttpTransaction? = dao.byId(id)?.toDomain()
    override suspend fun trim(keep: Int) = dao.trim(keep)
    override suspend fun clear() = dao.clear()
}
```

**Add Hilt binding** in the existing data DI module (`DataModule.kt` or equivalent):

```kotlin
@Binds
abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository
```

And provide the new DAOs next to the existing DAO providers:

```kotlin
@Provides @Singleton fun provideHttpTransactionDao(db: AxDatabase): HttpTransactionDao = db.httpTransactionDao()
@Provides @Singleton fun provideFuzzDao(db: AxDatabase): FuzzDao = db.fuzzDao()
```

(Add abstract `httpTransactionDao()` / `fuzzDao()` accessors to `AxDatabase`.)

### 0.6 NetworkInterceptor → Transaction + Proxy History persistence

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/devconsole/NetworkInterceptor.kt`

Add:

```kotlin
fun toTransaction(req: NetworkRequest, sessionId: String, source: String = "capture"): HttpTransaction =
    HttpTransaction(
        sessionId = sessionId,
        method = req.method,
        url = req.url,
        headers = req.requestHeaders.entries.flatMap { (k, v) ->
            // NetworkRequest stores combined values with ", " — split back where safe
            v.split(", ").map { k to it }
        },
        body = req.requestBody,
        responseStatus = req.status,
        responseHeaders = req.responseHeaders.entries.flatMap { (k, v) ->
            v.split(", ").map { k to it }
        },
        responseBody = req.responseBody,
        responseTimeMs = req.durationMs,
        source = source,
        createdAt = req.timestamp
    )
```

(Adapt field names to the actual `NetworkRequest` model — `method`, `url`,
`requestHeaders: Map<String, String>`, `requestBody: ByteArray?`, `status`, `durationMs`,
`timestamp`. If bodies are stored as Strings, base64-encode here instead.)

**Proxy History persistence (opt-in flag, plain setting — not a permission gate):**

**Create file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/devconsole/HistoryPersister.kt`

```kotlin
package com.akay.feature.browser.devconsole

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.akay.core.domain.repository.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists captured requests to Room ("Proxy History that survives process death").
 * Toggle is a plain user setting (DataStore `HISTORY_PERSIST_ENABLED`, default false).
 * Cap: 2000 rows LRU via dao.trim().
 */
@Singleton
class HistoryPersister @Inject constructor(
    private val repository: TransactionRepository,
    private val dataStore: AxPreferences   // existing DataStore wrapper in core-data
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun start() {
        scope.launch {
            dataStore.data.map { it[booleanPreferencesKey("HISTORY_PERSIST_ENABLED")] ?: false }
                .collect { _enabled.value = it }
        }
    }

    fun persist(req: NetworkRequest, sessionId: String) {
        if (!_enabled.value) return
        scope.launch {
            runCatching {
                repository.save(NetworkInterceptor.toTransaction(req, sessionId))
                repository.trim(keep = 2000)
            }
        }
    }
}
```

Wire it: in the same place `AxNetBridge` posts a finished request into `NetworkInterceptor`,
also call `historyPersister.persist(req, sessionId)` (inject via Hilt; sessionId = current
tab session id, or `"default"` if tabs don't carry one yet).

**Add a Settings row:** Dev Console section → "Persist proxy history" toggle writing
`HISTORY_PERSIST_ENABLED` to DataStore.

### 0.7 CurlParser

**Create file:** `feature/feature-pentest/src/main/kotlin/com/akay/feature/pentest/repeater/CurlParser.kt`

(This creates the new module — see §MODULE WIRING below for Gradle. If you prefer Phase 0
without the new module, put this in `core/core-data/util/` instead and move it in Phase 3.)

```kotlin
package com.akay.feature.pentest.repeater

import com.akay.core.domain.model.HttpTransaction

/**
 * Parses `curl ...` command strings (the format our get_curl tool and Chrome's
 * "Copy as cURL" produce) into structured transactions.
 * Supports: -X/--request, -H/--header (repeatable), -d/--data/--data-raw/--data-binary,
 * --data-urlencode (marked, not pre-encoded), --compressed, -L, -k, -s/-S (ignored),
 * URL positional, method inferred from data presence (POST).
 */
object CurlParser {

    fun parse(curl: String, sessionId: String = "repeater"): HttpTransaction? {
        val tokens = tokenize(curl.trim().removePrefix("$ "))
        if (tokens.isEmpty()) return null
        if (!tokens[0].equals("curl", ignoreCase = true)) return null

        var method: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        var body: ByteArray? = null
        var url: String? = null
        var i = 1
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-X" || t == "--request" -> { method = tokens.getOrNull(++i); i++ }
                t == "-H" || t == "--header" -> {
                    val raw = tokens.getOrNull(++i) ?: return null
                    val idx = raw.indexOf(':')
                    if (idx > 0) headers += raw.substring(0, idx).trim() to raw.substring(idx + 1).trim()
                    i++
                }
                t == "-d" || t == "--data" || t == "--data-raw" || t == "--data-binary" -> {
                    body = (tokens.getOrNull(++i) ?: "").toByteArray()
                    i++
                }
                t == "--data-urlencode" -> {
                    val raw = tokens.getOrNull(++i) ?: ""
                    body = raw.toByteArray()   // caller knows it needs encoding; keep literal
                    i++
                }
                t.startsWith("-") -> i++       // -L, -k, -s, -S, --compressed, -o, … ignore
                else -> { url = t; i++ }
            }
        }
        url ?: return null

        return HttpTransaction(
            sessionId = sessionId,
            method = (method ?: if (body != null) "POST" else "GET").uppercase(),
            url = url,
            headers = headers,
            body = body,
            source = "manual",
            createdAt = System.currentTimeMillis()
        )
    }

    fun toCurl(t: HttpTransaction): String = buildString {
        append("curl -X ").append(t.method).append(" '").append(t.url).append('\'')
        t.headers.forEach { (k, v) -> append(" -H '").append(k).append(": ").append(v).append('\'') }
        t.body?.let { append(" --data-raw '").append(String(it).replace("'", "'\\''")).append('\'') }
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in s) {
            when {
                escaped -> { cur.append(c); escaped = false }
                c == '\\' && quote == '\'' -> cur.append(c)          // inside single quotes backslash is literal
                c == '\\' -> { escaped = true }
                c == '\'' || c == '"' -> {
                    if (quote == c) quote = null
                    else if (quote == null) quote = c
                    else cur.append(c)
                }
                c.isWhitespace() && quote == null -> {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
```

### 0.8 Migration test

**Create file:** `core/core-testing/src/androidTest/kotlin/com/akay/core/testing/Migration6To7Test.kt`

```kotlin
package com.akay.core.testing

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class Migration6To7Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AxDatabase::class.java     // adjust import to your generated class
    )

    @Test
    fun migrate6To7_createsNewTables() {
        helper.createDatabase(TEST_DB, 6).close()
        helper.runMigrationsAndValidate(TEST_DB, 7, true).close()
    }

    @Test
    fun migrate6To7_preservesExistingRows() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO bookmarks (id, url, title, created_at) " +
                    "VALUES ('t1', 'https://example.com', 'Example', 0)"
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 7, true).use { db ->
            db.query("SELECT COUNT(*) FROM bookmarks WHERE id='t1'").use { c ->
                check(c.moveToFirst() && c.getInt(0) == 1) { "existing row lost in migration" }
            }
            db.query("SELECT COUNT(*) FROM http_transactions").use { c ->
                check(c.moveToFirst() && c.getInt(0) == 0) { "new table should start empty" }
            }
        }
    }

    companion object { private const val TEST_DB = "migration-test-6-7.db" }
}
```

(Adjust the seed table/columns to your actual v6 schema — the pattern is what matters:
validate schema, assert old rows survive.)

---

## PHASE 1 — AGENT: PLAYWRIGHT-MCP-SHAPED TOOLS

### 1.1 PageSnapshot model

**Create file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/agent/PageSnapshot.kt`

```kotlin
package com.akay.feature.browser.agent

data class SnapshotNode(
    val ref: String,        // "s12"
    val role: String,       // button | link | textbox | select | checkbox | heading | ...
    val name: String,       // accessible name
    val value: String?,     // current input value
    val path: String,       // unique CSS path (nth-of-type chain)
    val isPassword: Boolean = false,
    val formAction: String? = null,
    val csrfField: Boolean = false
)

data class PageSnapshot(
    val url: String,
    val title: String,
    val nodes: List<SnapshotNode>,
    val dirty: Boolean      // DOM changed since snapshot was taken
)

/**
 * Bookkeeping for ref → path across evaluateJavascript calls.
 * JS object references die between calls; only the CSS path survives.
 */
class SnapshotRegistry {

    private var map: Map<String, String> = emptyMap()
    private var counter = 0
    private val _dirty = kotlinx.coroutines.flow.MutableStateFlow(false)
    val dirty = _dirty

    /** Called with the JSON array emitted by AgentJs.SNAPSHOT. */
    fun ingest(nodes: List<SnapshotNode>, url: String, title: String): PageSnapshot {
        map = nodes.associate { it.ref to it.path }
        counter = nodes.size
        _dirty.value = false
        return PageSnapshot(url, title, nodes, dirty = false)
    }

    fun pathFor(ref: String): String? = map[ref]
    fun nextRef(): String = "s${++counter}"
    fun invalidate() { map = emptyMap(); _dirty.value = true }
    fun markDirty() { _dirty.value = true }
    fun isDirty(): Boolean = _dirty.value
}
```

### 1.2 Snapshot JS

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/agent/AgentJs.kt`

Add constant:

```kotlin
val SNAPSHOT = """
(function() {
    const INTERACTIVE = 'a[href], button, input, select, textarea, [role], [onclick], summary';
    const nodes = [];
    let counter = 0;

    function cssPath(el) {
        const parts = [];
        let node = el;
        while (node && node.nodeType === 1 && parts.length < 25) {
            let sel = node.tagName.toLowerCase();
            if (node.id) { parts.unshift(sel + '#' + CSS.escape(node.id)); break; }
            const parent = node.parentElement;
            if (parent) {
                const same = Array.from(parent.children).filter(c => c.tagName === node.tagName);
                if (same.length > 1) sel += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
            }
            parts.unshift(sel);
            node = node.parentElement;
        }
        return parts.join(' > ');
    }

    function accName(el) {
        return (el.getAttribute('aria-label') ||
                el.getAttribute('alt') ||
                el.getAttribute('placeholder') ||
                (el.labels && el.labels[0] && el.labels[0].innerText) ||
                el.innerText || el.value || '').trim().slice(0, 120);
    }

    document.querySelectorAll(INTERACTIVE).forEach(el => {
        if (el.offsetParent === null && el.tagName !== 'BODY') return;  // skip hidden
        const tag = el.tagName.toLowerCase();
        const type = (el.getAttribute('type') || '').toLowerCase();
        let role = tag;
        if (tag === 'input') {
            role = ({text:'textbox', search:'textbox', email:'textbox', tel:'textbox',
                     password:'textbox', checkbox:'checkbox', radio:'radio',
                     file:'file', submit:'button', button:'button'})[type] || 'textbox';
        }
        if (el.getAttribute('role')) role = el.getAttribute('role');
        if (el.closest('[aria-hidden="true"]')) return;

        const form = el.closest('form');
        const n = {
            ref: 's' + (++counter),
            role: role,
            name: accName(el),
            value: (tag === 'input' || tag === 'textarea') ? String(el.value).slice(0, 100) : undefined,
            path: cssPath(el),
            isPassword: type === 'password',
            formAction: form ? form.getAttribute('action') : undefined,
            csrfField: /^(csrf|_csrf|authenticity_token|xsrf|_token)$/i.test(el.name || '')
        };
        Object.keys(n).forEach(k => n[k] === undefined && delete n[k]);
        nodes.push(n);
    });

    return JSON.stringify({
        url: location.href,
        title: document.title,
        nodes: nodes.slice(0, 300)
    });
})();
""".trimIndent()
```

**Dirty flag injection** — extend the script injected on every `onPageStarted` (where you
already install `net_capture.js` hooks) with:

```javascript
(function() {
    if (window.__axDirtyInstalled) return;
    window.__axDirtyInstalled = true;
    let t = null;
    new MutationObserver(() => {
        clearTimeout(t);
        t = setTimeout(() => window.AxNative && window.AxNative.onDomDirty(), 300);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
```

And in `AxNetBridge` (or the JS interface object), add `@JavascriptInterface fun onDomDirty()`
that calls `snapshotRegistry.markDirty()`.

### 1.3 Ref resolution + new executor methods

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/agent/AgentToolExecutor.kt`

Add a `SnapshotRegistry` (constructor-injected or a field owned by BrowserScreen closures)
and these tool implementations:

```kotlin
// ── browser_snapshot ─────────────────────────────────────────────────────────
suspend fun browserSnapshot(): String {
    val raw = withContext(Dispatchers.Main) { webView.evaluateJavascript(AgentJs.SNAPSHOT, null) }
    // evaluateJavascript returns the JSON string quoted; strip and parse
    val json = JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\"))
    val nodes = json.getJSONArray("nodes")
    val parsed = (0 until nodes.length()).map { i ->
        val n = nodes.getJSONObject(i)
        SnapshotNode(
            ref = n.getString("ref"),
            role = n.getString("role"),
            name = n.optString("name", ""),
            value = n.optString("value", null),
            path = n.getString("path"),
            isPassword = n.optBoolean("isPassword", false),
            formAction = n.optString("formAction", null),
            csrfField = n.optBoolean("csrfField", false)
        )
    }
    val snap = registry.ingest(parsed, json.optString("url"), json.optString("title"))
    // Compact, token-friendly output for the model
    return buildString {
        appendLine("[snapshot] ${snap.url} — ${snap.title}")
        appendLine("refs: " + parsed.size + " interactive nodes (use ref for all element tools)")
        parsed.take(150).forEach { n ->
            appendLine("${n.ref} ${n.role} \"${n.name}\"" +
                (n.value?.let { " value=\"$it\"" } ?: "") +
                (if (n.isPassword) " [password]" else "") +
                (if (n.csrfField) " [csrf]" else "") +
                (n.formAction?.let { " form→$it" } ?: ""))
        }
        if (parsed.size > 150) appendLine("… ${parsed.size - 150} more (pass {maxNodes} to see more)")
    }
}

// ── ref → path resolution shared by all element tools ───────────────────────
private fun resolveRef(ref: String?): String =
    ref?.let { registry.pathFor(it) }
        ?: throw IllegalArgumentException("Unknown or stale ref '$ref' — call browser_snapshot again")

// ── browser_click ────────────────────────────────────────────────────────────
suspend fun browserClick(ref: String): String {
    val path = resolveRef(ref)
    val js = """
    (function() {
        const el = document.querySelector(${path.jsString()});
        if (!el) return JSON.stringify({ok:false, reason:'not-found'});
        el.scrollIntoView({block:'center'});
        const r = el.getBoundingClientRect();
        // synthetic touch + click — covers standard controls and canvas-heavy apps
        ['pointerdown','touchstart','pointerup','touchend','click'].forEach(type => {
            el.dispatchEvent(new (type.startsWith('touch') ? TouchEvent : PointerEvent)(type, {
                bubbles: true, cancelable: true, clientX: r.left + r.width/2, clientY: r.top + r.height/2
            }));
        });
        el.click();
        return JSON.stringify({ok:true, url: location.href});
    })();
    """.trimIndent()
    val before = currentUrl()
    val result = evalJs(js)
    val dirtyAfter = withContext(Dispatchers.IO) { kotlinx.coroutines.delay(500); registry.isDirty() }
    val navigated = currentUrl() != before
    registry.invalidate()   // DOM likely changed; next tool call should re-snapshot
    return "clicked $ref → ok=${result.substringAfter("\"ok\":").take(4)} navigated=$navigated domChanged=${dirtyAfter || navigated}"
}

// ── browser_type ─────────────────────────────────────────────────────────────
suspend fun browserType(ref: String, text: String, submit: Boolean = false): String {
    val path = resolveRef(ref)
    val js = """
    (function() {
        const el = document.querySelector(${path.jsString()});
        if (!el) return JSON.stringify({ok:false, reason:'not-found'});
        el.focus();
        el.value = ${text.jsString()};
        el.dispatchEvent(new Event('input',  {bubbles:true}));
        el.dispatchEvent(new Event('change', {bubbles:true}));
        return JSON.stringify({ok:true});
    })();
    """.trimIndent()
    evalJs(js)
    if (submit) return browserPressKey("Enter")
    return "typed into $ref"
}

// ── browser_fill_form ────────────────────────────────────────────────────────
suspend fun browserFillForm(fields: List<Triple<String, String, String>>): String {
    // fields = (ref, value, name-for-report)
    val results = fields.map { (ref, value, _) -> browserType(ref, value) }
    return "filled ${results.size} fields: " + fields.joinToString { it.third }
}

// ── browser_select_option ────────────────────────────────────────────────────
suspend fun browserSelectOption(ref: String, values: List<String>): String {
    val path = resolveRef(ref)
    val vals = values.joinToString(",") { it.jsString() }
    val js = """
    (function() {
        const el = document.querySelector(${path.jsString()});
        if (!el || el.tagName !== 'SELECT') return JSON.stringify({ok:false, reason:'not-select'});
        Array.from(el.options).forEach(o => o.selected = [${vals}].includes(o.value) || [${vals}].includes(o.text));
        el.dispatchEvent(new Event('change', {bubbles:true}));
        return JSON.stringify({ok:true});
    })();
    """.trimIndent()
    evalJs(js)
    return "selected ${values.joinToString()} in $ref"
}

// ── browser_hover / browser_drag ─────────────────────────────────────────────
suspend fun browserHover(ref: String): String {
    val path = resolveRef(ref)
    evalJs("""
    (function() {
        const el = document.querySelector(${path.jsString()});
        if (!el) return;
        ['mouseover','mouseenter','mousemove'].forEach(t =>
            el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true})));
    })();
    """.trimIndent())
    return "hovered $ref"
}

suspend fun browserDrag(startRef: String, endRef: String): String {
    val from = resolveRef(startRef); val to = resolveRef(endRef)
    evalJs("""
    (function() {
        const a = document.querySelector(${from.jsString()});
        const b = document.querySelector(${to.jsString()});
        if (!a || !b) return;
        const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        const ax = ra.left + ra.width/2, ay = ra.top + ra.height/2;
        const bx = rb.left + rb.width/2, by = rb.top + rb.height/2;
        a.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, clientX:ax, clientY:ay}));
        for (let i = 1; i <= 5; i++) {
            document.elementFromPoint(ax + (bx-ax)*i/5, ay + (by-ay)*i/5)
                ?.dispatchEvent(new PointerEvent('pointermove', {bubbles:true,
                    clientX: ax + (bx-ax)*i/5, clientY: ay + (by-ay)*i/5}));
        }
        b.dispatchEvent(new PointerEvent('pointerup', {bubbles:true, clientX:bx, clientY:by}));
    })();
    """.trimIndent())
    return "dragged $startRef → $endRef"
}

// ── browser_press_key ────────────────────────────────────────────────────────
suspend fun browserPressKey(key: String): String {
    val js = """
    (function() {
        const el = document.activeElement || document.body;
        const k = ${key.jsString()};
        const init = {bubbles:true, cancelable:true, key:k, code:k.length===1 ? 'Key'+k.toUpperCase() : k};
        el.dispatchEvent(new KeyboardEvent('keydown', init));
        if (k.length === 1) el.dispatchEvent(new KeyboardEvent('keypress', init));
        el.dispatchEvent(new KeyboardEvent('keyup', init));
        if (k === 'Enter' && el.form) { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); }
        return JSON.stringify({ok:true});
    })();
    """.trimIndent()
    evalJs(js)
    return "pressed $key"
}

// ── browser_wait_for ─────────────────────────────────────────────────────────
suspend fun browserWaitFor(text: String? = null, textGone: String? = null, timeMs: Long? = null): String {
    val deadline = System.currentTimeMillis() + (timeMs ?: if (text != null || textGone != null) 10_000 else 0)
    while (System.currentTimeMillis() < deadline) {
        if (text != null && evalJs("document.body.innerText.includes(${text.jsString()})").contains("true"))
            return "text appeared: $text"
        if (textGone != null && !evalJs("document.body.innerText.includes(${textGone.jsString()})").contains("true"))
            return "text gone: $textGone"
        kotlinx.coroutines.delay(250)
    }
    return if (text != null) "timeout waiting for: $text" else if (textGone != null) "timeout waiting for removal: $textGone" else "waited ${timeMs}ms"
}

// ── browser_console_messages ────────────────────────────────────────────────
// Add to AxWebChromeClient: keep a synchronized ring buffer (capacity 200) of
// onConsoleMessage entries {level, text, sourceLine}; expose `fun drain(): List<String>`.
// Tool returns last N (default 30) newest-first.

// ── browser_handle_dialog ───────────────────────────────────────────────────
// Add to AxWebChromeClient: a pending-dialog queue.
//   onJsAlert/onJsConfirm/onJsPrompt → enqueue {message, type, JsResult} and return true
//   (suspends the dialog) — browser_handle_dialog {accept, promptText} resumes it:
//   accept ? result.confirm()/confirm(promptText) : result.cancel().
// Tool with empty queue returns "no pending dialog".

// ── browser_tabs ────────────────────────────────────────────────────────────
// Thin extension of existing switch_tab/list_tabs:
//   {action:"list"} → indexed list; {action:"new", url?}; {action:"close", index};
//   {action:"select", index}

// ── browser_file_upload ─────────────────────────────────────────────────────
// Hook onShowFileChooser in AxWebChromeClient: store the ValueCallback<Uri[]> and
// pending file paths; browser_file_upload {paths} resolves the callback with the URIs,
// or cancels it when paths is empty. Must run on main thread.

// ── browser_evaluate ────────────────────────────────────────────────────────
// = existing run_js. Keep name browser_evaluate in the prompt; keep run_js as alias.
```

Helper used above:

```kotlin
private fun String.jsString(): String = "\"" + this
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
```

And the JSON import: `import org.json.JSONObject` (Android built-in — fine).

### 1.4 Dialog + file-chooser + console wiring

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/webview/AxWebChromeClient.kt`

1. **Delete** the current `onJsAlert`/`onJsConfirm` overrides that silently `result?.cancel()`
   (planv03 FIX 10 said this too — verify it was done; if those overrides still exist, remove them).
2. Add a pending-dialog queue:

```kotlin
data class PendingDialog(val type: String, val message: String, val result: JsResult)

private val dialogQueue = ArrayDeque<PendingDialog>()
private val dialogMutex = Mutex()

fun pendingDialogs(): List<PendingDialog> = dialogQueue.toList()
fun currentDialog(): PendingDialog? = dialogQueue.firstOrNull()

suspend fun takeDialog(): PendingDialog? = dialogMutex.withLock { dialogQueue.removeFirstOrNull() }

fun offerDialog(type: String, message: String, result: JsResult) {
    dialogQueue.addLast(PendingDialog(type, message, result))
}

fun resolveDialog(accept: Boolean, promptText: String?): Boolean {
    val d = dialogQueue.firstOrNull() ?: return false
    dialogQueue.removeFirst()
    when {
        !accept -> d.result.cancel()
        d.type == "prompt" && promptText != null -> d.result.confirm(promptText)
        else -> d.result.confirm()
    }
    return true
}
```

Then:
```kotlin
override fun onJsAlert(v: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
    result ?: return true
    offerDialog("alert", message ?: "", result)
    return true   // dialog held until browser_handle_dialog resolves it
}
override fun onJsConfirm(v: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
    result ?: return true
    offerDialog("confirm", message ?: "", result)
    return true
}
override fun onJsPrompt(v: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
    result ?: return true
    offerDialog("prompt", message ?: "", result)
    return true
}
```

> NOTE: holding dialogs means a page's `alert()` blocks until the agent answers —
> add a 30 s auto-cancel in `takeDialog`/`resolveDialog` callers if the agent is idle,
> so pages never hang forever. This is a UX safeguard, not a permission gate.

3. **Console ring buffer** (browser_console_messages source):

```kotlin
private val consoleBuffer = ArrayDeque<String>()
fun onConsole(msg: ConsoleMessage) {
    synchronized(consoleBuffer) {
        if (consoleBuffer.size >= 200) consoleBuffer.removeFirst()
        consoleBuffer.addLast("[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
    }
}
fun drainConsole(limit: Int = 30): List<String> =
    synchronized(consoleBuffer) { consoleBuffer.takeLast(limit) }
```
(call it from the existing `onConsoleMessage` override.)

4. **File chooser hook**:

```kotlin
private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
override fun onShowFileChooser(
    webView: WebView?, callback: ValueCallback<Array<Uri>>,
    params: FileChooserParams?
): Boolean {
    fileChooserCallback?.onReceiveValue(null)   // cancel stale
    fileChooserCallback = callback
    // Existing UI (if any) may present a picker; the agent can call browser_file_upload
    return true
}
fun resolveFileChooser(uris: Array<Uri>?) {
    fileChooserCallback?.onReceiveValue(uris)
    fileChooserCallback = null
}
```

### 1.5 Dialog tool implementation (executor side)

```kotlin
suspend fun browserHandleDialog(accept: Boolean, promptText: String? = null): String {
    val ok = chromeClient.resolveDialog(accept, promptText)
    return if (ok) {
        val remaining = chromeClient.pendingDialogs().size
        "dialog ${if (accept) "accepted" else "dismissed"}${if (remaining > 0) " ($remaining pending)" else ""}"
    } else "no pending dialog"
}
```

### 1.6 New pentest tool wrappers (always available — no precondition)

```kotlin
// http_send — engine arrives in Phase 3; for Phase 1, implement with OkHttp directly:
suspend fun httpSend(method: String, url: String, headers: List<Pair<String,String>>, body: ByteArray?): String {
    val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val rb = Request.Builder().url(url).method(method, body?.toRequestBody())
    headers.forEach { (k, v) -> rb.header(k, v) }
    client.newCall(rb.build()).execute().use { resp ->
        val text = resp.body?.string().orEmpty().take(4000)
        return "HTTP ${resp.code} ${resp.message}\n" +
            resp.headers.joinToString("\n") { (k, v) -> "$k: $v" } +
            "\n\n" + text
    }
}

suspend fun jwtDecode(token: String): String {
    val parts = token.trim().split('.')
    if (parts.size !in 2..3) return "not a JWT (expected 2-3 dot-separated parts)"
    fun b64(s: String) = String(Base64.getUrlDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '=')))
    val header = b64(parts[0]); val payload = b64(parts[1])
    val notes = mutableListOf<String>()
    if (header.contains("\"alg\":\"none\"", ignoreCase = true)) notes += "⚠ alg:none — unsigned token"
    val json = JSONObject(payload)
    json.optLong("exp", 0).takeIf { it > 0 }?.let { exp ->
        if (exp < System.currentTimeMillis() / 1000) notes += "⚠ expired at $exp"
    }
    if (header.contains("\"alg\":\"HS256\"")) notes += "HS256 — symmetric, check for weak secret"
    return "HEADER: $header\nPAYLOAD: $payload" + (if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "")
}

suspend fun dumpStorage(): String {
    val js = """
    (function() {
        const out = {cookies: document.cookie, localStorage: {}, sessionStorage: {}};
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            out.localStorage[k] = String(localStorage.getItem(k)).slice(0, 300);
        }
        for (let i = 0; i < sessionStorage.length; i++) {
            const k = sessionStorage.key(i);
            out.sessionStorage[k] = String(sessionStorage.getItem(k)).slice(0, 300);
        }
        return JSON.stringify(out);
    })();
    """.trimIndent()
    return evalJs(js)
}
```

`audit_security_headers` and `extract_js_endpoints` arrive with the Audit tab in Phase 4 —
for Phase 1, register them in the tool list but implement as:

```kotlin
suspend fun auditSecurityHeaders(): String {
    val resp = /* fetch current page's response headers from NetworkInterceptor's last
                 main-frame-ish capture for current URL, else do a HEAD via httpSend internals */
    // Scorecard: CSP, HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy,
    // Permissions-Policy — present/missing, plus CORS reflection check.
    ...
}
```
(Full code in Phase 4; keep the tool registered now so the model sees a stable surface.)

### 1.7 AgentEngine prompt update

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/agent/AgentEngine.kt`

1. `maxSteps` default **15 → 25**; expose `maxSteps` as a per-run parameter set from
   AgentSheet (a settings slider — resource knob, not a gate).
2. Replace the tools section of the system prompt with the new action names, one line each:

```
Interaction (use refs from browser_snapshot — NEVER write CSS selectors yourself):
- browser_snapshot {} → compact list of interactive elements with refs + roles + names
- browser_click {ref}
- browser_type {ref, text, submit?}
- browser_fill_form {fields: [{ref, value}]}
- browser_select_option {ref, values}
- browser_hover {ref} / browser_drag {startRef, endRef}
- browser_press_key {key}
- browser_wait_for {text? | textGone? | time?}
- browser_handle_dialog {accept, promptText?}
- browser_tabs {action: list|new|close|select, index?, url?}
- browser_evaluate {function} (alias: run_js)
- browser_console_messages {}
- browser_take_screenshot {filename?}
- browser_file_upload {paths?}
Navigation: browser_navigate {url} (alias: navigate)
Network/analysis tools: unchanged (get_curl, find_api_requests, get_response_body, ...)
New: http_send {method, url, headers, body} | jwt_decode {token} | dump_storage {}
     | set_match_replace {rules} | toggle_intercept {on}
```

3. Add the alias line: "Legacy names (navigate, click_element, type_text, wait_for_element,
   switch_tab, take_screenshot, run_js, get_network_requests) still work and map to the
   browser_* equivalents."
4. **Remove any refusal/consent/scope language.** The agent executes what it is told;
   authorization is the operator's responsibility. If the current prompt contains
   "authorized", "permission", "consent", or "scope" language around tools — delete it.

### 1.8 AgentSheet: maxSteps control

**Modify file:** `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/agent/AgentSheet.kt`

Add a compact stepper next to the model selector: `Steps [−] 25 [+]` (range 5–50), passed
through AgentChatController → AgentEngine.

---

## MODULE WIRING (do once, before Phase 0 files)

1. `settings.gradle.kts`: `include(":feature:feature-pentest")`
2. `feature/feature-pentest/build.gradle.kts`:

```kotlin
plugins {
    id("axbrowser.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.akay.feature.pentest"
}
dependencies {
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
}
```

3. `app/build.gradle.kts`: `implementation(project(":feature:feature-pentest"))`

---

## EXECUTION ORDER

```
 1. Module wiring (settings.gradle.kts, feature-pentest/build.gradle.kts, app deps)
 2. HttpTransaction model (core-domain)
 3. Room entities + DAOs + AxDatabase v7 + MIGRATION_6_7 + DAO providers + Hilt binding
 4. TransactionMapper + TransactionRepository + Impl
 5. NetworkInterceptor.toTransaction + HistoryPersister + Settings toggle
 6. CurlParser (+ unit tests: quoted args, escaped quotes, -H repeats, --data-raw)
 7. Migration6To7Test (androidTest, core-testing)
 8. PageSnapshot.kt + SnapshotRegistry + AgentJs.SNAPSHOT + MutationObserver dirty flag
 9. AxWebChromeClient: dialog queue + console buffer + file-chooser hook
10. AgentToolExecutor: browser_* tools + resolveRef + http_send/jwt_decode/dump_storage
11. AgentEngine prompt + maxSteps=25 + aliases; AgentSheet steps stepper
12. Build: ./gradlew :feature:feature-browser:compileDebugKotlin :feature:feature-pentest:compileDebugKotlin
13. Test:  ./gradlew test
```

## VERIFICATION (on device)

1. Open agent → `browser_snapshot` on any page → refs render (s1, s2, …) with roles/names.
2. `browser_click {ref}` on a link → navigates; `browser_type` into a search box + submit → results.
3. Full flow: snapshot login form → fill_form → click submit → agent reports the POST
   (get_curl) without ever writing a CSS selector.
4. Trigger a JS `confirm()` on a page → agent `browser_handle_dialog {accept:true}` resolves it.
5. `http_send` a GET to any host from chat → status + headers + body returned, no prompts.
6. `jwt_decode` a real JWT → decoded payload + flags.
7. Toggle "Persist proxy history" in Settings → kill app → reopen → Dev Console history intact.
8. `agentest.md` legacy prompts (click_element, type_text, wait_for_element) still work via aliases.
```
