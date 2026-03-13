package com.anthropic.balanceapp.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class ClaudeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClaudeWidget()

    companion object {
        suspend fun updateWidgets(context: Context) {
            ClaudeWidget().updateAll(context)
        }
    }
}
