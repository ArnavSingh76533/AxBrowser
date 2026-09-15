package com.akay.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.akay.core.data.db.dao.BookmarkDao
import com.akay.core.data.db.dao.DownloadDao
import com.akay.core.data.db.dao.FilterListDao
import com.akay.core.data.db.dao.FuzzJobDao
import com.akay.core.data.db.dao.FuzzResultDao
import com.akay.core.data.db.dao.HistoryDao
import com.akay.core.data.db.dao.HttpTransactionDao
import com.akay.core.data.db.dao.PasswordDao
import com.akay.core.data.db.dao.PermissionDao
import com.akay.core.data.db.dao.ProxyDao
import com.akay.core.data.db.dao.SavedRequestDao
import com.akay.core.data.db.dao.SiteNoteDao
import com.akay.core.data.db.dao.TabDao
import com.akay.core.data.db.dao.WatchDao
import com.akay.core.data.db.entity.BlockedDomainEntity
import com.akay.core.data.db.entity.BookmarkEntity
import com.akay.core.data.db.entity.BookmarkFolderEntity
import com.akay.core.data.db.entity.DownloadEntity
import com.akay.core.data.db.entity.FuzzJobEntity
import com.akay.core.data.db.entity.FuzzResultEntity
import com.akay.core.data.db.entity.HistoryEntity
import com.akay.core.data.db.entity.HttpTransactionEntity
import com.akay.core.data.db.entity.PasswordEntity
import com.akay.core.data.db.entity.ProxyEntity
import com.akay.core.data.db.entity.SavedRequestEntity
import com.akay.core.data.db.entity.SitePermissionEntity
import com.akay.core.data.db.entity.SiteNoteEntity
import com.akay.core.data.db.entity.TabEntity
import com.akay.core.data.db.entity.WatchEntity

@Database(
    entities = [
        TabEntity::class,
        BookmarkEntity::class,
        BookmarkFolderEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        BlockedDomainEntity::class,
        SitePermissionEntity::class,
        PasswordEntity::class,
        ProxyEntity::class,
        SavedRequestEntity::class,
        SiteNoteEntity::class,
        WatchEntity::class,
        HttpTransactionEntity::class,
        FuzzJobEntity::class,
        FuzzResultEntity::class
    ],
    version = 7,
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
    abstract fun proxyDao(): ProxyDao
    abstract fun savedRequestDao(): SavedRequestDao
    abstract fun siteNoteDao(): SiteNoteDao
    abstract fun watchDao(): WatchDao
    abstract fun httpTransactionDao(): HttpTransactionDao
    abstract fun fuzzJobDao(): FuzzJobDao
    abstract fun fuzzResultDao(): FuzzResultDao
}
