package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import com.akay.core.data.db.entity.WatchEntity

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchEntity)

    @Query("SELECT * FROM watches ORDER BY created_at DESC")
    suspend fun getAll(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE label = :label LIMIT 1")
    suspend fun getByLabel(label: String): WatchEntity?

    @Query("DELETE FROM watches WHERE label = :label")
    suspend fun deleteByLabel(label: String)

    @Query("UPDATE watches SET last_content_hash = :hash, last_checked_at = :checkedAt WHERE label = :label")
    suspend fun updateHash(label: String, hash: String, checkedAt: Long)
}
