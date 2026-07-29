package com.akay.core.domain.model

data class FilterListSubscription(
    val url: String,
    val name: String,
    val hostCount: Int = 0
)

enum class SitePermissionType { AD_BLOCK, JAVASCRIPT }

data class SitePermission(
    val origin: String,
    val type: SitePermissionType,
    val granted: Boolean
)
