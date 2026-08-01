package com.akay.core.data.repository

import com.akay.core.data.db.dao.ProxyDao
import com.akay.core.data.db.entity.ProxyEntity
import com.akay.core.data.security.CryptoManager
import com.akay.core.domain.model.ProxyServer
import com.akay.core.domain.model.ProxyType
import com.akay.core.domain.repository.ProxyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyRepositoryImpl @Inject constructor(
    private val proxyDao: ProxyDao,
    private val cryptoManager: CryptoManager
) : ProxyRepository {

    override fun observeAll(): Flow<List<ProxyServer>> =
        proxyDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAllOnce(): List<ProxyServer> =
        proxyDao.getAllOnce().map { it.toDomain() }

    override suspend fun save(
        id: String?,
        label: String,
        type: ProxyType,
        host: String,
        port: Int,
        username: String?,
        password: String?,
        enabled: Boolean
    ) {
        val sortOrder = if (id == null) {
            proxyDao.getMaxSortOrder() + 1
        } else {
            proxyDao.getAllOnce().firstOrNull { it.id == id }?.sortOrder ?: (proxyDao.getMaxSortOrder() + 1)
        }
        val entity = ProxyEntity(
            id = id ?: UUID.randomUUID().toString(),
            label = label,
            type = type.name,
            host = host,
            port = port,
            username = username?.takeIf { it.isNotBlank() },
            encryptedPassword = password?.takeIf { it.isNotBlank() }?.let { cryptoManager.encrypt(it) },
            enabled = enabled,
            sortOrder = sortOrder
        )
        proxyDao.upsert(entity)
    }

    override suspend fun delete(id: String) {
        proxyDao.delete(id)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        proxyDao.setEnabled(id, enabled)
    }

    private fun ProxyEntity.toDomain() = ProxyServer(
        id = id,
        label = label,
        type = runCatching { ProxyType.valueOf(type) }.getOrDefault(ProxyType.HTTP),
        host = host,
        port = port,
        username = username,
        password = encryptedPassword?.let { runCatching { cryptoManager.decrypt(it) }.getOrNull() },
        enabled = enabled,
        sortOrder = sortOrder
    )
}
