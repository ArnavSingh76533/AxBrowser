package com.akay.core.domain.repository

import com.akay.core.domain.model.SitePermissionType
import kotlinx.coroutines.flow.Flow

interface AdBlockRepository {
    /** All currently blocked hostnames, bundled + subscriptions combined. */
    fun observeAllBlockedHosts(): Flow<List<String>>

    fun observeSubscriptionSources(): Flow<List<String>>

    suspend fun countForSource(source: String): Int

    /** Downloads and parses a hosts-file / EasyList-lite subscription, replacing existing entries for that source. */
    suspend fun addOrRefreshSubscription(url: String): Result<Int>

    suspend fun removeSubscription(url: String)

    suspend fun refreshAllSubscriptions(): Result<Unit>

    fun observeDisabledOrigins(type: SitePermissionType): Flow<List<String>>

    suspend fun isEnabledForOrigin(origin: String, type: SitePermissionType): Boolean

    suspend fun setEnabledForOrigin(origin: String, type: SitePermissionType, enabled: Boolean)
}
