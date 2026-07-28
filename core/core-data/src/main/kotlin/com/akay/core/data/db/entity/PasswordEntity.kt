package com.akay.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_passwords")
data class PasswordEntity(
    @PrimaryKey val id: String,
    val origin: String,
    val username: String,
    @ColumnInfo(name = "encrypted_password") val encryptedPassword: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
