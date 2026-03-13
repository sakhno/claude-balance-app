package com.anthropic.balanceapp.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anthropic.balanceapp.data.WidgetTheme
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
    val cached = uiState.cachedUsage

    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            delay(2000)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Balance") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = settings.apiKey.isNotBlank() && !uiState.isSyncing
                    ) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                        }
                    }
                }
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
            // Usage summary card
            if (cached.fetchedAtMs > 0) {
                UsageSummaryCard(uiState = uiState)
            } else if (cached.lastError.isNotEmpty()) {
                ErrorCard(error = cached.lastError)
            }

            // API Key section
            SectionCard(title = "API Configuration") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { viewModel.updateApiKey(it) },
                    label = { Text("Anthropic API Key") },
                    placeholder = { Text("sk-ant-api03-...") },
                    visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide" else "Show"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.validateApiKey() },
                        enabled = settings.apiKey.isNotBlank() && !uiState.isValidatingKey,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isValidatingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Validate Key")
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.keyValidationResult != null) {
                    uiState.keyValidationResult?.let { result ->
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

            // Sync settings
            SectionCard(title = "Sync Settings") {
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
            }

            // Budget settings
            SectionCard(title = "Budget & Alerts") {
                var budgetText by remember(settings.monthlyBudgetUsd) {
                    mutableStateOf(settings.monthlyBudgetUsd.let {
                        if (it == it.toLong().toDouble()) it.toLong().toString()
                        else "%.2f".format(it)
                    })
                }

                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { text ->
                        budgetText = text
                        text.toDoubleOrNull()?.let { viewModel.updateBudget(it) }
                    },
                    label = { Text("Monthly Budget (USD)") },
                    prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

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

                        Text(
                            text = "Alert when budget usage exceeds: ${settings.alertUsageThresholdPercent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.alertUsageThresholdPercent.toFloat(),
                            onValueChange = { viewModel.updateUsageAlertThreshold(it.roundToInt()) },
                            valueRange = 50f..100f,
                            steps = 9
                        )

                        var balanceAlertText by remember(settings.alertBalanceThresholdUsd) {
                            mutableStateOf("%.2f".format(settings.alertBalanceThresholdUsd))
                        }
                        OutlinedTextField(
                            value = balanceAlertText,
                            onValueChange = { text ->
                                balanceAlertText = text
                                text.toDoubleOrNull()?.let { viewModel.updateBalanceAlert(it) }
                            },
                            label = { Text("Alert when spend exceeds (USD)") },
                            prefix = { Text("$") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Widget appearance
            SectionCard(title = "Widget Appearance") {
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

            // Save button
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

@Composable
fun UsageSummaryCard(uiState: SettingsUiState) {
    val cached = uiState.cachedUsage
    val settings = uiState.settings
    val budgetPercent = if (settings.monthlyBudgetUsd > 0) {
        ((cached.estimatedCostUsd / settings.monthlyBudgetUsd) * 100)
            .roundToInt()
            .coerceIn(0, 100)
    } else 0

    val lastUpdated = remember(cached.fetchedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(cached.fetchedAtMs))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "This Month's Usage",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Updated $lastUpdated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "\$${"%.2f".format(cached.estimatedCostUsd)}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = when {
                            budgetPercent >= 90 -> MaterialTheme.colorScheme.error
                            budgetPercent >= 70 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        text = "of \$${"%.0f".format(settings.monthlyBudgetUsd)} budget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$budgetPercent%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { budgetPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = when {
                    budgetPercent >= 90 -> MaterialTheme.colorScheme.error
                    budgetPercent >= 70 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                UsageStat(
                    label = "Input tokens",
                    value = formatTokensM(cached.totalInputTokens),
                    modifier = Modifier.weight(1f)
                )
                UsageStat(
                    label = "Output tokens",
                    value = formatTokensM(cached.totalOutputTokens),
                    modifier = Modifier.weight(1f)
                )
                UsageStat(
                    label = "Period",
                    value = if (cached.periodStart.isNotEmpty())
                        "${cached.periodStart.takeLast(5)} – ${cached.periodEnd.takeLast(5)}"
                    else "--",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun UsageStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ErrorCard(error: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Sync Error",
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

private fun formatTokensM(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.2f".format(tokens / 1_000_000.0)}M"
    tokens >= 1_000 -> "${"%.1f".format(tokens / 1_000.0)}K"
    else -> tokens.toString()
}
