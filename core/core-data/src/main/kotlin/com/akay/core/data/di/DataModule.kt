package com.akay.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.data.db.AxDatabase
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AxDatabase {
        return Room.databaseBuilder(
            context,
            AxDatabase::class.java,
            "axbrowser_database"
        )
            .addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    /** v7: pentest tables (proxy history, intruder jobs/results). Fresh tables, no data movement. */
    private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `http_transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` TEXT NOT NULL, `method` TEXT NOT NULL, `url` TEXT NOT NULL, `http_version` TEXT NOT NULL, `headers_json` TEXT NOT NULL, `body_text` TEXT NOT NULL, `response_status` INTEGER, `response_headers_json` TEXT NOT NULL, `response_body_text` TEXT NOT NULL, `response_time_ms` INTEGER NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_transactions_session_id` ON `http_transactions` (`session_id`)" )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_http_transactions_created_at` ON `http_transactions` (`created_at`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `fuzz_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `engine` TEXT NOT NULL, `request_template` TEXT NOT NULL, `payload_sets_json` TEXT NOT NULL, `threads` INTEGER NOT NULL, `request_cap` INTEGER NOT NULL, `delay_ms` INTEGER NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `fuzz_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `job_id` INTEGER NOT NULL, `payload` TEXT NOT NULL, `status` INTEGER NOT NULL, `length` INTEGER NOT NULL, `time_ms` INTEGER NOT NULL, `hit` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuzz_results_job_id` ON `fuzz_results` (`job_id`)")
        }
    }

    @Provides
    fun provideTabDao(database: AxDatabase): TabDao = database.tabDao()

    @Provides
    fun provideBookmarkDao(database: AxDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideHistoryDao(database: AxDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideDownloadDao(database: AxDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideFilterListDao(database: AxDatabase): FilterListDao = database.filterListDao()

    @Provides
    fun providePermissionDao(database: AxDatabase): PermissionDao = database.permissionDao()

    @Provides
    fun providePasswordDao(database: AxDatabase): PasswordDao = database.passwordDao()

    @Provides
    fun provideProxyDao(database: AxDatabase): ProxyDao = database.proxyDao()

    @Provides
    fun provideSavedRequestDao(database: AxDatabase): SavedRequestDao = database.savedRequestDao()

    @Provides
    fun provideSiteNoteDao(database: AxDatabase): SiteNoteDao = database.siteNoteDao()

    @Provides
    fun provideWatchDao(database: AxDatabase): WatchDao = database.watchDao()

    @Provides
    fun provideHttpTransactionDao(database: AxDatabase): HttpTransactionDao = database.httpTransactionDao()

    @Provides
    fun provideFuzzJobDao(database: AxDatabase): FuzzJobDao = database.fuzzJobDao()

    @Provides
    fun provideFuzzResultDao(database: AxDatabase): FuzzResultDao = database.fuzzResultDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
