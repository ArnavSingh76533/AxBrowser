package com.akay.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.akay.core.data.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "axbrowser_preferences")

@Singleton
class AxPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager
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
        val OPENROUTER_API_KEY_ENC = stringPreferencesKey("openrouter_api_key_enc")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_ROTATION_MODE = stringPreferencesKey("proxy_rotation_mode")
        val PROXY_ROTATION_INTERVAL_MIN = intPreferencesKey("proxy_rotation_interval_min")
        val PROXY_BYPASS_RULES_JSON = stringPreferencesKey("proxy_bypass_rules_json")
        val PROXY_ACTIVE_INDEX = intPreferencesKey("proxy_active_index")
        val FINGERPRINT_PROTECTION_ENABLED = booleanPreferencesKey("fingerprint_protection_enabled")
        val FINGERPRINT_SPOOF_CANVAS = booleanPreferencesKey("fingerprint_spoof_canvas")
        val FINGERPRINT_SPOOF_WEBGL = booleanPreferencesKey("fingerprint_spoof_webgl")
        val FINGERPRINT_SPOOF_HARDWARE = booleanPreferencesKey("fingerprint_spoof_hardware")
        val FINGERPRINT_DEVICE_SEED = stringPreferencesKey("fingerprint_device_seed")
        val PENDING_UPDATE_APK_PATH = stringPreferencesKey("pending_update_apk_path")
        val PENDING_UPDATE_VERSION = stringPreferencesKey("pending_update_version")
    }

    val searchEngine: Flow<String> = context.dataStore.data.map { it[Keys.SEARCH_ENGINE] ?: "https://www.google.com/search?q=" }
    val homepage: Flow<String> = context.dataStore.data.map { it[Keys.HOMEPAGE] ?: "about:blank" }
    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_DARK_MODE] ?: true }
    val isAdBlockerEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_AD_BLOCKER_ENABLED] ?: true }
    val isTrackerBlockerEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_TRACKER_BLOCKER_ENABLED] ?: true }
    val isHttpsUpgrade: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_HTTPS_UPGRADE] ?: true }
    val isJavascriptEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_JAVASCRIPT_ENABLED] ?: true }
    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map { it[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 3 }
    /** SAF (Storage Access Framework) tree URI for the user-chosen "AxBrowser" folder - opt-in,
     *  set once via a folder picker in Settings. Empty means "not set", in which case everything
     *  falls back to app-private storage (AxStorage.kt handles that fallback). Key name kept as
     *  "download_folder_uri" (an earlier, narrower, never-wired-up concept) since this preference
     *  was never actually used yet - reusing it avoids a migration for zero benefit. */
    val axStorageRootUri: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_FOLDER_URI] ?: "" }
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
    /** The encrypted key stored under this name is reused for whichever provider is currently
     *  selected (OpenRouter, OpenAI, NVIDIA NIM, or a custom OpenAI-compatible endpoint) - one
     *  key slot, since a person only has one active provider configured at a time. */
    val openRouterApiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.OPENROUTER_API_KEY_ENC]?.let { enc -> runCatching { cryptoManager.decrypt(enc) }.getOrNull() }
    }
    val aiModel: Flow<String> = context.dataStore.data.map { it[Keys.AI_MODEL] ?: "meta-llama/llama-3.1-8b-instruct:free" }
    /** Which provider preset is active: "openrouter" | "openai" | "nim" | "custom". */
    val aiProvider: Flow<String> = context.dataStore.data.map { it[Keys.AI_PROVIDER] ?: "openrouter" }
    /** The OpenAI-compatible base URL (no trailing slash, no /chat/completions suffix) for the
     *  active provider - e.g. https://openrouter.ai/api/v1. Only meaningfully editable when
     *  aiProvider == "custom"; for the built-in presets this mirrors AiProviderPresets. */
    val aiBaseUrl: Flow<String> = context.dataStore.data.map { it[Keys.AI_BASE_URL] ?: "https://openrouter.ai/api/v1" }

    val proxyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.PROXY_ENABLED] ?: false }
    val proxyRotationMode: Flow<String> = context.dataStore.data.map { it[Keys.PROXY_ROTATION_MODE] ?: "MANUAL" }
    val proxyRotationIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.PROXY_ROTATION_INTERVAL_MIN] ?: 10 }
    val proxyBypassRulesJson: Flow<String> = context.dataStore.data.map { it[Keys.PROXY_BYPASS_RULES_JSON] ?: "[\"<local>\"]" }
    val proxyActiveIndex: Flow<Int> = context.dataStore.data.map { it[Keys.PROXY_ACTIVE_INDEX] ?: 0 }

    val fingerprintProtectionEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FINGERPRINT_PROTECTION_ENABLED] ?: false }
    val fingerprintSpoofCanvas: Flow<Boolean> = context.dataStore.data.map { it[Keys.FINGERPRINT_SPOOF_CANVAS] ?: true }
    val fingerprintSpoofWebGl: Flow<Boolean> = context.dataStore.data.map { it[Keys.FINGERPRINT_SPOOF_WEBGL] ?: true }
    val fingerprintSpoofHardware: Flow<Boolean> = context.dataStore.data.map { it[Keys.FINGERPRINT_SPOOF_HARDWARE] ?: true }
    val fingerprintDeviceSeed: Flow<String> = context.dataStore.data.map { prefs -> prefs[Keys.FINGERPRINT_DEVICE_SEED] ?: "" }

    /** Path to a fully-downloaded update APK waiting to be installed, and which version it is - survives navigation, backgrounding, even process death. */
    val pendingUpdateApkPath: Flow<String?> = context.dataStore.data.map { it[Keys.PENDING_UPDATE_APK_PATH] }
    val pendingUpdateVersion: Flow<String?> = context.dataStore.data.map { it[Keys.PENDING_UPDATE_VERSION] }

    suspend fun setPendingUpdate(apkPath: String?, version: String?) {
        context.dataStore.edit { prefs ->
            if (apkPath == null) prefs.remove(Keys.PENDING_UPDATE_APK_PATH) else prefs[Keys.PENDING_UPDATE_APK_PATH] = apkPath
            if (version == null) prefs.remove(Keys.PENDING_UPDATE_VERSION) else prefs[Keys.PENDING_UPDATE_VERSION] = version
        }
    }

    /** Reads the persisted device fingerprint seed, generating and saving one on first use. */
    suspend fun getOrCreateFingerprintSeed(): String {
        val existing = context.dataStore.data.map { it[Keys.FINGERPRINT_DEVICE_SEED] }.first()
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.FINGERPRINT_DEVICE_SEED] = generated }
        return generated
    }

    suspend fun setSearchEngine(url: String) { context.dataStore.edit { it[Keys.SEARCH_ENGINE] = url } }
    suspend fun setHomepage(url: String) { context.dataStore.edit { it[Keys.HOMEPAGE] = url } }
    suspend fun setDarkMode(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_DARK_MODE] = enabled } }
    suspend fun setAdBlockerEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_AD_BLOCKER_ENABLED] = enabled } }
    suspend fun setTrackerBlockerEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_TRACKER_BLOCKER_ENABLED] = enabled } }
    suspend fun setHttpsUpgrade(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_HTTPS_UPGRADE] = enabled } }
    suspend fun setJavascriptEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.IS_JAVASCRIPT_ENABLED] = enabled } }
    suspend fun setMaxConcurrentDownloads(count: Int) { context.dataStore.edit { it[Keys.MAX_CONCURRENT_DOWNLOADS] = count } }
    suspend fun setAxStorageRootUri(uri: String) { context.dataStore.edit { it[Keys.DOWNLOAD_FOLDER_URI] = uri } }
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
    suspend fun setOpenRouterApiKey(key: String?) {
        context.dataStore.edit {
            if (key.isNullOrBlank()) it.remove(Keys.OPENROUTER_API_KEY_ENC)
            else it[Keys.OPENROUTER_API_KEY_ENC] = cryptoManager.encrypt(key)
        }
    }
    suspend fun setAiModel(modelId: String) { context.dataStore.edit { it[Keys.AI_MODEL] = modelId } }
    suspend fun setAiProvider(providerId: String) { context.dataStore.edit { it[Keys.AI_PROVIDER] = providerId } }
    suspend fun setAiBaseUrl(url: String) { context.dataStore.edit { it[Keys.AI_BASE_URL] = url } }

    suspend fun setProxyEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.PROXY_ENABLED] = enabled } }
    suspend fun setProxyRotationMode(mode: String) { context.dataStore.edit { it[Keys.PROXY_ROTATION_MODE] = mode } }
    suspend fun setProxyRotationIntervalMinutes(minutes: Int) { context.dataStore.edit { it[Keys.PROXY_ROTATION_INTERVAL_MIN] = minutes } }
    suspend fun setProxyBypassRulesJson(json: String) { context.dataStore.edit { it[Keys.PROXY_BYPASS_RULES_JSON] = json } }
    suspend fun setProxyActiveIndex(index: Int) { context.dataStore.edit { it[Keys.PROXY_ACTIVE_INDEX] = index } }

    suspend fun setFingerprintProtectionEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.FINGERPRINT_PROTECTION_ENABLED] = enabled } }
    suspend fun setFingerprintSpoofCanvas(enabled: Boolean) { context.dataStore.edit { it[Keys.FINGERPRINT_SPOOF_CANVAS] = enabled } }
    suspend fun setFingerprintSpoofWebGl(enabled: Boolean) { context.dataStore.edit { it[Keys.FINGERPRINT_SPOOF_WEBGL] = enabled } }
    suspend fun setFingerprintSpoofHardware(enabled: Boolean) { context.dataStore.edit { it[Keys.FINGERPRINT_SPOOF_HARDWARE] = enabled } }
    suspend fun regenerateFingerprintSeed(): String {
        val generated = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.FINGERPRINT_DEVICE_SEED] = generated }
        return generated
    }
}
