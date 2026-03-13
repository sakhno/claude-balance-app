package com.anthropic.balanceapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val AnthropicOrange = Color(0xFFD97757)
private val AnthropicOrangeLight = Color(0xFFE8895A)
private val DeepNavy = Color(0xFF1A1A2E)
private val SoftWhite = Color(0xFFF8F9FA)

private val DarkColorScheme = darkColorScheme(
    primary = AnthropicOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C2E18),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color.Black,
    background = DeepNavy,
    onBackground = Color.White,
    surface = Color(0xFF16213E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF0F3460),
    onSurfaceVariant = Color(0xFFB0BEC5)
)

private val LightColorScheme = lightColorScheme(
    primary = AnthropicOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF5C2E18),
    secondary = Color(0xFF546E7A),
    onSecondary = Color.White,
    background = SoftWhite,
    onBackground = Color(0xFF1A1A2E),
    surface = Color.White,
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFF546E7A)
)

@Composable
fun BalanceAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
