package com.anthropic.balanceapp.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.unit.ColorProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import com.anthropic.balanceapp.api.formatResetTime
import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.data.AppSettings
import com.anthropic.balanceapp.worker.SyncWorker

class ClaudeWidget : GlanceAppWidget() {

    companion object {
        val SMALL = DpSize(140.dp, 60.dp)
        val MEDIUM = DpSize(220.dp, 100.dp)
        val LARGE = DpSize(280.dp, 140.dp)
        val EXTRA_LARGE = DpSize(350.dp, 200.dp)
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, MEDIUM, LARGE, EXTRA_LARGE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dataStore = AppDataStore(context)

        provideContent {
            val settings by dataStore.settingsFlow.collectAsState(initial = AppSettings())
            val usage by dataStore.claudeUsageFlow.collectAsState(initial = ClaudeUsageData())
            val balance by dataStore.apiBalanceFlow.collectAsState(initial = ApiBalance())
            val size = LocalSize.current

            WidgetContent(settings = settings, usage = usage, balance = balance, size = size)
        }
    }
}

@Composable
fun WidgetContent(
    settings: AppSettings,
    usage: ClaudeUsageData,
    balance: ApiBalance,
    size: DpSize
) {
    val isSmall = size.width < 200.dp
    val isExtraLarge = size.width >= 350.dp && size.height >= 200.dp
    val isLarge = size.width >= 280.dp && size.height >= 140.dp

    GlanceTheme {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<OpenAppAction>())
                .padding(if (isSmall) 10.dp else 14.dp)
        ) {
            when {
                isSmall -> SmallWidgetLayout(balance = balance)
                isExtraLarge -> ExtraLargeWidgetLayout(usage = usage, balance = balance)
                isLarge -> LargeWidgetLayout(usage = usage, balance = balance)
                else -> MediumWidgetLayout(usage = usage, balance = balance)
            }
        }
    }
}

// ─── Small (2×1): just the remaining balance ─────────────────────────────────

@Composable
fun SmallWidgetLayout(balance: ApiBalance) {
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
            style = TextStyle(
                color = balanceColorProvider(balance.remainingUsd, hasBalance),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "remaining",
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 12.sp
            )
        )
    }
}

// ─── Medium (3×2) ─────────────────────────────────────────────────────────────

@Composable
fun MediumWidgetLayout(usage: ClaudeUsageData, balance: ApiBalance) {
    val hasUsage = usage.fetchedAtMs > 0 && usage.lastError.isEmpty()
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()

    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Session row
        UsageRow(
            label = "Session",
            percent = if (hasUsage) usage.sessionPercent else null,
            resetAtMs = usage.sessionResetAtMs
        )
        Spacer(modifier = GlanceModifier.height(4.dp))

        // Weekly row
        UsageRow(
            label = "Weekly ",
            percent = if (hasUsage) usage.weeklyPercent else null,
            resetAtMs = usage.weeklyResetAtMs
        )
        Spacer(modifier = GlanceModifier.height(6.dp))

        // Balance row
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Balance",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 13.sp
                ),
                modifier = GlanceModifier.width(52.dp)
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)} remaining" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.pendingUsd)} pending" else "",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
    }
}

// ─── Large (4×2) ─────────────────────────────────────────────────────────────

@Composable
fun LargeWidgetLayout(usage: ClaudeUsageData, balance: ApiBalance) {
    val hasUsage = usage.fetchedAtMs > 0 && usage.lastError.isEmpty()
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()
    val updatedAtMs = maxOf(usage.fetchedAtMs, balance.fetchedAtMs)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Header
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Claude Usage",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (updatedAtMs > 0) formatRelativeTime(updatedAtMs) else "",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))

        // Session row with inline bar
        LargeUsageRow(
            label = "Session",
            percent = if (hasUsage) usage.sessionPercent else null,
            resetAtMs = usage.sessionResetAtMs
        )
        Spacer(modifier = GlanceModifier.height(6.dp))

        // Weekly row with inline bar
        LargeUsageRow(
            label = "Weekly ",
            percent = if (hasUsage) usage.weeklyPercent else null,
            resetAtMs = usage.weeklyResetAtMs
        )
        Spacer(modifier = GlanceModifier.height(10.dp))

        // Balance section
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Remaining Balance",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 13.sp
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pending this period",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 13.sp
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.pendingUsd)}" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

// ─── Extra Large (4×3): same as Large but more spacing & bigger text ──────────

@Composable
fun ExtraLargeWidgetLayout(usage: ClaudeUsageData, balance: ApiBalance) {
    val hasUsage = usage.fetchedAtMs > 0 && usage.lastError.isEmpty()
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()
    val updatedAtMs = maxOf(usage.fetchedAtMs, balance.fetchedAtMs)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Header
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Claude Usage",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (updatedAtMs > 0) formatRelativeTime(updatedAtMs) else "",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(12.dp))

        // Session row
        LargeUsageRow(
            label = "Session",
            percent = if (hasUsage) usage.sessionPercent else null,
            resetAtMs = usage.sessionResetAtMs,
            bigText = true
        )
        Spacer(modifier = GlanceModifier.height(10.dp))

        // Weekly row
        LargeUsageRow(
            label = "Weekly ",
            percent = if (hasUsage) usage.weeklyPercent else null,
            resetAtMs = usage.weeklyResetAtMs,
            bigText = true
        )
        Spacer(modifier = GlanceModifier.height(16.dp))

        // Balance section
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Remaining Balance",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 14.sp
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pending this period",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 14.sp
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.pendingUsd)}" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        // Error hints
        val combinedError = listOfNotNull(
            usage.lastError.takeIf { it.isNotEmpty() },
            balance.lastError.takeIf { it.isNotEmpty() }
        ).joinToString(" | ")
        if (combinedError.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = combinedError.take(100),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE57373)),
                    fontSize = 11.sp
                )
            )
        }
    }
}

// ─── Shared composable rows ───────────────────────────────────────────────────

/**
 * Compact row for the medium widget:
 * "Session  11%  ████░░░░░░  Resets in 3h 51m"
 */
@Composable
fun UsageRow(label: String, percent: Int?, resetAtMs: Long) {
    val pct = percent ?: 0
    val displayPct = if (percent != null) "$pct%" else "--"
    val resetText = if (resetAtMs > 0) formatResetTime(resetAtMs) else ""

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 13.sp
            ),
            modifier = GlanceModifier.width(52.dp)
        )
        Text(
            text = displayPct,
            style = TextStyle(
                color = if (percent != null) progressColorProvider(pct)
                else GlanceTheme.colors.secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.width(30.dp)
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        CompactProgressBar(
            percent = pct,
            modifier = GlanceModifier.defaultWeight().height(6.dp)
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = resetText,
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 11.sp
            )
        )
    }
}

/**
 * Row for the large widget with a full-width bar underneath:
 * "Session  ████░░░░░░░░░░░░  11%    Resets in 3 hr 51 min"
 */
@Composable
fun LargeUsageRow(
    label: String,
    percent: Int?,
    resetAtMs: Long,
    bigText: Boolean = false
) {
    val pct = percent ?: 0
    val displayPct = if (percent != null) "$pct%" else "--"
    val resetText = if (resetAtMs > 0) formatResetTime(resetAtMs) else ""
    val labelSize = if (bigText) 14.sp else 13.sp
    val valueSize = if (bigText) 16.sp else 13.sp

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = labelSize
                ),
                modifier = GlanceModifier.width(56.dp)
            )
            CompactProgressBar(
                percent = pct,
                modifier = GlanceModifier.defaultWeight().height(if (bigText) 10.dp else 8.dp)
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = displayPct,
                style = TextStyle(
                    color = if (percent != null) progressColorProvider(pct)
                    else GlanceTheme.colors.secondary,
                    fontSize = valueSize,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.width(34.dp)
            )
            Text(
                text = resetText,
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = if (bigText) 13.sp else 11.sp
                )
            )
        }
    }
}

// ─── Progress bar ─────────────────────────────────────────────────────────────

/**
 * A two-segment progress bar that fits inside any externally-sized container.
 * Uses integer dp widths (filled + empty = 100 dp) which scale proportionally
 * when the parent Row stretches the child to fill available space.
 */
@Composable
fun CompactProgressBar(percent: Int, modifier: GlanceModifier = GlanceModifier) {
    val barColor = progressColorProvider(percent)
    val clampedPercent = percent.coerceIn(0, 100)
    val filledWeight = clampedPercent.coerceAtLeast(1)
    val emptyWeight = (100 - clampedPercent).coerceAtLeast(1)

    Row(modifier = modifier) {
        Box(
            modifier = GlanceModifier
                .width(filledWeight.dp)
                .fillMaxHeight()
                .background(barColor)
                .cornerRadius(4.dp)
        ) {}
        Box(
            modifier = GlanceModifier
                .width(emptyWeight.dp)
                .fillMaxHeight()
                .background(ColorProvider(Color(0x22888888)))
                .cornerRadius(4.dp)
        ) {}
    }
}

/**
 * Two-segment horizontal bar. Glance's Row weight system renders each box
 * proportionally to its weight value (both segments sum to 100 units).
 */
@Composable
fun WidgetProgressBar(percent: Int, modifier: GlanceModifier = GlanceModifier) {
    val barColor = progressColorProvider(percent)
    val clampedPercent = percent.coerceIn(0, 100)
    val filledWeight = clampedPercent.coerceAtLeast(1)
    val emptyWeight = (100 - clampedPercent).coerceAtLeast(1)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
    ) {
        Box(
            modifier = GlanceModifier
                .width(filledWeight.dp)
                .fillMaxHeight()
                .background(barColor)
                .cornerRadius(4.dp)
        ) {}
        Box(
            modifier = GlanceModifier
                .width(emptyWeight.dp)
                .fillMaxHeight()
                .background(ColorProvider(Color(0x22888888)))
                .cornerRadius(4.dp)
        ) {}
    }
}

// ─── Colour helpers ───────────────────────────────────────────────────────────

fun progressColorProvider(percent: Int): ColorProvider = when {
    percent >= 90 -> ColorProvider(Color(0xFFE57373))  // red
    percent >= 70 -> ColorProvider(Color(0xFFFFB74D))  // amber
    else -> ColorProvider(Color(0xFF81C784))            // green
}

fun balanceColorProvider(remainingUsd: Double, hasData: Boolean): ColorProvider = when {
    !hasData -> ColorProvider(Color(0xFF888888))
    remainingUsd < 1.0 -> ColorProvider(Color(0xFFE57373))   // red – nearly empty
    remainingUsd < 5.0 -> ColorProvider(Color(0xFFFFB74D))   // amber
    else -> ColorProvider(Color(0xFF81C784))                  // green
}

// ─── Time helpers ─────────────────────────────────────────────────────────────

fun formatRelativeTime(timestampMs: Long): String {
    val diffMs = System.currentTimeMillis() - timestampMs
    val diffMin = diffMs / 60_000
    return when {
        diffMin < 1 -> "just now"
        diffMin < 60 -> "${diffMin}m ago"
        diffMin < 1440 -> "${diffMin / 60}h ago"
        else -> "${diffMin / 1440}d ago"
    }
}

// ─── Action callbacks ─────────────────────────────────────────────────────────

class OpenAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, com.anthropic.balanceapp.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        SyncWorker.runNow(context)
    }
}
