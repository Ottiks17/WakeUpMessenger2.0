package com.wakemessenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF0A2E0D),
    secondary = Color(0xFF37474F),
    background = Color(0xFFF7F9F7),
    surface = Color.White,
    error = Color(0xFFB3261E)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF07290C),
    primaryContainer = Color(0xFF2E7D32),
    onPrimaryContainer = Color(0xFFE8F5E9),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF101410),
    surface = Color(0xFF1A201A)
)

@Composable
fun WakeUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content
    )
}
