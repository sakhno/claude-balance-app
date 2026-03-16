package com.anthropic.balanceapp.ui.settings

import android.app.Activity.RESULT_OK
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anthropic.balanceapp.api.formatResetTime
import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import com.anthropic.balanceapp.data.WidgetTheme
import com.anthropic.balanceapp.ui.login.LoginWebViewActivity
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            delay(2000)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Summary cards (live data) ─────────────────────────────────────
            if (settings.claudeSessionToken.isNotBlank()) {
                UsageSummaryCard(usage = uiState.claudeUsage)
            }
            if (settings.anthropicApiKey.isNotBlank()) {
                BalanceSummaryCard(balance = uiState.apiBalance)
            }

            // ── Section 1: Claude.ai Usage Tracking ──────────────────────────
            SectionCard(title = "Claude.ai Usage Tracking") {
                val context = LocalContext.current
                val loginLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        val token = result.data?.getStringExtra(LoginWebViewActivity.RESULT_SESSION_TOKEN)
                        if (!token.isNullOrBlank()) {
                            viewModel.updateClaudeSessionToken(token)
                            viewModel.validateSessionToken() // also saves on success
                        }
                    }
                }

                Button(
                    onClick = {
                        loginLauncher.launch(
                            android.content.Intent(context, LoginWebViewActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login with Claude.ai")
                }

                if (settings.claudeSessionToken.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ Session token saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Manual override
                var showManual by remember { mutableStateOf(false) }
                TextButton(onClick = { showManual = !showManual }) {
                    Text(if (showManual) "Hide manual entry" else "Enter token manually")
                }

                AnimatedVisibility(visible = showManual) {
                    Column {
                        var showToken by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = settings.claudeSessionToken,
                            onValueChange = { viewModel.updateClaudeSessionToken(it) },
                            label = { Text("Session Token") },
                            placeholder = { Text("sk-ant-sid01-...") },
                            visualTransformation = if (showToken) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showToken) "Hide" else "Show"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.validateSessionToken() },
                    enabled = settings.claudeSessionToken.isNotBlank() &&
                              !uiState.isValidatingSessionToken,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isValidatingSessionToken) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Test connection")
                    }
                }

                AnimatedVisibility(visible = uiState.sessionTokenValidationResult != null) {
                    uiState.sessionTokenValidationResult?.let { result ->
                        val isSuccess = result.startsWith("Session token is valid")
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ── Section 2: API Balance Tracking ──────────────────────────────
            SectionCard(title = "API Balance Tracking") {
                Text(
                    text = "Requires an Admin API key with billing access (console.anthropic.com)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = settings.anthropicApiKey,
                    onValueChange = { viewModel.updateAnthropicApiKey(it) },
                    label = { Text("Admin API Key") },
                    placeholder = { Text("sk-ant-api03-...") },
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.validateApiKey() },
                    enabled = settings.anthropicApiKey.isNotBlank() && !uiState.isValidatingApiKey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isValidatingApiKey) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Test connection")
                    }
                }

                AnimatedVisibility(visible = uiState.apiKeyValidationResult != null) {
                    uiState.apiKeyValidationResult?.let { result ->
                        val isSuccess = result.startsWith("API key is valid")
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ── Section 3: Widget Settings ────────────────────────────────────
            SectionCard(title = "Widget Settings") {
                Text(
                    text = "Update Interval",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val intervals = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 360 to "6 hours")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    intervals.forEach { (minutes, label) ->
                        FilterChip(
                            selected = settings.updateIntervalMinutes == minutes,
                            onClick = { viewModel.updateIntervalMinutes(minutes) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Widget Transparency: ${settings.widgetTransparencyPercent}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = settings.widgetTransparencyPercent.toFloat(),
                    onValueChange = { viewModel.updateWidgetTransparency(it.roundToInt()) },
                    valueRange = 0f..80f
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Widget Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WidgetTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.widgetTheme == theme,
                            onClick = { viewModel.updateWidgetTheme(theme) },
                            label = {
                                Text(
                                    text = theme.value.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Section 4: Alerts ─────────────────────────────────────────────
            SectionCard(title = "Alerts") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Alerts",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = settings.alertsEnabled,
                        onCheckedChange = { viewModel.updateAlertsEnabled(it) }
                    )
                }

                AnimatedVisibility(visible = settings.alertsEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Session usage alert
                        Text(
                            text = "Session usage alert: ${settings.alertSessionThresholdPercent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.alertSessionThresholdPercent.toFloat(),
                            onValueChange = { viewModel.updateSessionAlertThreshold(it.roundToInt()) },
                            valueRange = 50f..100f,
                            steps = 9
                        )

                        // Weekly usage alert
                        Text(
                            text = "Weekly usage alert: ${settings.alertWeeklyThresholdPercent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.alertWeeklyThresholdPercent.toFloat(),
                            onValueChange = { viewModel.updateWeeklyAlertThreshold(it.roundToInt()) },
                            valueRange = 50f..100f,
                            steps = 9
                        )

                        // Balance alert
                        var balanceAlertText by remember(settings.alertBalanceThresholdUsd) {
                            mutableStateOf("%.2f".format(settings.alertBalanceThresholdUsd))
                        }
                        OutlinedTextField(
                            value = balanceAlertText,
                            onValueChange = { text ->
                                balanceAlertText = text
                                text.toDoubleOrNull()?.let { viewModel.updateBalanceAlert(it) }
                            },
                            label = { Text("Alert when balance falls below (USD)") },
                            prefix = { Text("$") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // ── Save button ───────────────────────────────────────────────────
            Button(
                onClick = { viewModel.saveSettings() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else if (uiState.saveSuccess) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saved!")
                } else {
                    Text("Save Settings")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ─── Summary cards ────────────────────────────────────────────────────────────

@Composable
fun UsageSummaryCard(usage: ClaudeUsageData) {
    if (usage.fetchedAtMs == 0L && usage.lastError.isEmpty()) return

    if (usage.lastError.isNotEmpty()) {
        ErrorCard(title = "Claude.ai Sync Error", error = usage.lastError)
        return
    }

    if (usage.dataUnavailable) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Usage percentages are not available via the API for personal Claude Pro accounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    val lastUpdated = remember(usage.fetchedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(usage.fetchedAtMs))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Claude.ai Usage",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Updated $lastUpdated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Session
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${usage.sessionPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = usageColor(usage.sessionPercent)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { usage.sessionPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = usageColor(usage.sessionPercent)
            )
            if (usage.sessionResetAtMs > 0) {
                Text(
                    text = formatResetTime(usage.sessionResetAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Weekly
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Weekly (All Models)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${usage.weeklyPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = usageColor(usage.weeklyPercent)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { usage.weeklyPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = usageColor(usage.weeklyPercent)
            )
            if (usage.weeklyResetAtMs > 0) {
                Text(
                    text = formatResetTime(usage.weeklyResetAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun BalanceSummaryCard(balance: ApiBalance) {
    if (balance.fetchedAtMs == 0L && balance.lastError.isEmpty()) return

    if (balance.lastError.isNotEmpty()) {
        ErrorCard(title = "Balance Sync Error", error = balance.lastError)
        return
    }

    val lastUpdated = remember(balance.fetchedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(balance.fetchedAtMs))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "API Balance",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Updated $lastUpdated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remaining Balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "\$${"%.2f".format(balance.remainingUsd)}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = when {
                            balance.remainingUsd < 1.0 -> MaterialTheme.colorScheme.error
                            balance.remainingUsd < 5.0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pending this period",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "\$${"%.2f".format(balance.pendingUsd)}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorCard(title: String, error: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

// ─── Colour helpers ───────────────────────────────────────────────────────────

@Composable
private fun usageColor(percent: Int) = when {
    percent >= 90 -> MaterialTheme.colorScheme.error
    percent >= 70 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
