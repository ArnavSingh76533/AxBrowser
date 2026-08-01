package com.akay.core.data.di

import android.content.Context
import androidx.room.Room
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.data.db.AxDatabase
import com.akay.core.data.db.dao.BookmarkDao
import com.akay.core.data.db.dao.DownloadDao
import com.akay.core.data.db.dao.FilterListDao
import com.akay.core.data.db.dao.HistoryDao
import com.akay.core.data.db.dao.PasswordDao
import com.akay.core.data.db.dao.PermissionDao
import com.akay.core.data.db.dao.ProxyDao
import com.akay.core.data.db.dao.TabDao
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
        ).fallbackToDestructiveMigration().build()
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
