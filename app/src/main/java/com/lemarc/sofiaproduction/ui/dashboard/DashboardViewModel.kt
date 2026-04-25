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

    /** Whether the chart is being reloaded due to a period change (rest of UI stays visible). */
    private val _chartLoading = MutableStateFlow(false)
    val chartLoading: StateFlow<Boolean> = _chartLoading.asStateFlow()

    /** Number of days to display in the chart (1–14). */
    private val _chartDays = MutableStateFlow(2)
    val chartDays: StateFlow<Int> = _chartDays.asStateFlow()

    // Refresh every 30 minutes automatically
    private val POLL_INTERVAL_MS = 30 * 60 * 1000L

    init {
        // Restore last successful snapshot immediately so the loading screen
        // is never shown on repeat visits — the background refresh will update
        // the data silently once the API responds.
        repo.getCachedSnapshot()?.let { _uiState.value = UiState.Success(it) }
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

    /** Update the chart time window (clamped to 1–14 days). Triggers a reload. */
    fun setChartDays(days: Int) {
        val clamped = days.coerceIn(1, 14)
        if (_chartDays.value != clamped) {
            _chartDays.value = clamped
            viewModelScope.launch {
                _chartLoading.value = true
                load()
                _chartLoading.value = false
            }
        }
    }

    private suspend fun load() {
        repo.fetchSnapshot(days = _chartDays.value)
            .onSuccess { snapshot ->
                _uiState.value = UiState.Success(snapshot)
                repo.cacheSnapshot(snapshot)
            }
            .onFailure { e ->
                // Keep last good data visible if we already had it
                if (_uiState.value !is UiState.Success) {
                    _uiState.value = UiState.Error(e.message ?: "Unknown error")
                }
            }
    }
}
