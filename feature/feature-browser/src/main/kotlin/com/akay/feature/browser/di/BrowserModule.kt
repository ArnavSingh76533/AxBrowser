package com.akay.feature.browser.di

import com.akay.core.data.repository.AdBlockRepositoryImpl
import com.akay.core.data.repository.PasswordRepositoryImpl
import com.akay.core.data.repository.ProxyRepositoryImpl
import com.akay.core.data.repository.TabRepositoryImpl
import com.akay.core.domain.repository.AdBlockRepository
import com.akay.core.domain.repository.PasswordRepository
import com.akay.core.domain.repository.ProxyRepository
import com.akay.core.domain.repository.TabRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BrowserModule {
    @Binds
    @Singleton
    abstract fun bindTabRepository(impl: TabRepositoryImpl): TabRepository

    @Binds
    @Singleton
    abstract fun bindAdBlockRepository(impl: AdBlockRepositoryImpl): AdBlockRepository

    @Binds
    @Singleton
    abstract fun bindPasswordRepository(impl: PasswordRepositoryImpl): PasswordRepository

    @Binds
    @Singleton
    abstract fun bindProxyRepository(impl: ProxyRepositoryImpl): ProxyRepository
}
