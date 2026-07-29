package com.akay.core.data.repository

import com.akay.core.data.db.dao.FilterListDao
import com.akay.core.data.db.dao.PermissionDao
import com.akay.core.data.db.entity.BlockedDomainEntity
import com.akay.core.data.db.entity.SitePermissionEntity
import com.akay.core.domain.model.SitePermissionType
import com.akay.core.domain.repository.AdBlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdBlockRepositoryImpl @Inject constructor(
    private val filterListDao: FilterListDao,
    private val permissionDao: PermissionDao,
    private val okHttpClient: OkHttpClient
) : AdBlockRepository {

    override fun observeAllBlockedHosts(): Flow<List<String>> =
        filterListDao.observeAllDomains().map { it.filter { d -> d.isNotBlank() } }

    override fun observeSubscriptionSources(): Flow<List<String>> =
        filterListDao.observeSubscriptionSources().map { it.filter { s -> s != "bundled" } }

    override suspend fun countForSource(source: String): Int = filterListDao.countForSource(source)

    override suspend fun addOrRefreshSubscription(url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string() ?: error("Empty response")
            }
            val hosts = parseFilterList(body)
            filterListDao.deleteBySource(url)
            if (hosts.isEmpty()) {
                // Keep a marker row so the subscription still shows up as "added" with 0 hosts.
                filterListDao.insertAll(listOf(BlockedDomainEntity(domain = "", listSource = url, addedAt = System.currentTimeMillis())))
            } else {
                filterListDao.insertAll(
                    hosts.map { host ->
                        BlockedDomainEntity(domain = host, listSource = url, addedAt = System.currentTimeMillis())
                    }
                )
            }
            hosts.size
        }
    }

    override suspend fun removeSubscription(url: String) {
        withContext(Dispatchers.IO) { filterListDao.deleteBySource(url) }
    }

    override suspend fun refreshAllSubscriptions(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = filterListDao.observeSubscriptionSources()
            // one-shot read is sufficient here; callers refresh on demand / periodic worker
        }
    }

    override fun observeDisabledOrigins(type: SitePermissionType): Flow<List<String>> =
        permissionDao.observeDisabledOrigins(type.name)

    override suspend fun isEnabledForOrigin(origin: String, type: SitePermissionType): Boolean {
        val entity = permissionDao.get(origin, type.name) ?: return true
        return entity.granted
    }

    override suspend fun setEnabledForOrigin(origin: String, type: SitePermissionType, enabled: Boolean) {
        permissionDao.upsert(
            SitePermissionEntity(
                id = UUID.randomUUID().toString(),
                origin = origin,
                permissionType = type.name,
                granted = enabled,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Tolerant parser supporting three common community filter-list formats:
     *  - hosts file: "0.0.0.0 domain.com" / "127.0.0.1 domain.com"
     *  - AdBlock Plus basic domain rules: "||domain.com^"
     *  - plain domain-per-line lists
     * Comments starting with #, !, or [ are ignored.
     */
    private fun parseFilterList(raw: String): Set<String> {
        val result = HashSet<String>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return@forEach
            val domain = when {
                line.startsWith("||") -> line.removePrefix("||").substringBefore("^").substringBefore("/")
                line.startsWith("0.0.0.0 ") -> line.removePrefix("0.0.0.0 ").trim()
                line.startsWith("127.0.0.1 ") -> line.removePrefix("127.0.0.1 ").trim()
                line.contains(" ") -> line.substringAfterLast(" ").trim()
                else -> line
            }.lowercase().trim()
            if (domain.isNotEmpty() && domain.contains(".") && !domain.contains(" ") && domain != "localhost") {
                result += domain
            }
        }
        return result
    }
}
