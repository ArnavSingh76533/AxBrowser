package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A short fact the agent learned about a specific site's domain, persisted so a later chat
 *  session (not just a later turn in the same run) can be primed with it instead of starting
 *  from zero each visit - e.g. "this site's API needs an X-Client-Version header or it 403s". */
@Entity(tableName = "site_notes")
data class SiteNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
