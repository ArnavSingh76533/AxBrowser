package com.akay.core.domain.repository

import com.akay.core.domain.model.ProxyServer
import com.akay.core.domain.model.ProxyType
import kotlinx.coroutines.flow.Flow

interface ProxyRepository {
    fun observeAll(): Flow<List<ProxyServer>>
    suspend fun getAllOnce(): List<ProxyServer>
    suspend fun save(
        id: String?,
        label: String,
        type: ProxyType,
        host: String,
        port: Int,
        username: String?,
        password: String?,
        enabled: Boolean
    )
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}
