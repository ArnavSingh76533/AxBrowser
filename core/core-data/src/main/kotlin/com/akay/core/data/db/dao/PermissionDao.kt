package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.SitePermissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionDao {

    @Query("SELECT * FROM site_permissions")
    fun observeAll(): Flow<List<SitePermissionEntity>>

    @Query("SELECT * FROM site_permissions WHERE origin = :origin AND permission_type = :type LIMIT 1")
    suspend fun get(origin: String, type: String): SitePermissionEntity?

    @Query("SELECT origin FROM site_permissions WHERE permission_type = :type AND granted = 0")
    fun observeDisabledOrigins(type: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SitePermissionEntity)

    @Query("DELETE FROM site_permissions WHERE origin = :origin AND permission_type = :type")
    suspend fun delete(origin: String, type: String)
}
