package com.anthropic.balanceapp.worker

import android.content.Context
import androidx.work.*
import com.anthropic.balanceapp.api.AnthropicBalanceClient
import com.anthropic.balanceapp.api.ApiResult
import com.anthropic.balanceapp.api.ClaudeAiApiClient
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.notifications.AlertManager
import com.anthropic.balanceapp.widget.ClaudeWidgetReceiver
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dataStore = AppDataStore(appContext)
    private val claudeClient = ClaudeAiApiClient()
    private val balanceClient = AnthropicBalanceClient()
    private val alertManager = AlertManager(appContext)

    override suspend fun doWork(): Result {
        val settings = dataStore.getSettings()

        var anySuccess = false
        var anyRetryNeeded = false

        // ── 1. Fetch Claude.ai usage ──────────────────────────────────────────
        if (settings.claudeSessionToken.isNotBlank()) {
            when (val result = claudeClient.fetchUsage(settings.claudeSessionToken)) {
                is ApiResult.Success -> {
                    val usage = result.data
                    dataStore.saveClaudeUsage(usage)
                    anySuccess = true

                    if (settings.alertsEnabled) {
                        if (usage.sessionPercent >= settings.alertSessionThresholdPercent) {
                            alertManager.sendSessionUsageAlert(usage.sessionPercent)
                        }
                        if (usage.weeklyPercent >= settings.alertWeeklyThresholdPercent) {
                            alertManager.sendWeeklyUsageAlert(usage.weeklyPercent)
                        }
                    }
                }
                is ApiResult.Error -> {
                    dataStore.saveClaudeUsageError(result.message)
                    // Auth errors should not retry
                    if (result.code != 401 && result.code != 403) {
                        anyRetryNeeded = true
                    }
                }
                is ApiResult.NetworkError -> {
                    anyRetryNeeded = true
                }
            }
        }

        // ── 2. Fetch API billing balance ──────────────────────────────────────
        if (settings.anthropicApiKey.isNotBlank()) {
            when (val result = balanceClient.fetchBalance(settings.anthropicApiKey)) {
                is ApiResult.Success -> {
                    val balance = result.data
                    dataStore.saveApiBalance(balance)
                    anySuccess = true

                    if (settings.alertsEnabled) {
                        if (balance.remainingUsd <= settings.alertBalanceThresholdUsd) {
                            alertManager.sendLowBalanceAlert(
                                balance.remainingUsd,
                                settings.alertBalanceThresholdUsd
                            )
                        }
                    }
                }
                is ApiResult.Error -> {
                    dataStore.saveApiBalanceError(result.message)
                    if (result.code != 401 && result.code != 403) {
                        anyRetryNeeded = true
                    }
                }
                is ApiResult.NetworkError -> {
                    anyRetryNeeded = true
                }
            }
        }

        // Update widget regardless of outcome so stale/error state is shown
        ClaudeWidgetReceiver.updateWidgets(applicationContext)

        return when {
            anyRetryNeeded && !anySuccess -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "claude_balance_sync"

        fun schedule(context: Context, intervalMinutes: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = intervalMinutes.toLong(),
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = minOf(intervalMinutes.toLong() / 3, 15L),
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
