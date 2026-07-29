package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String?,
    @ColumnInfo(name = "favicon_url") val faviconUrl: String?,
    @ColumnInfo(name = "scroll_position") val scrollPosition: Int = 0,
    @ColumnInfo(name = "is_incognito") val isIncognito: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed") val lastAccessed: Long,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    @ColumnInfo(name = "group_name") val groupName: String? = null,
    @ColumnInfo(name = "group_color") val groupColor: Int? = null
)
