package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.akay.core.data.db.entity.SiteNoteEntity

@Dao
interface SiteNoteDao {
    @Insert
    suspend fun insert(entity: SiteNoteEntity)

    @Query("SELECT * FROM site_notes WHERE domain = :domain ORDER BY created_at DESC LIMIT 20")
    suspend fun getForDomain(domain: String): List<SiteNoteEntity>

    @Query("DELETE FROM site_notes WHERE domain = :domain")
    suspend fun deleteForDomain(domain: String)
}
