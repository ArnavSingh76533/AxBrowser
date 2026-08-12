package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A curl command the agent (or user) bookmarked with a name, so it can be replayed later
 *  without re-discovering it (find_api_requests -> get_curl -> save_request). */
@Entity(tableName = "saved_requests", indices = [Index(value = ["label"], unique = true)])
data class SavedRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val curl: String,
    val domain: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
