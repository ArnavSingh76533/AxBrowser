package com.akay.core.domain.model

data class SavedCredential(
    val id: String,
    val origin: String,
    val username: String,
    val password: String,
    val createdAt: Long,
    val updatedAt: Long
)
