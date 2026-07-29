package com.akay.axbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.data.datastore.AxPreferences
import com.akay.core.ui.security.PinHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppLockConfig(
    val enabled: Boolean = false,
    val pinHash: String? = null,
    val useBiometric: Boolean = true
)

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val preferences: AxPreferences
) : ViewModel() {

    val config: StateFlow<AppLockConfig> = combine(
        preferences.appLockEnabled,
        preferences.appLockPinHash,
        preferences.appLockUseBiometric
    ) { enabled, pinHash, useBiometric -> AppLockConfig(enabled, pinHash, useBiometric) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLockConfig())

    // True once unlocked for this process lifetime; reset when the app goes to background.
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun markUnlocked() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun checkPin(pin: String): Boolean {
        val hash = config.value.pinHash ?: return false
        return PinHasher.matches(pin, hash)
    }

    fun setPin(pin: String) {
        viewModelScope.launch { preferences.setAppLockPinHash(PinHasher.hash(pin)) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAppLockEnabled(enabled) }
    }

    fun setUseBiometric(enabled: Boolean) {
        viewModelScope.launch { preferences.setAppLockUseBiometric(enabled) }
    }
}
