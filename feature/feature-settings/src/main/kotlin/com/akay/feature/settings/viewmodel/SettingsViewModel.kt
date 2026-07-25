package com.akay.feature.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.datastore.AxPreferences
import com.akay.feature.downloads.engine.YtDlpSetup
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isDarkMode: Boolean = true,
    val isAdBlockerEnabled: Boolean = true,
    val isHttpsUpgrade: Boolean = true,
    val isJavascriptEnabled: Boolean = true,
    val maxConcurrentDownloads: Int = 3,
    val isDesktopMode: Boolean = false,
    val fontSize: Int = 100,
    val clearCacheOnExit: Boolean = false,
    val isErudaEnabled: Boolean = false,
    val ytDlpInstalled: Boolean = false,
    val ytDlpUpdateStatus: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AxPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferences.isDarkMode.collect { _uiState.value = _uiState.value.copy(isDarkMode = it) }
        }
        viewModelScope.launch {
            preferences.isAdBlockerEnabled.collect { _uiState.value = _uiState.value.copy(isAdBlockerEnabled = it) }
        }
        viewModelScope.launch {
            preferences.isHttpsUpgrade.collect { _uiState.value = _uiState.value.copy(isHttpsUpgrade = it) }
        }
        viewModelScope.launch {
            preferences.isJavascriptEnabled.collect { _uiState.value = _uiState.value.copy(isJavascriptEnabled = it) }
        }
        viewModelScope.launch {
            preferences.maxConcurrentDownloads.collect { _uiState.value = _uiState.value.copy(maxConcurrentDownloads = it) }
        }
        viewModelScope.launch {
            preferences.isDesktopMode.collect { _uiState.value = _uiState.value.copy(isDesktopMode = it) }
        }
        viewModelScope.launch {
            preferences.fontSize.collect { _uiState.value = _uiState.value.copy(fontSize = it) }
        }
        viewModelScope.launch {
            preferences.clearCacheOnExit.collect { _uiState.value = _uiState.value.copy(clearCacheOnExit = it) }
        }
        viewModelScope.launch {
            preferences.erudaEnabled.collect { _uiState.value = _uiState.value.copy(isErudaEnabled = it) }
        }
        _uiState.value = _uiState.value.copy(
            ytDlpInstalled = YtDlpSetup.isInstalled(context)
        )
    }

    fun setDarkMode(enabled: Boolean) { viewModelScope.launch { preferences.setDarkMode(enabled) } }
    fun setAdBlockerEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setAdBlockerEnabled(enabled) } }
    fun setHttpsUpgrade(enabled: Boolean) { viewModelScope.launch { preferences.setHttpsUpgrade(enabled) } }
    fun setJavascriptEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setJavascriptEnabled(enabled) } }
    fun setDesktopMode(enabled: Boolean) { viewModelScope.launch { preferences.setDesktopMode(enabled) } }
    fun setFontSize(size: Int) { viewModelScope.launch { preferences.setFontSize(size) } }
    fun setClearCacheOnExit(enabled: Boolean) { viewModelScope.launch { preferences.setClearCacheOnExit(enabled) } }
    fun setErudaEnabled(enabled: Boolean) { viewModelScope.launch { preferences.setErudaEnabled(enabled) } }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Updating...")
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context, YoutubeDL.UpdateChannel.STABLE
                )
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> {
                        _uiState.value = _uiState.value.copy(
                            ytDlpUpdateStatus = "Updated successfully",
                            ytDlpInstalled = true
                        )
                    }
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                        _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Already up to date")
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Update status: $status")
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(ytDlpUpdateStatus = "Update failed: ${e.message}")
            }
        }
    }
}
