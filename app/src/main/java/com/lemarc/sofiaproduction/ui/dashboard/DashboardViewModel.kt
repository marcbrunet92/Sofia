package com.lemarc.sofiaproduction.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lemarc.sofiaproduction.data.AppSettings
import com.lemarc.sofiaproduction.data.FarmSnapshot
import com.lemarc.sofiaproduction.data.RecordsData
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

    /** Number of days to display in the chart (1–maxChartDays). */
    private val _chartDays = MutableStateFlow(2)
    val chartDays: StateFlow<Int> = _chartDays.asStateFlow()

    /**
     * Maximum selectable chart days.
     * Legacy API: always 14.
     * Sofia API: set from the /latest-date endpoint (capped at MAX_CHART_DAYS_SOFIA).
     */
    private val _maxChartDays = MutableStateFlow(MAX_CHART_DAYS_LEGACY)
    val maxChartDays: StateFlow<Int> = _maxChartDays.asStateFlow()

    /** Peak generation records — non-null only when the Sofia API is active. */
    private val _records = MutableStateFlow<RecordsData?>(null)
    val records: StateFlow<RecordsData?> = _records.asStateFlow()

    // Refresh every 30 minutes automatically
    private val POLL_INTERVAL_MS = 30 * 60 * 1000L

    /** Tracks the API mode at last load so we can detect settings changes. */
    private var lastApiMode: String = AppSettings.getApiMode()

    companion object {
        /** Days fetched from the legacy (Robinhawkes) API. */
        const val MAX_CHART_DAYS_LEGACY = 14

        /** Maximum days to request from the Sofia API (caps the slider). */
        const val MAX_CHART_DAYS_SOFIA = 90
    }

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

    /**
     * Called from DashboardFragment.onResume() to detect API mode changes made
     * in the Settings screen and trigger a reload when needed.
     */
    fun onResume() {
        val currentMode = AppSettings.getApiMode()
        if (currentMode != lastApiMode) {
            lastApiMode = currentMode
            if (currentMode == AppSettings.API_MODE_LEGACY) {
                _maxChartDays.value = MAX_CHART_DAYS_LEGACY
                _records.value = null
            }
            viewModelScope.launch { load() }
        }
    }

    /** Update the chart time window (clamped to [1, maxChartDays]). No API call needed —
     *  the full history is already loaded; the fragment slices it in-memory. */
    fun setChartDays(days: Int) {
        val clamped = days.coerceIn(1, _maxChartDays.value)
        if (_chartDays.value != clamped) {
            _chartDays.value = clamped
        }
    }

    private suspend fun load() {
        val isNewApi = AppSettings.getApiMode() == AppSettings.API_MODE_SOFIA

        val snapshotResult = if (isNewApi) {
            repo.fetchSnapshotFromSofiaApi(days = MAX_CHART_DAYS_SOFIA)
        } else {
            repo.fetchSnapshot(days = MAX_CHART_DAYS_LEGACY)
        }

        snapshotResult
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

        if (isNewApi) {
            repo.fetchDataRange().onSuccess { info ->
                _maxChartDays.value = info.totalDays.coerceIn(1, MAX_CHART_DAYS_SOFIA)
            }
            repo.fetchRecords().onSuccess { data ->
                _records.value = data
            }
        }
    }
}

