package com.akay.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.akay.core.data.db.dao.BookmarkDao
import com.akay.core.data.db.dao.DownloadDao
import com.akay.core.data.db.dao.FilterListDao
import com.akay.core.data.db.dao.HistoryDao
import com.akay.core.data.db.dao.PasswordDao
import com.akay.core.data.db.dao.PermissionDao
import com.akay.core.data.db.dao.TabDao
import com.akay.core.data.db.entity.BlockedDomainEntity
import com.akay.core.data.db.entity.BookmarkEntity
import com.akay.core.data.db.entity.BookmarkFolderEntity
import com.akay.core.data.db.entity.DownloadEntity
import com.akay.core.data.db.entity.HistoryEntity
import com.akay.core.data.db.entity.PasswordEntity
import com.akay.core.data.db.entity.SitePermissionEntity
import com.akay.core.data.db.entity.TabEntity

@Database(
    entities = [
        TabEntity::class,
        BookmarkEntity::class,
        BookmarkFolderEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        BlockedDomainEntity::class,
        SitePermissionEntity::class,
        PasswordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AxDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun filterListDao(): FilterListDao
    abstract fun permissionDao(): PermissionDao
    abstract fun passwordDao(): PasswordDao
}
