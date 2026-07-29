package com.akay.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.akay.core.data.db.entity.TabEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY last_accessed DESC")
    fun getAllTabs(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE is_active = 1")
    fun getActiveTabs(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun getTab(id: String): TabEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: TabEntity)

    @Update
    suspend fun updateTab(tab: TabEntity)

    @Delete
    suspend fun deleteTab(tab: TabEntity)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun deleteTabById(id: String)

    @Query("UPDATE tabs SET is_active = 0")
    suspend fun deactivateAllTabs()

    @Query("UPDATE tabs SET is_active = 1 WHERE id = :id")
    suspend fun setActiveTab(id: String)

    @Query("SELECT COUNT(*) FROM tabs")
    suspend fun getTabCount(): Int

    @Query("UPDATE tabs SET group_id = :groupId, group_name = :groupName, group_color = :groupColor WHERE id = :tabId")
    suspend fun assignGroup(tabId: String, groupId: String?, groupName: String?, groupColor: Int?)

    @Query("UPDATE tabs SET group_id = NULL, group_name = NULL, group_color = NULL WHERE id = :tabId")
    suspend fun clearGroup(tabId: String)
}
