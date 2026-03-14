package com.anthropic.balanceapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anthropic.balanceapp.MainActivity
import com.anthropic.balanceapp.R

class AlertManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "claude_balance_alerts"
        const val NOTIFICATION_ID_SESSION = 1001
        const val NOTIFICATION_ID_WEEKLY = 1002
        const val NOTIFICATION_ID_BALANCE = 1003
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun sendSessionUsageAlert(usagePercent: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_usage_title))
            .setContentText("Session usage is at $usagePercent%")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(buildPendingIntent())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SESSION, notification)
        } catch (_: SecurityException) { /* permission not granted */ }
    }

    fun sendWeeklyUsageAlert(usagePercent: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("High Weekly Usage")
            .setContentText("Weekly (all models) usage is at $usagePercent%")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(buildPendingIntent())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_WEEKLY, notification)
        } catch (_: SecurityException) { /* permission not granted */ }
    }

    fun sendLowBalanceAlert(remainingUsd: Double, thresholdUsd: Double) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_balance_title))
            .setContentText(
                "Remaining balance \$${"%.2f".format(remainingUsd)} is below " +
                "alert threshold \$${"%.2f".format(thresholdUsd)}"
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(buildPendingIntent())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BALANCE, notification)
        } catch (_: SecurityException) { /* permission not granted */ }
    }

    // ── Legacy overloads kept for any leftover call-sites ────────────────────

    fun sendUsageAlert(usagePercent: Int, currentCost: Double, budgetLimit: Double) {
        sendSessionUsageAlert(usagePercent)
    }

    fun sendBalanceAlert(currentCost: Double, threshold: Double) {
        sendLowBalanceAlert(currentCost, threshold)
    }
}
