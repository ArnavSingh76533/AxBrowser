package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxies")
data class ProxyEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: String, // HTTP, HTTPS, SOCKS4, SOCKS5
    val host: String,
    val port: Int,
    val username: String?,
    @ColumnInfo(name = "encrypted_password") val encryptedPassword: String?,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)
