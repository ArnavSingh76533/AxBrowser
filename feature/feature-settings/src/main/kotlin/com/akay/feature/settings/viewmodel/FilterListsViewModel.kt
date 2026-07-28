package com.akay.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akay.core.domain.repository.AdBlockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilterListUiItem(
    val url: String,
    val hostCount: Int
)

data class FilterListsUiState(
    val subscriptions: List<FilterListUiItem> = emptyList(),
    val bundledCount: Int = 0,
    val isRefreshing: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class FilterListsViewModel @Inject constructor(
    private val adBlockRepository: AdBlockRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilterListsUiState())
    val uiState: StateFlow<FilterListsUiState> = _uiState.asStateFlow()

    val presetLists = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts" to "StevenBlack Unified Hosts",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/BaseFilter/sections/adservers.txt" to "AdGuard Base (ad servers)",
        "https://raw.githubusercontent.com/anudeepND/blacklist/master/adservers.txt" to "anudeepND Adservers"
    )

    init {
        viewModelScope.launch {
            adBlockRepository.observeSubscriptionSources().collect { sources ->
                val items = sources.map { url ->
                    FilterListUiItem(url = url, hostCount = adBlockRepository.countForSource(url))
                }
                _uiState.value = _uiState.value.copy(subscriptions = items)
            }
        }
    }

    fun addSubscription(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, message = null)
            val result = adBlockRepository.addOrRefreshSubscription(url.trim())
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                message = result.fold(
                    onSuccess = { "Added $it hosts from list" },
                    onFailure = { "Failed to load list: ${it.message}" }
                )
            )
        }
    }

    fun refreshSubscription(url: String) = addSubscription(url)

    fun removeSubscription(url: String) {
        viewModelScope.launch { adBlockRepository.removeSubscription(url) }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
