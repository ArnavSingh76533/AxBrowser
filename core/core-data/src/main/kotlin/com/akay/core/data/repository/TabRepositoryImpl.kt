package com.akay.core.data.repository

import com.akay.core.data.db.dao.TabDao
import com.akay.core.data.mapper.toDomain
import com.akay.core.data.mapper.toEntity
import com.akay.core.domain.model.Tab
import com.akay.core.domain.repository.TabRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TabRepositoryImpl @Inject constructor(
    private val tabDao: TabDao
) : TabRepository {

    override fun getAllTabs(): Flow<List<Tab>> {
        return tabDao.getAllTabs().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getActiveTabs(): Flow<List<Tab>> {
        return tabDao.getActiveTabs().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getTab(id: String): Tab? {
        return tabDao.getTab(id)?.toDomain()
    }

    override suspend fun createTab(tab: Tab): String {
        tabDao.insertTab(tab.toEntity())
        return tab.id
    }

    override suspend fun updateTab(tab: Tab) {
        tabDao.updateTab(tab.toEntity())
    }

    override suspend fun deleteTab(id: String) {
        tabDao.deleteTabById(id)
    }

    override suspend fun setActiveTab(id: String) {
        tabDao.deactivateAllTabs()
        tabDao.setActiveTab(id)
    }

    override suspend fun getTabCount(): Int {
        return tabDao.getTabCount()
    }

    override suspend fun assignTabToGroup(tabId: String, groupId: String?, groupName: String?, groupColor: Int?) {
        tabDao.assignGroup(tabId, groupId, groupName, groupColor)
    }

    override suspend fun clearTabGroup(tabId: String) {
        tabDao.clearGroup(tabId)
    }
}
