package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_domains", primaryKeys = ["domain", "list_source"])
data class BlockedDomainEntity(
    val domain: String,
    @ColumnInfo(name = "list_source") val listSource: String,
    @ColumnInfo(name = "added_at") val addedAt: Long
)

@Entity(tableName = "site_permissions")
data class SitePermissionEntity(
    @PrimaryKey val id: String,
    val origin: String,
    @ColumnInfo(name = "permission_type") val permissionType: String,
    val granted: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
