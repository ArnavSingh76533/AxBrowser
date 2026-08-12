package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A background "tell me when this page changes" job. Checked periodically by PageWatchWorker,
 *  which fetches [url]'s raw content, hashes it, and notifies when the hash differs from
 *  [lastContentHash]. This is a plain HTTP fetch (via OkHttp), not a rendered WebView load, so it
 *  only sees what's in the initial HTML response - it won't catch changes that only appear after
 *  JS runs client-side. That's a deliberate scope limit: driving a WebView from a background
 *  Worker is a much larger, riskier undertaking (WebView needs the main looper + a real window),
 *  and most "did this page change" checks (price text, a status field, article content) are
 *  visible in the raw HTML anyway. */
@Entity(tableName = "watches", indices = [Index(value = ["label"], unique = true)])
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val url: String,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int,
    @ColumnInfo(name = "last_content_hash") val lastContentHash: String? = null,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
