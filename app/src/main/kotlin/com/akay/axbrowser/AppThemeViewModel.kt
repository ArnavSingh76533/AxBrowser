package com.akay.axbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.datastore.AxPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AppThemeState(
    val isDarkMode: Boolean = true,
    val amoled: Boolean = true,
    val accentName: String = "Nebula Violet",
    val galaxyEnabled: Boolean = true,
    val animationIntensity: Int = 100
)

@HiltViewModel
class AppThemeViewModel @Inject constructor(
    preferences: AxPreferences
) : ViewModel() {

    val state: StateFlow<AppThemeState> =
        combine(
            preferences.isDarkMode,
            preferences.amoledTheme,
            preferences.accentColor,
            preferences.galaxyEnabled,
            preferences.animationIntensity
        ) { dark, amoled, accent, galaxy, intensity ->
            AppThemeState(
                isDarkMode = dark,
                amoled = amoled,
                accentName = accent,
                galaxyEnabled = galaxy,
                animationIntensity = intensity
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppThemeState())
}
