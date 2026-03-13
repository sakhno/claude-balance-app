package com.anthropic.balanceapp

import android.app.Application
import androidx.work.Configuration
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BalanceApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleInitialSync()
    }

    private fun scheduleInitialSync() {
        applicationScope.launch {
            val dataStore = AppDataStore(this@BalanceApplication)
            val settings = dataStore.settingsFlow.first()
            if (settings.apiKey.isNotBlank()) {
                SyncWorker.schedule(this@BalanceApplication, settings.updateIntervalMinutes)
            }
        }
    }
}
