package com.anthropic.balanceapp.worker

import android.content.Context
import androidx.work.*
import com.anthropic.balanceapp.api.PlatformCreditsClient
import com.anthropic.balanceapp.api.ApiResult
import com.anthropic.balanceapp.api.ClaudeAiApiClient
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.logging.AppLogger
import com.anthropic.balanceapp.notifications.AlertManager
import com.anthropic.balanceapp.widget.ClaudeWidgetReceiver
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dataStore = AppDataStore(appContext)
    private val claudeClient = ClaudeAiApiClient()
    private val balanceClient = PlatformCreditsClient()
    private val alertManager = AlertManager(appContext)

    override suspend fun doWork(): Result {
        AppLogger.d("doWork started (attempt ${runAttemptCount + 1})")
        val settings = dataStore.getSettings()

        var anySuccess = false
        var anyRetryNeeded = false

        // ── 1. Fetch Claude.ai usage ──────────────────────────────────────────
        if (settings.claudeSessionToken.isNotBlank()) {
            AppLogger.d("Fetching Claude.ai usage…")
            when (val result = claudeClient.fetchUsage(settings.claudeSessionToken)) {
                is ApiResult.Success -> {
                    val usage = result.data
                    AppLogger.d("Claude usage OK — session=${usage.sessionPercent}% weekly=${usage.weeklyPercent}%")
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
                    AppLogger.w("Claude usage error (code=${result.code}): ${result.message}")
                    dataStore.saveClaudeUsageError(result.message)
                    // Auth errors should not retry
                    if (result.code != 401 && result.code != 403) {
                        anyRetryNeeded = true
                    }
                }
                is ApiResult.NetworkError -> {
                    AppLogger.w("Claude usage network error")
                    anyRetryNeeded = true
                }
            }
        } else {
            AppLogger.d("No Claude session token configured, skipping usage fetch")
        }

        // ── 2. Fetch prepaid credit balance from platform.claude.com ─────────
        if (settings.claudeSessionToken.isNotBlank()) {
            AppLogger.d("Fetching prepaid credit balance…")
            when (val result = balanceClient.fetchBalance(
                settings.claudeSessionToken,
                settings.platformRoutingHint.takeIf { it.isNotBlank() }
            )) {
                is ApiResult.Success -> {
                    val balance = result.data
                    AppLogger.d("Balance OK — remaining=\$${balance.remainingUsd} pending=\$${balance.pendingUsd}")
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
                    AppLogger.w("Balance error (code=${result.code}): ${result.message}")
                    dataStore.saveApiBalanceError(result.message)
                    // 401/403/404 = permanent failures, no retry
                    if (result.code != 401 && result.code != 403 && result.code != 404) {
                        anyRetryNeeded = true
                    }
                }
                is ApiResult.NetworkError -> {
                    AppLogger.w("Balance network error")
                    anyRetryNeeded = true
                }
            }
        } else {
            AppLogger.d("No session token configured, skipping balance fetch")
        }

        // Update widget regardless of outcome so stale/error state is shown
        ClaudeWidgetReceiver.updateWidgets(applicationContext)

        val result = when {
            anyRetryNeeded && !anySuccess -> Result.retry()
            else -> Result.success()
        }
        AppLogger.d("doWork finished → $result")
        return result
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
