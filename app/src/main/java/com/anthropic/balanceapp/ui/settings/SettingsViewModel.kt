package com.anthropic.balanceapp.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.balanceapp.api.AnthropicBalanceClient
import com.anthropic.balanceapp.api.ApiResult
import com.anthropic.balanceapp.api.ClaudeAiApiClient
import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.data.AppSettings
import com.anthropic.balanceapp.data.WidgetTheme
import com.anthropic.balanceapp.worker.SyncWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val claudeUsage: ClaudeUsageData = ClaudeUsageData(),
    val apiBalance: ApiBalance = ApiBalance(),

    // Session token validation
    val isValidatingSessionToken: Boolean = false,
    val sessionTokenValidationResult: String? = null,

    // API key validation
    val isValidatingApiKey: Boolean = false,
    val apiKeyValidationResult: String? = null,

    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val isSyncing: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = AppDataStore(application)
    private val claudeClient = ClaudeAiApiClient()
    private val balanceClient = AnthropicBalanceClient()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                dataStore.settingsFlow,
                dataStore.claudeUsageFlow,
                dataStore.apiBalanceFlow
            ) { settings, usage, balance ->
                Triple(settings, usage, balance)
            }.collect { (settings, usage, balance) ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        claudeUsage = usage,
                        apiBalance = balance
                    )
                }
            }
        }
    }

    // ── Settings field updaters ───────────────────────────────────────────────

    fun updateClaudeSessionToken(token: String) {
        _uiState.update { it.copy(settings = it.settings.copy(claudeSessionToken = token)) }
    }

    fun updateAnthropicApiKey(key: String) {
        _uiState.update { it.copy(settings = it.settings.copy(anthropicApiKey = key)) }
    }

    fun updateIntervalMinutes(minutes: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(updateIntervalMinutes = minutes)) }
    }

    fun updateSessionAlertThreshold(percent: Int) {
        _uiState.update {
            it.copy(settings = it.settings.copy(alertSessionThresholdPercent = percent))
        }
    }

    fun updateWeeklyAlertThreshold(percent: Int) {
        _uiState.update {
            it.copy(settings = it.settings.copy(alertWeeklyThresholdPercent = percent))
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

    // ── Validation ────────────────────────────────────────────────────────────

    fun validateSessionToken() {
        val token = _uiState.value.settings.claudeSessionToken
        if (token.isBlank()) {
            _uiState.update { it.copy(sessionTokenValidationResult = "Please enter a session token") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isValidatingSessionToken = true, sessionTokenValidationResult = null)
            }
            val result = claudeClient.validateSessionToken(token)
            val message = when (result) {
                is ApiResult.Success -> "Session token is valid!"
                is ApiResult.Error -> "Invalid: ${result.message}"
                is ApiResult.NetworkError -> "Network error — check connection"
            }
            _uiState.update {
                it.copy(isValidatingSessionToken = false, sessionTokenValidationResult = message)
            }
        }
    }

    fun validateApiKey() {
        val key = _uiState.value.settings.anthropicApiKey
        if (key.isBlank()) {
            _uiState.update { it.copy(apiKeyValidationResult = "Please enter an API key") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isValidatingApiKey = true, apiKeyValidationResult = null)
            }
            val result = balanceClient.validateApiKey(key)
            val message = when (result) {
                is ApiResult.Success -> "API key is valid!"
                is ApiResult.Error -> "Invalid: ${result.message}"
                is ApiResult.NetworkError -> "Network error — check connection"
            }
            _uiState.update {
                it.copy(isValidatingApiKey = false, apiKeyValidationResult = message)
            }
        }
    }

    // ── Save & sync ───────────────────────────────────────────────────────────

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveSuccess = false) }
            val settings = _uiState.value.settings
            dataStore.saveSettings(settings)
            SyncWorker.schedule(getApplication(), settings.updateIntervalMinutes)
            _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            SyncWorker.runNow(getApplication())
            kotlinx.coroutines.delay(500)
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    fun clearSessionTokenValidation() {
        _uiState.update { it.copy(sessionTokenValidationResult = null) }
    }

    fun clearApiKeyValidation() {
        _uiState.update { it.copy(apiKeyValidationResult = null) }
    }
}
