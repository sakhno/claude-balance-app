package com.anthropic.balanceapp.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
                .padding(if (isSmall) 10.dp else 12.dp)
        ) {
            when {
                isSmall -> SmallWidgetLayout(usage = usage, balance = balance)
                isExtraLarge -> ExtraLargeWidgetLayout(usage = usage, balance = balance)
                isLarge -> LargeWidgetLayout(usage = usage, balance = balance)
                else -> MediumWidgetLayout(usage = usage, balance = balance)
            }
        }
    }
}

// ─── Circle progress indicator ────────────────────────────────────────────────

fun createCircleBitmap(percent: Int, sizePx: Int, colorArgb: Int, hasData: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = sizePx * 0.13f
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val radius = cx - stroke / 2f

    // Track
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = stroke
    paint.strokeCap = Paint.Cap.ROUND
    paint.color = 0x33888888.toInt()
    canvas.drawCircle(cx, cy, radius, paint)

    // Arc fill
    if (hasData && percent > 0) {
        paint.color = colorArgb
        val inset = stroke / 2f
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)
        canvas.drawArc(oval, -90f, 360f * percent / 100f, false, paint)
    }

    // Center text
    paint.style = Paint.Style.FILL
    paint.color = if (hasData) colorArgb else 0xFF888888.toInt()
    paint.textSize = sizePx * 0.26f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    val text = if (hasData) "$percent%" else "--"
    val textY = cy - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, cx, textY, paint)

    return bmp
}

@Composable
fun UsageCircle(percent: Int, hasData: Boolean, sizeDp: Dp) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val sizePx = (sizeDp.value * density).toInt().coerceAtLeast(40)

    val colorArgb = when {
        !hasData -> 0xFF888888.toInt()
        percent >= 90 -> 0xFFE57373.toInt()
        percent >= 70 -> 0xFFFFB74D.toInt()
        else -> 0xFF81C784.toInt()
    }

    val bitmap = createCircleBitmap(percent.coerceIn(0, 100), sizePx, colorArgb, hasData)

    Image(
        provider = ImageProvider(bitmap),
        contentDescription = if (hasData) "$percent%" else "No data",
        modifier = GlanceModifier.size(sizeDp)
    )
}

// ─── Small (2×1) ──────────────────────────────────────────────────────────────

@Composable
fun SmallWidgetLayout(usage: ClaudeUsageData, balance: ApiBalance) {
    val hasUsage = usage.fetchedAtMs > 0 && usage.lastError.isEmpty()
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Session circle
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            UsageCircle(if (hasUsage) usage.sessionPercent else 0, hasUsage, 38.dp)
            Text(
                text = "Session",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 9.sp)
            )
        }

        // Weekly circle
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            UsageCircle(if (hasUsage) usage.weeklyPercent else 0, hasUsage, 38.dp)
            Text(
                text = "Weekly",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 9.sp)
            )
        }

        // Balance
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                style = TextStyle(
                    color = balanceColorProvider(balance.remainingUsd, hasBalance),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "balance",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 9.sp)
            )
        }
    }
}

// ─── Medium (3×2) ─────────────────────────────────────────────────────────────

@Composable
fun MediumWidgetLayout(usage: ClaudeUsageData, balance: ApiBalance) {
    val hasUsage = usage.fetchedAtMs > 0 && usage.lastError.isEmpty()
    val hasBalance = balance.fetchedAtMs > 0 && balance.lastError.isEmpty()

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Session circle + label
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            UsageCircle(if (hasUsage) usage.sessionPercent else 0, hasUsage, 52.dp)
            Spacer(modifier = GlanceModifier.height(3.dp))
            Text(
                text = "Session",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
            )
        }

        // Weekly circle + label
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            UsageCircle(if (hasUsage) usage.weeklyPercent else 0, hasUsage, 52.dp)
            Spacer(modifier = GlanceModifier.height(3.dp))
            Text(
                text = "Weekly",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
            )
        }

        // Balance
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                style = TextStyle(
                    color = balanceColorProvider(balance.remainingUsd, hasBalance),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "remaining",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
            )
            if (hasBalance && balance.pendingUsd > 0) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = "\$${"%.2f".format(balance.pendingUsd)} pending",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 10.sp)
                )
            }
        }
    }
}

// ─── Large (4×2) ──────────────────────────────────────────────────────────────

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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (updatedAtMs > 0) formatRelativeTime(updatedAtMs) else "",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 12.sp)
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))

        // Circles row
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UsageCircle(if (hasUsage) usage.sessionPercent else 0, hasUsage, 60.dp)
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    text = "Session",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 12.sp)
                )
                if (usage.sessionResetAtMs > 0) {
                    Text(
                        text = formatResetTime(usage.sessionResetAtMs),
                        style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 10.sp)
                    )
                }
            }
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UsageCircle(if (hasUsage) usage.weeklyPercent else 0, hasUsage, 60.dp)
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    text = "Weekly",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 12.sp)
                )
                if (usage.weeklyResetAtMs > 0) {
                    Text(
                        text = formatResetTime(usage.weeklyResetAtMs),
                        style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 10.sp)
                    )
                }
            }
            // Balance column
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Balance",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 12.sp)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                    style = TextStyle(
                        color = balanceColorProvider(balance.remainingUsd, hasBalance),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "remaining",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
                )
                if (hasBalance) {
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = "\$${"%.2f".format(balance.pendingUsd)} pending",
                        style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 10.sp)
                    )
                }
            }
        }
    }
}

// ─── Extra Large (4×3) ────────────────────────────────────────────────────────

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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (updatedAtMs > 0) formatRelativeTime(updatedAtMs) else "",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 12.sp)
            )
        }
        Spacer(modifier = GlanceModifier.height(12.dp))

        // Circles row
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UsageCircle(if (hasUsage) usage.sessionPercent else 0, hasUsage, 80.dp)
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = "Session",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 13.sp)
                )
                if (usage.sessionResetAtMs > 0) {
                    Text(
                        text = formatResetTime(usage.sessionResetAtMs),
                        style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
                    )
                }
            }
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UsageCircle(if (hasUsage) usage.weeklyPercent else 0, hasUsage, 80.dp)
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = "Weekly",
                    style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 13.sp)
                )
                if (usage.weeklyResetAtMs > 0) {
                    Text(
                        text = formatResetTime(usage.weeklyResetAtMs),
                        style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 11.sp)
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.height(14.dp))

        // Balance section
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Remaining Balance",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 14.sp),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.remainingUsd)}" else "--",
                style = TextStyle(
                    color = balanceColorProvider(balance.remainingUsd, hasBalance),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pending this period",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 14.sp),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = if (hasBalance) "\$${"%.2f".format(balance.pendingUsd)}" else "--",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 20.sp,
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

// ─── Colour helpers ───────────────────────────────────────────────────────────

fun progressColorProvider(percent: Int): ColorProvider = when {
    percent >= 90 -> ColorProvider(Color(0xFFE57373))
    percent >= 70 -> ColorProvider(Color(0xFFFFB74D))
    else -> ColorProvider(Color(0xFF81C784))
}

fun balanceColorProvider(remainingUsd: Double, hasData: Boolean): ColorProvider = when {
    !hasData -> ColorProvider(Color(0xFF888888))
    remainingUsd < 1.0 -> ColorProvider(Color(0xFFE57373))
    remainingUsd < 5.0 -> ColorProvider(Color(0xFFFFB74D))
    else -> ColorProvider(Color(0xFF81C784))
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
