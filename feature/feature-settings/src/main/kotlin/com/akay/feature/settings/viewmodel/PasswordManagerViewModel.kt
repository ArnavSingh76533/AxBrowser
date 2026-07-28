package com.akay.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.domain.model.SavedCredential
import com.akay.core.domain.repository.PasswordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PasswordManagerViewModel @Inject constructor(
    private val passwordRepository: PasswordRepository
) : ViewModel() {

    val credentials: StateFlow<List<SavedCredential>> = passwordRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(credential: SavedCredential) {
        viewModelScope.launch { passwordRepository.delete(credential) }
    }
}
