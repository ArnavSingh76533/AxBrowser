package com.akay.core.data.mapper

import com.akay.core.data.db.entity.BookmarkEntity
import com.akay.core.data.db.entity.BookmarkFolderEntity
import com.akay.core.data.db.entity.DownloadEntity
import com.akay.core.data.db.entity.HistoryEntity
import com.akay.core.data.db.entity.TabEntity
import com.akay.core.domain.model.Bookmark
import com.akay.core.domain.model.BookmarkFolder
import com.akay.core.domain.model.Download
import com.akay.core.domain.model.DownloadStatus
import com.akay.core.domain.model.HistoryItem
import com.akay.core.domain.model.Tab

fun TabEntity.toDomain(): Tab = Tab(
    id = id,
    url = url,
    title = title ?: "",
    faviconUrl = faviconUrl,
    scrollPosition = scrollPosition,
    isIncognito = isIncognito,
    createdAt = createdAt,
    lastAccessed = lastAccessed,
    isActive = isActive,
    groupId = groupId,
    groupName = groupName,
    groupColor = groupColor
)

fun Tab.toEntity(): TabEntity = TabEntity(
    id = id,
    url = url,
    title = title,
    faviconUrl = faviconUrl,
    scrollPosition = scrollPosition,
    isIncognito = isIncognito,
    createdAt = createdAt,
    lastAccessed = lastAccessed,
    isActive = isActive,
    groupId = groupId,
    groupName = groupName,
    groupColor = groupColor
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    url = url,
    title = title,
    faviconUrl = faviconUrl,
    folderId = folderId,
    createdAt = createdAt
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    url = url,
    title = title,
    faviconUrl = faviconUrl,
    folderId = folderId,
    createdAt = createdAt
)

fun BookmarkFolderEntity.toDomain(): BookmarkFolder = BookmarkFolder(
    id = id,
    name = name,
    parentId = parentId,
    createdAt = createdAt
)

fun BookmarkFolder.toEntity(): BookmarkFolderEntity = BookmarkFolderEntity(
    id = id,
    name = name,
    parentId = parentId,
    createdAt = createdAt
)

fun HistoryEntity.toDomain(): HistoryItem = HistoryItem(
    id = id,
    url = url,
    title = title,
    visitCount = visitCount,
    lastVisited = lastVisited
)

fun HistoryItem.toEntity(): HistoryEntity = HistoryEntity(
    id = id,
    url = url,
    title = title,
    visitCount = visitCount,
    lastVisited = lastVisited
)

fun DownloadEntity.toDomain(): Download = Download(
    id = id,
    url = url,
    filename = filename,
    destinationPath = destinationPath,
    mimeType = mimeType,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    status = DownloadStatus.valueOf(status),
    errorMessage = errorMessage,
    speedBps = speedBps,
    enqueuedAt = enqueuedAt,
    completedAt = completedAt,
    retryCount = retryCount
)

fun Download.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    url = url,
    filename = filename,
    destinationPath = destinationPath,
    mimeType = mimeType,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    status = status.name,
    errorMessage = errorMessage,
    speedBps = speedBps,
    enqueuedAt = enqueuedAt,
    completedAt = completedAt,
    retryCount = retryCount
)
