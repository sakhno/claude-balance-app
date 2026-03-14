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
import com.anthropic.balanceapp.data.AppDataStore
import com.anthropic.balanceapp.data.AppSettings
import com.anthropic.balanceapp.data.CachedUsageData
import com.anthropic.balanceapp.worker.SyncWorker
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

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
            val cached by dataStore.cachedUsageFlow.collectAsState(initial = CachedUsageData())
            val size = LocalSize.current

            WidgetContent(settings = settings, cached = cached, size = size)
        }
    }
}

@Composable
fun WidgetContent(
    settings: AppSettings,
    cached: CachedUsageData,
    size: DpSize
) {
    val budgetPercent = if (settings.monthlyBudgetUsd > 0 && cached.fetchedAtMs > 0) {
        ((cached.estimatedCostUsd / settings.monthlyBudgetUsd) * 100)
            .roundToInt()
            .coerceIn(0, 100)
    } else 0

    val daysUntilReset = run {
        val today = LocalDate.now()
        val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())
        ChronoUnit.DAYS.between(today, endOfMonth).toInt() + 1
    }

    val isSmall = size.width < 200.dp
    val isLarge = size.width >= 280.dp && size.height >= 140.dp
    val isExtraLarge = size.width >= 350.dp && size.height >= 200.dp

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
                isSmall -> SmallWidgetLayout(
                    budgetPercent = budgetPercent,
                    costUsd = cached.estimatedCostUsd,
                    daysUntilReset = daysUntilReset,
                    hasData = cached.fetchedAtMs > 0
                )
                isExtraLarge -> ExtraLargeWidgetLayout(
                    settings = settings,
                    cached = cached,
                    budgetPercent = budgetPercent,
                    daysUntilReset = daysUntilReset
                )
                isLarge -> LargeWidgetLayout(
                    settings = settings,
                    cached = cached,
                    budgetPercent = budgetPercent,
                    daysUntilReset = daysUntilReset
                )
                else -> MediumWidgetLayout(
                    budgetPercent = budgetPercent,
                    costUsd = cached.estimatedCostUsd,
                    budget = settings.monthlyBudgetUsd,
                    daysUntilReset = daysUntilReset,
                    hasData = cached.fetchedAtMs > 0,
                    lastError = cached.lastError
                )
            }
        }
    }
}

@Composable
fun SmallWidgetLayout(budgetPercent: Int, costUsd: Double, daysUntilReset: Int, hasData: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasData) "$budgetPercent%" else "--",
            style = TextStyle(
                color = progressColorProvider(budgetPercent),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = if (hasData) "\$${"%.2f".format(costUsd)}" else "No data",
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 13.sp
            )
        )
        if (hasData) {
            Text(
                text = "${daysUntilReset}d left",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
    }
}

@Composable
fun MediumWidgetLayout(
    budgetPercent: Int,
    costUsd: Double,
    budget: Double,
    daysUntilReset: Int,
    hasData: Boolean,
    lastError: String
) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "Anthropic API",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = when {
                        hasData -> "$budgetPercent% used"
                        lastError.isNotEmpty() -> "Sync error"
                        else -> "Loading..."
                    },
                    style = TextStyle(
                        color = if (hasData) progressColorProvider(budgetPercent) else GlanceTheme.colors.secondary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (hasData) "\$${"%.2f".format(costUsd)}" else "--",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "of \$${"%.0f".format(budget)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.secondary,
                        fontSize = 13.sp
                    )
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        WidgetProgressBar(percent = budgetPercent)
        Spacer(modifier = GlanceModifier.height(5.dp))
        Text(
            text = "Resets in $daysUntilReset days · incl. Claude Code",
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
fun LargeWidgetLayout(
    settings: AppSettings,
    cached: CachedUsageData,
    budgetPercent: Int,
    daysUntilReset: Int
) {
    val hasData = cached.fetchedAtMs > 0
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Anthropic API (+ Claude Code)",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasData) formatRelativeTime(cached.fetchedAtMs) else "",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = if (hasData) "$budgetPercent%" else "--",
            style = TextStyle(
                color = progressColorProvider(budgetPercent),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "of monthly budget used",
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 12.sp
            )
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        WidgetProgressBar(percent = budgetPercent)
        Spacer(modifier = GlanceModifier.height(10.dp))

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            WidgetStatCell(
                label = "Spent",
                value = if (hasData) "\$${"%.2f".format(cached.estimatedCostUsd)}" else "--",
                modifier = GlanceModifier.defaultWeight()
            )
            WidgetStatCell(
                label = "Budget",
                value = "\$${"%.0f".format(settings.monthlyBudgetUsd)}",
                modifier = GlanceModifier.defaultWeight()
            )
            WidgetStatCell(
                label = "Resets",
                value = "${daysUntilReset}d",
                modifier = GlanceModifier.defaultWeight()
            )
        }
    }
}

@Composable
fun ExtraLargeWidgetLayout(
    settings: AppSettings,
    cached: CachedUsageData,
    budgetPercent: Int,
    daysUntilReset: Int
) {
    val hasData = cached.fetchedAtMs > 0
    val totalTokensM = if (hasData) {
        (cached.totalInputTokens + cached.totalOutputTokens) / 1_000_000.0
    } else 0.0

    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Anthropic API · incl. Claude Code",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasData) formatRelativeTime(cached.fetchedAtMs) else "No data",
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(10.dp))

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = if (hasData) "$budgetPercent%" else "--",
                    style = TextStyle(
                        color = progressColorProvider(budgetPercent),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "budget used",
                    style = TextStyle(
                        color = GlanceTheme.colors.secondary,
                        fontSize = 13.sp
                    )
                )
            }
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = if (hasData) "\$${"%.2f".format(cached.estimatedCostUsd)}" else "--",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "of \$${"%.0f".format(settings.monthlyBudgetUsd)} budget",
                    style = TextStyle(
                        color = GlanceTheme.colors.secondary,
                        fontSize = 13.sp
                    )
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(10.dp))
        WidgetProgressBar(percent = budgetPercent)
        Spacer(modifier = GlanceModifier.height(12.dp))

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            WidgetStatCell(
                label = "Input",
                value = if (hasData) formatWidgetTokens(cached.totalInputTokens) else "--",
                modifier = GlanceModifier.defaultWeight()
            )
            WidgetStatCell(
                label = "Output",
                value = if (hasData) formatWidgetTokens(cached.totalOutputTokens) else "--",
                modifier = GlanceModifier.defaultWeight()
            )
            WidgetStatCell(
                label = "Total",
                value = if (hasData) "${"%.2f".format(totalTokensM)}M" else "--",
                modifier = GlanceModifier.defaultWeight()
            )
            WidgetStatCell(
                label = "Resets",
                value = "${daysUntilReset}d",
                modifier = GlanceModifier.defaultWeight()
            )
        }

        if (cached.lastError.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Text(
                text = "⚠ ${cached.lastError.take(80)}",
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE57373)),
                    fontSize = 11.sp
                )
            )
        }
    }
}

@Composable
fun WidgetProgressBar(percent: Int) {
    val barColor = progressColorProvider(percent)
    val clampedPercent = percent.coerceIn(0, 100)
    val filledWeight = clampedPercent.coerceAtLeast(1)
    val emptyWeight = (100 - clampedPercent).coerceAtLeast(1)

    Row(
        modifier = GlanceModifier
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

@Composable
fun WidgetStatCell(label: String, value: String, modifier: GlanceModifier = GlanceModifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.secondary,
                fontSize = 11.sp
            )
        )
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

fun progressColorProvider(percent: Int): ColorProvider = when {
    percent >= 90 -> ColorProvider(Color(0xFFE57373))  // red
    percent >= 70 -> ColorProvider(Color(0xFFFFB74D))  // amber
    else -> ColorProvider(Color(0xFF81C784))            // green
}

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

fun formatWidgetTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${"%.1f".format(tokens / 1_000.0)}K"
    else -> tokens.toString()
}

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
