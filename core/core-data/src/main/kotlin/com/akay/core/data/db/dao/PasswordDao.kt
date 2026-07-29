package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.akay.core.data.db.entity.PasswordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao {

    @Query("SELECT * FROM saved_passwords ORDER BY origin ASC")
    fun observeAll(): Flow<List<PasswordEntity>>

    @Query("SELECT * FROM saved_passwords WHERE origin = :origin")
    suspend fun getForOrigin(origin: String): List<PasswordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PasswordEntity)

    @Delete
    suspend fun delete(entity: PasswordEntity)

    @Query("DELETE FROM saved_passwords WHERE id = :id")
    suspend fun deleteById(id: String)
}
