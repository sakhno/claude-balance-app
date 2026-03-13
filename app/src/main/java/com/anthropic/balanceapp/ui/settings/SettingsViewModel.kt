package com.anthropic.balanceapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.balanceapp.api.AnthropicApiClient
import com.anthropic.balanceapp.api.ApiResult
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.data.AppSettings
import com.anthropic.balanceapp.data.CachedUsageData
import com.anthropic.balanceapp.data.WidgetTheme
import com.anthropic.balanceapp.worker.SyncWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val cachedUsage: CachedUsageData = CachedUsageData(),
    val isValidatingKey: Boolean = false,
    val keyValidationResult: String? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val isSyncing: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = AppDataStore(application)
    private val apiClient = AnthropicApiClient()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settingsFlow
                .combine(dataStore.cachedUsageFlow) { settings, usage ->
                    settings to usage
                }
                .collect { (settings, usage) ->
                    _uiState.update {
                        it.copy(settings = settings, cachedUsage = usage)
                    }
                }
        }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(settings = it.settings.copy(apiKey = key)) }
    }

    fun updateIntervalMinutes(minutes: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(updateIntervalMinutes = minutes)) }
    }

    fun updateBudget(budget: Double) {
        _uiState.update { it.copy(settings = it.settings.copy(monthlyBudgetUsd = budget)) }
    }

    fun updateUsageAlertThreshold(percent: Int) {
        _uiState.update {
            it.copy(settings = it.settings.copy(alertUsageThresholdPercent = percent))
        }
    }

    fun updateBalanceAlert(dollars: Double) {
        _uiState.update {
            it.copy(settings = it.settings.copy(alertBalanceThresholdUsd = dollars))
        }
    }

    fun updateWidgetTransparency(percent: Int) {
        _uiState.update {
            it.copy(settings = it.settings.copy(widgetTransparencyPercent = percent))
        }
    }

    fun updateWidgetTheme(theme: WidgetTheme) {
        _uiState.update { it.copy(settings = it.settings.copy(widgetTheme = theme)) }
    }

    fun updateAlertsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(alertsEnabled = enabled)) }
    }

    fun validateApiKey() {
        val key = _uiState.value.settings.apiKey
        if (key.isBlank()) {
            _uiState.update { it.copy(keyValidationResult = "Please enter an API key") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingKey = true, keyValidationResult = null) }
            val result = apiClient.validateApiKey(key)
            val message = when (result) {
                is ApiResult.Success -> "API key is valid!"
                is ApiResult.Error -> "Invalid: ${result.message}"
                is ApiResult.NetworkError -> "Network error — check connection"
            }
            _uiState.update {
                it.copy(isValidatingKey = false, keyValidationResult = message)
            }
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = false) }
            val settings = _uiState.value.settings
            dataStore.saveSettings(settings)

            // Reschedule WorkManager with new interval
            SyncWorker.schedule(getApplication(), settings.updateIntervalMinutes)

            _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            SyncWorker.runNow(getApplication())
            // Give it a moment to kick off; WorkManager is async
            kotlinx.coroutines.delay(500)
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}
