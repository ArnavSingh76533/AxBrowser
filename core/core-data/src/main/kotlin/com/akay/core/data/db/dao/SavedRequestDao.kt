package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.SavedRequestEntity

@Dao
interface SavedRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedRequestEntity)

    @Query("SELECT * FROM saved_requests ORDER BY created_at DESC")
    suspend fun getAll(): List<SavedRequestEntity>

    @Query("SELECT * FROM saved_requests WHERE label = :label LIMIT 1")
    suspend fun getByLabel(label: String): SavedRequestEntity?

    @Query("DELETE FROM saved_requests WHERE label = :label")
    suspend fun deleteByLabel(label: String)
}
