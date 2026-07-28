package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.BlockedDomainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterListDao {

    @Query("SELECT domain FROM blocked_domains")
    suspend fun getAllDomainsOnce(): List<String>

    @Query("SELECT domain FROM blocked_domains")
    fun observeAllDomains(): Flow<List<String>>

    @Query("SELECT DISTINCT list_source FROM blocked_domains")
    fun observeSubscriptionSources(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE list_source = :source")
    suspend fun countForSource(source: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BlockedDomainEntity>)

    @Query("DELETE FROM blocked_domains WHERE list_source = :source")
    suspend fun deleteBySource(source: String)

    @Query("DELETE FROM blocked_domains")
    suspend fun deleteAll()
}
