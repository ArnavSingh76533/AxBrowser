package com.akay.core.domain.model

import java.util.UUID

data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "about:blank",
    val title: String = "",
    val faviconUrl: String? = null,
    val scrollPosition: Int = 0,
    val isIncognito: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupColor: Int? = null
)
