package com.anthropic.balanceapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "balance_app_settings")

data class AppSettings(
    val apiKey: String = "",
    val updateIntervalMinutes: Int = 60,
    val monthlyBudgetUsd: Double = 50.0,
    val alertUsageThresholdPercent: Int = 80,
    val alertBalanceThresholdUsd: Double = 40.0,
    val widgetTransparencyPercent: Int = 10,
    val widgetTheme: WidgetTheme = WidgetTheme.AUTO,
    val alertsEnabled: Boolean = true
)

data class CachedUsageData(
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val periodStart: String = "",
    val periodEnd: String = "",
    val fetchedAtMs: Long = 0L,
    val lastError: String = ""
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
        val API_KEY = stringPreferencesKey("api_key")
        val UPDATE_INTERVAL_MINUTES = intPreferencesKey("update_interval_minutes")
        val MONTHLY_BUDGET_USD = doublePreferencesKey("monthly_budget_usd")
        val ALERT_USAGE_THRESHOLD = intPreferencesKey("alert_usage_threshold")
        val ALERT_BALANCE_THRESHOLD = doublePreferencesKey("alert_balance_threshold")
        val WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")
        val WIDGET_THEME = stringPreferencesKey("widget_theme")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")

        // Cached data keys
        val CACHED_INPUT_TOKENS = longPreferencesKey("cached_input_tokens")
        val CACHED_OUTPUT_TOKENS = longPreferencesKey("cached_output_tokens")
        val CACHED_COST_USD = doublePreferencesKey("cached_cost_usd")
        val CACHED_PERIOD_START = stringPreferencesKey("cached_period_start")
        val CACHED_PERIOD_END = stringPreferencesKey("cached_period_end")
        val CACHED_FETCHED_AT_MS = longPreferencesKey("cached_fetched_at_ms")
        val CACHED_LAST_ERROR = stringPreferencesKey("cached_last_error")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            AppSettings(
                apiKey = prefs[API_KEY] ?: "",
                updateIntervalMinutes = prefs[UPDATE_INTERVAL_MINUTES] ?: 60,
                monthlyBudgetUsd = prefs[MONTHLY_BUDGET_USD] ?: 50.0,
                alertUsageThresholdPercent = prefs[ALERT_USAGE_THRESHOLD] ?: 80,
                alertBalanceThresholdUsd = prefs[ALERT_BALANCE_THRESHOLD] ?: 40.0,
                widgetTransparencyPercent = prefs[WIDGET_TRANSPARENCY] ?: 10,
                widgetTheme = WidgetTheme.fromValue(prefs[WIDGET_THEME] ?: "auto"),
                alertsEnabled = prefs[ALERTS_ENABLED] ?: true
            )
        }

    val cachedUsageFlow: Flow<CachedUsageData> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            CachedUsageData(
                totalInputTokens = prefs[CACHED_INPUT_TOKENS] ?: 0L,
                totalOutputTokens = prefs[CACHED_OUTPUT_TOKENS] ?: 0L,
                estimatedCostUsd = prefs[CACHED_COST_USD] ?: 0.0,
                periodStart = prefs[CACHED_PERIOD_START] ?: "",
                periodEnd = prefs[CACHED_PERIOD_END] ?: "",
                fetchedAtMs = prefs[CACHED_FETCHED_AT_MS] ?: 0L,
                lastError = prefs[CACHED_LAST_ERROR] ?: ""
            )
        }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = settings.apiKey
            prefs[UPDATE_INTERVAL_MINUTES] = settings.updateIntervalMinutes
            prefs[MONTHLY_BUDGET_USD] = settings.monthlyBudgetUsd
            prefs[ALERT_USAGE_THRESHOLD] = settings.alertUsageThresholdPercent
            prefs[ALERT_BALANCE_THRESHOLD] = settings.alertBalanceThresholdUsd
            prefs[WIDGET_TRANSPARENCY] = settings.widgetTransparencyPercent
            prefs[WIDGET_THEME] = settings.widgetTheme.value
            prefs[ALERTS_ENABLED] = settings.alertsEnabled
        }
    }

    suspend fun saveCachedUsage(
        inputTokens: Long,
        outputTokens: Long,
        costUsd: Double,
        periodStart: String,
        periodEnd: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[CACHED_INPUT_TOKENS] = inputTokens
            prefs[CACHED_OUTPUT_TOKENS] = outputTokens
            prefs[CACHED_COST_USD] = costUsd
            prefs[CACHED_PERIOD_START] = periodStart
            prefs[CACHED_PERIOD_END] = periodEnd
            prefs[CACHED_FETCHED_AT_MS] = System.currentTimeMillis()
            prefs[CACHED_LAST_ERROR] = ""
        }
    }

    suspend fun saveCacheError(errorMessage: String) {
        context.dataStore.edit { prefs ->
            prefs[CACHED_LAST_ERROR] = errorMessage
            prefs[CACHED_FETCHED_AT_MS] = System.currentTimeMillis()
        }
    }

    suspend fun getApiKey(): String {
        return context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[API_KEY] ?: "" }
            .first()
    }

    suspend fun getSettings(): AppSettings {
        return settingsFlow.first()
    }
}
