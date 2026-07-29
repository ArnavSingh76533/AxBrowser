package com.akay.core.domain.repository

import com.akay.core.domain.model.Tab
import kotlinx.coroutines.flow.Flow

interface TabRepository {
    fun getAllTabs(): Flow<List<Tab>>
    fun getActiveTabs(): Flow<List<Tab>>
    suspend fun getTab(id: String): Tab?
    suspend fun createTab(tab: Tab): String
    suspend fun updateTab(tab: Tab)
    suspend fun deleteTab(id: String)
    suspend fun setActiveTab(id: String)
    suspend fun getTabCount(): Int
    suspend fun assignTabToGroup(tabId: String, groupId: String?, groupName: String?, groupColor: Int?)
    suspend fun clearTabGroup(tabId: String)
}
