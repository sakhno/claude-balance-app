package com.anthropic.balanceapp.worker

import android.content.Context
import androidx.work.*
import com.anthropic.balanceapp.api.AnthropicApiClient
import com.anthropic.balanceapp.api.ApiResult
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.notifications.AlertManager
import com.anthropic.balanceapp.widget.ClaudeWidgetReceiver
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dataStore = AppDataStore(appContext)
    private val apiClient = AnthropicApiClient()
    private val alertManager = AlertManager(appContext)

    override suspend fun doWork(): Result {
        val settings = dataStore.getSettings()

        if (settings.apiKey.isBlank()) {
            return Result.success() // Nothing to do without API key
        }

        return when (val result = apiClient.fetchMonthlyUsage(settings.apiKey)) {
            is ApiResult.Success -> {
                val usage = result.data
                dataStore.saveCachedUsage(
                    inputTokens = usage.totalInputTokens,
                    outputTokens = usage.totalOutputTokens,
                    costUsd = usage.estimatedCostUsd,
                    periodStart = usage.periodStart,
                    periodEnd = usage.periodEnd
                )

                // Check alert thresholds
                if (settings.alertsEnabled) {
                    val budgetUsagePercent = if (settings.monthlyBudgetUsd > 0) {
                        (usage.estimatedCostUsd / settings.monthlyBudgetUsd * 100).toInt()
                    } else 0

                    if (budgetUsagePercent >= settings.alertUsageThresholdPercent) {
                        alertManager.sendUsageAlert(
                            budgetUsagePercent,
                            usage.estimatedCostUsd,
                            settings.monthlyBudgetUsd
                        )
                    }

                    if (usage.estimatedCostUsd >= settings.alertBalanceThresholdUsd) {
                        alertManager.sendBalanceAlert(
                            usage.estimatedCostUsd,
                            settings.alertBalanceThresholdUsd
                        )
                    }
                }

                // Trigger widget update
                ClaudeWidgetReceiver.updateWidgets(applicationContext)

                Result.success()
            }
            is ApiResult.Error -> {
                dataStore.saveCacheError(result.message)
                ClaudeWidgetReceiver.updateWidgets(applicationContext)
                // Don't retry on auth errors
                if (result.code == 401 || result.code == 403) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
            is ApiResult.NetworkError -> {
                Result.retry()
            }
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
