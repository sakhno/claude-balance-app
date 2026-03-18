package com.anthropic.balanceapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "balance_app_settings")

data class AppSettings(
    val claudeSessionToken: String = "",
    val anthropicApiKey: String = "",
    val anthropicAdminKey: String = "",
    val platformRoutingHint: String = "",
    val updateIntervalMinutes: Int = 60,
    val alertSessionThresholdPercent: Int = 80,
    val alertWeeklyThresholdPercent: Int = 80,
    val alertBalanceThresholdUsd: Double = 1.0,
    val widgetTransparencyPercent: Int = 10,
    val widgetTheme: WidgetTheme = WidgetTheme.AUTO,
    val alertsEnabled: Boolean = true
)

enum class WidgetTheme(val value: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String) = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

class AppDataStore(private val context: Context) {

    companion object {
        // Credentials
        val CLAUDE_SESSION_TOKEN = stringPreferencesKey("claude_session_token")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val ANTHROPIC_ADMIN_KEY = stringPreferencesKey("anthropic_admin_key")
        val PLATFORM_ROUTING_HINT = stringPreferencesKey("platform_routing_hint")

        // Widget & sync settings
        val UPDATE_INTERVAL_MINUTES = intPreferencesKey("update_interval_minutes")
        val ALERT_SESSION_THRESHOLD = intPreferencesKey("alert_session_threshold")
        val ALERT_WEEKLY_THRESHOLD = intPreferencesKey("alert_weekly_threshold")
        val ALERT_BALANCE_THRESHOLD = doublePreferencesKey("alert_balance_threshold")
        val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")
        val WIDGET_THEME = stringPreferencesKey("widget_theme")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")

        // Cached ClaudeUsageData
        val CLAUDE_SESSION_PERCENT = intPreferencesKey("claude_session_percent")
        val CLAUDE_SESSION_RESET_AT_MS = longPreferencesKey("claude_session_reset_at_ms")
        val CLAUDE_WEEKLY_PERCENT = intPreferencesKey("claude_weekly_percent")
        val CLAUDE_WEEKLY_RESET_AT_MS = longPreferencesKey("claude_weekly_reset_at_ms")
        val CLAUDE_FETCHED_AT_MS = longPreferencesKey("claude_fetched_at_ms")
        val CLAUDE_LAST_ERROR = stringPreferencesKey("claude_last_error")
        val CLAUDE_DATA_UNAVAILABLE = booleanPreferencesKey("claude_data_unavailable")

        // Cached ApiBalance
        val BALANCE_REMAINING_USD = doublePreferencesKey("balance_remaining_usd")
        val BALANCE_PENDING_USD = doublePreferencesKey("balance_pending_usd")
        val BALANCE_FETCHED_AT_MS = longPreferencesKey("balance_fetched_at_ms")
        val BALANCE_LAST_ERROR = stringPreferencesKey("balance_last_error")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            AppSettings(
                claudeSessionToken = prefs[CLAUDE_SESSION_TOKEN] ?: "",
                anthropicApiKey = prefs[ANTHROPIC_API_KEY] ?: "",
                anthropicAdminKey = prefs[ANTHROPIC_ADMIN_KEY] ?: "",
                platformRoutingHint = prefs[PLATFORM_ROUTING_HINT] ?: "",
                updateIntervalMinutes = prefs[UPDATE_INTERVAL_MINUTES] ?: 60,
                alertSessionThresholdPercent = prefs[ALERT_SESSION_THRESHOLD] ?: 80,
                alertWeeklyThresholdPercent = prefs[ALERT_WEEKLY_THRESHOLD] ?: 80,
                alertBalanceThresholdUsd = prefs[ALERT_BALANCE_THRESHOLD] ?: 1.0,
                widgetTransparencyPercent = prefs[WIDGET_TRANSPARENCY] ?: 10,
                widgetTheme = WidgetTheme.fromValue(prefs[WIDGET_THEME] ?: "auto"),
                alertsEnabled = prefs[ALERTS_ENABLED] ?: true
            )
        }

    val claudeUsageFlow: Flow<ClaudeUsageData> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            ClaudeUsageData(
                sessionPercent = prefs[CLAUDE_SESSION_PERCENT] ?: 0,
                sessionResetAtMs = prefs[CLAUDE_SESSION_RESET_AT_MS] ?: 0L,
                weeklyPercent = prefs[CLAUDE_WEEKLY_PERCENT] ?: 0,
                weeklyResetAtMs = prefs[CLAUDE_WEEKLY_RESET_AT_MS] ?: 0L,
                fetchedAtMs = prefs[CLAUDE_FETCHED_AT_MS] ?: 0L,
                lastError = prefs[CLAUDE_LAST_ERROR] ?: "",
                dataUnavailable = prefs[CLAUDE_DATA_UNAVAILABLE] ?: false
            )
        }

    val apiBalanceFlow: Flow<ApiBalance> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            ApiBalance(
                remainingUsd = prefs[BALANCE_REMAINING_USD] ?: 0.0,
                pendingUsd = prefs[BALANCE_PENDING_USD] ?: 0.0,
                fetchedAtMs = prefs[BALANCE_FETCHED_AT_MS] ?: 0L,
                lastError = prefs[BALANCE_LAST_ERROR] ?: ""
            )
        }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[CLAUDE_SESSION_TOKEN] = settings.claudeSessionToken
            prefs[ANTHROPIC_API_KEY] = settings.anthropicApiKey
            prefs[ANTHROPIC_ADMIN_KEY] = settings.anthropicAdminKey
            prefs[PLATFORM_ROUTING_HINT] = settings.platformRoutingHint
            prefs[UPDATE_INTERVAL_MINUTES] = settings.updateIntervalMinutes
            prefs[ALERT_SESSION_THRESHOLD] = settings.alertSessionThresholdPercent
            prefs[ALERT_WEEKLY_THRESHOLD] = settings.alertWeeklyThresholdPercent
            prefs[ALERT_BALANCE_THRESHOLD] = settings.alertBalanceThresholdUsd
            prefs[WIDGET_TRANSPARENCY] = settings.widgetTransparencyPercent
            prefs[WIDGET_THEME] = settings.widgetTheme.value
            prefs[ALERTS_ENABLED] = settings.alertsEnabled
        }
    }

    suspend fun saveClaudeUsage(data: ClaudeUsageData) {
        context.dataStore.edit { prefs ->
            prefs[CLAUDE_SESSION_PERCENT] = data.sessionPercent
            prefs[CLAUDE_SESSION_RESET_AT_MS] = data.sessionResetAtMs
            prefs[CLAUDE_WEEKLY_PERCENT] = data.weeklyPercent
            prefs[CLAUDE_WEEKLY_RESET_AT_MS] = data.weeklyResetAtMs
            prefs[CLAUDE_FETCHED_AT_MS] = System.currentTimeMillis()
            prefs[CLAUDE_LAST_ERROR] = ""
            prefs[CLAUDE_DATA_UNAVAILABLE] = data.dataUnavailable
        }
    }

    suspend fun saveClaudeUsageError(errorMessage: String) {
        context.dataStore.edit { prefs ->
            prefs[CLAUDE_LAST_ERROR] = errorMessage
            prefs[CLAUDE_FETCHED_AT_MS] = System.currentTimeMillis()
        }
    }

    suspend fun saveApiBalance(data: ApiBalance) {
        context.dataStore.edit { prefs ->
            prefs[BALANCE_REMAINING_USD] = data.remainingUsd
            prefs[BALANCE_PENDING_USD] = data.pendingUsd
            prefs[BALANCE_FETCHED_AT_MS] = System.currentTimeMillis()
            prefs[BALANCE_LAST_ERROR] = ""
        }
    }

    suspend fun saveApiBalanceError(errorMessage: String) {
        context.dataStore.edit { prefs ->
            prefs[BALANCE_LAST_ERROR] = errorMessage
            prefs[BALANCE_FETCHED_AT_MS] = System.currentTimeMillis()
        }
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun getClaudeUsage(): ClaudeUsageData = claudeUsageFlow.first()

    suspend fun getApiBalance(): ApiBalance = apiBalanceFlow.first()
}
