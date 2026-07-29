package com.akay.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "axbrowser_preferences")

@Singleton
class AxPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val IS_AD_BLOCKER_ENABLED = booleanPreferencesKey("is_ad_blocker_enabled")
        val IS_TRACKER_BLOCKER_ENABLED = booleanPreferencesKey("is_tracker_blocker_enabled")
        val IS_HTTPS_UPGRADE = booleanPreferencesKey("is_https_upgrade")
        val IS_JAVASCRIPT_ENABLED = booleanPreferencesKey("is_javascript_enabled")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        val IS_DESKTOP_MODE = booleanPreferencesKey("is_desktop_mode")
        val FONT_SIZE = intPreferencesKey("font_size")
        val CLEAR_CACHE_ON_EXIT = booleanPreferencesKey("clear_cache_on_exit")
        val ERUDA_ENABLED = booleanPreferencesKey("eruda_enabled")
        val CUSTOM_HEADERS = stringPreferencesKey("custom_headers")
        val USER_SCRIPTS = stringPreferencesKey("user_scripts")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val AMOLED_THEME = booleanPreferencesKey("amoled_theme")
        val GALAXY_ENABLED = booleanPreferencesKey("galaxy_enabled")
        val ANIMATION_INTENSITY = intPreferencesKey("animation_intensity")
        val DARK_MODE_WEBSITES = booleanPreferencesKey("dark_mode_websites")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_USE_BIOMETRIC = booleanPreferencesKey("app_lock_use_biometric")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val BATTERY_SAVER_ENABLED = booleanPreferencesKey("battery_saver_enabled")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
    }

    val searchEngine: Flow<String> = context.dataStore.data.map { it[Keys.SEARCH_ENGINE] ?: "https://www.google.com/search?q=" }
    val homepage: Flow<String> = context.dataStore.data.map { it[Keys.HOMEPAGE] ?: "about:blank" }
    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_DARK_MODE] ?: true }
    val isAdBlockerEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_AD_BLOCKER_ENABLED] ?: true }
    val isTrackerBlockerEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_TRACKER_BLOCKER_ENABLED] ?: true }
    val isHttpsUpgrade: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_HTTPS_UPGRADE] ?: true }
    val isJavascriptEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_JAVASCRIPT_ENABLED] ?: true }
    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map { it[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 3 }
    val downloadFolderUri: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_FOLDER_URI] ?: "" }
    val isDesktopMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_DESKTOP_MODE] ?: false }
    val fontSize: Flow<Int> = context.dataStore.data.map { it[Keys.FONT_SIZE] ?: 100 }
    val clearCacheOnExit: Flow<Boolean> = context.dataStore.data.map { it[Keys.CLEAR_CACHE_ON_EXIT] ?: false }
    val erudaEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.ERUDA_ENABLED] ?: false }
    val customHeaders: Flow<String> = context.dataStore.data.map { it[Keys.CUSTOM_HEADERS] ?: "" }
    val userScripts: Flow<String> = context.dataStore.data.map { it[Keys.USER_SCRIPTS] ?: "" }
    val accentColor: Flow<String> = context.dataStore.data.map { it[Keys.ACCENT_COLOR] ?: "Nebula Violet" }
    val amoledTheme: Flow<Boolean> = context.dataStore.data.map { it[Keys.AMOLED_THEME] ?: true }
    val galaxyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.GALAXY_ENABLED] ?: true }
    val animationIntensity: Flow<Int> = context.dataStore.data.map { it[Keys.ANIMATION_INTENSITY] ?: 100 }
    val darkModeForWebsites: Flow<Boolean> = context.dataStore.data.map { it[Keys.DARK_MODE_WEBSITES] ?: false }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }
    val appLockPinHash: Flow<String?> = context.dataStore.data.map { it[Keys.APP_LOCK_PIN_HASH] }
    val appLockUseBiometric: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK_USE_BIOMETRIC] ?: true }
    val wifiOnlyDownloads: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY_DOWNLOADS] ?: false }
    val batterySaverEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.BATTERY_SAVER_ENABLED] ?: false }
    val themePreset: Flow<String> = context.dataStore.data.map { it[Keys.THEME_PRESET] ?: "Nebula" }

    suspend fun setSearchEngine(url: String) { context.dataStore.edit { it[Keys.SEARCH_ENGINE] = url } }
    suspend fun setHomepage(url: String) { context.dataStore.edit { it[Keys.HOMEPAGE] = url } }
    suspend fun setDarkMode(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_DARK_MODE] = enabled } }
    suspend fun setAdBlockerEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_AD_BLOCKER_ENABLED] = enabled } }
    suspend fun setTrackerBlockerEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_TRACKER_BLOCKER_ENABLED] = enabled } }
    suspend fun setHttpsUpgrade(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_HTTPS_UPGRADE] = enabled } }
    suspend fun setJavascriptEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_JAVASCRIPT_ENABLED] = enabled } }
    suspend fun setMaxConcurrentDownloads(count: Int) { context.dataStore.edit { it[Keys.MAX_CONCURRENT_DOWNLOADS] = count } }
    suspend fun setDownloadFolderUri(uri: String) { context.dataStore.edit { it[Keys.DOWNLOAD_FOLDER_URI] = uri } }
    suspend fun setDesktopMode(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_DESKTOP_MODE] = enabled } }
    suspend fun setFontSize(size: Int) { context.dataStore.edit { it[Keys.FONT_SIZE] = size } }
    suspend fun setClearCacheOnExit(enabled: Boolean) { context.dataStore.edit { it[Keys.CLEAR_CACHE_ON_EXIT] = enabled } }
    suspend fun setErudaEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.ERUDA_ENABLED] = enabled } }
    suspend fun setCustomHeaders(headers: String) { context.dataStore.edit { it[Keys.CUSTOM_HEADERS] = headers } }
    suspend fun setUserScripts(json: String) { context.dataStore.edit { it[Keys.USER_SCRIPTS] = json } }
    suspend fun setAccentColor(name: String) { context.dataStore.edit { it[Keys.ACCENT_COLOR] = name } }
    suspend fun setAmoledTheme(enabled: Boolean) { context.dataStore.edit { it[Keys.AMOLED_THEME] = enabled } }
    suspend fun setGalaxyEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.GALAXY_ENABLED] = enabled } }
    suspend fun setAnimationIntensity(value: Int) { context.dataStore.edit { it[Keys.ANIMATION_INTENSITY] = value } }
    suspend fun setDarkModeForWebsites(enabled: Boolean) { context.dataStore.edit { it[Keys.DARK_MODE_WEBSITES] = enabled } }
    suspend fun setAppLockEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled } }
    suspend fun setAppLockPinHash(hash: String?) {
        context.dataStore.edit {
            if (hash == null) it.remove(Keys.APP_LOCK_PIN_HASH) else it[Keys.APP_LOCK_PIN_HASH] = hash
        }
    }
    suspend fun setAppLockUseBiometric(enabled: Boolean) { context.dataStore.edit { it[Keys.APP_LOCK_USE_BIOMETRIC] = enabled } }
    suspend fun setWifiOnlyDownloads(enabled: Boolean) { context.dataStore.edit { it[Keys.WIFI_ONLY_DOWNLOADS] = enabled } }
    suspend fun setBatterySaverEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.BATTERY_SAVER_ENABLED] = enabled } }
    suspend fun setThemePreset(name: String) { context.dataStore.edit { it[Keys.THEME_PRESET] = name } }
}
