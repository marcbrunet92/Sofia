package com.lemarc.sofiaproduction.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lemarc.sofiaproduction.data.FarmSnapshot
import com.lemarc.sofiaproduction.data.SofiaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class UiState {
    object Loading : UiState()
    data class Success(val snapshot: FarmSnapshot) : UiState()
    data class Error(val message: String) : UiState()
}

class DashboardViewModel(
    private val repo: SofiaRepository = SofiaRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Whether a manual refresh is in progress (for swipe-to-refresh spinner). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Refresh every 30 minutes automatically
    private val POLL_INTERVAL_MS = 30 * 60 * 1000L

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                load()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            load()
            _refreshing.value = false
        }
    }

    private suspend fun load() {
        repo.fetchSnapshot()
            .onSuccess { snapshot ->
                _uiState.value = UiState.Success(snapshot)
            }
            .onFailure { e ->
                // Keep last good data visible if we already had it
                if (_uiState.value !is UiState.Success) {
                    _uiState.value = UiState.Error(e.message ?: "Unknown error")
                }
            }
    }
}
