package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.ProxyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyDao {

    @Query("SELECT * FROM proxies ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<ProxyEntity>>

    @Query("SELECT * FROM proxies ORDER BY sort_order ASC")
    suspend fun getAllOnce(): List<ProxyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProxyEntity)

    @Query("DELETE FROM proxies WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE proxies SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM proxies")
    suspend fun getMaxSortOrder(): Int
}
