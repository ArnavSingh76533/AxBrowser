package com.akay.core.data.repository

import com.akay.core.data.db.dao.PasswordDao
import com.akay.core.data.db.entity.PasswordEntity
import com.akay.core.data.security.CryptoManager
import com.akay.core.domain.model.SavedCredential
import com.akay.core.domain.repository.PasswordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordRepositoryImpl @Inject constructor(
    private val passwordDao: PasswordDao,
    private val cryptoManager: CryptoManager
) : PasswordRepository {

    override fun observeAll(): Flow<List<SavedCredential>> =
        passwordDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getForOrigin(origin: String): List<SavedCredential> =
        passwordDao.getForOrigin(origin).map { it.toDomain() }

    override suspend fun save(origin: String, username: String, password: String) {
        val existing = passwordDao.getForOrigin(origin).firstOrNull { it.username == username }
        val now = System.currentTimeMillis()
        val entity = PasswordEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            origin = origin,
            username = username,
            encryptedPassword = cryptoManager.encrypt(password),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        passwordDao.upsert(entity)
    }

    override suspend fun delete(credential: SavedCredential) {
        passwordDao.deleteById(credential.id)
    }

    private fun PasswordEntity.toDomain() = SavedCredential(
        id = id,
        origin = origin,
        username = username,
        password = runCatching { cryptoManager.decrypt(encryptedPassword) }.getOrDefault(""),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
