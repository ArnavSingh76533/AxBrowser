package com.akay.core.domain.repository

import com.akay.core.domain.model.SavedCredential
import kotlinx.coroutines.flow.Flow

interface PasswordRepository {
    fun observeAll(): Flow<List<SavedCredential>>
    suspend fun getForOrigin(origin: String): List<SavedCredential>
    suspend fun save(origin: String, username: String, password: String)
    suspend fun delete(credential: SavedCredential)
}
