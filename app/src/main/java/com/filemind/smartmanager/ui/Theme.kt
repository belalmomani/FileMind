package com.filemind.smartmanager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.filemind.smartmanager.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF5E8C6A), secondary = Color(0xFF4FA3B8), tertiary = Color(0xFFA79BD6),
    background = Color(0xFFF6F3EC), surface = Color(0xFFFBF9F4), onBackground = Color(0xFF262B26), onSurface = Color(0xFF262B26),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FBF9C), secondary = Color(0xFF7CC0D2), tertiary = Color(0xFFC1B7E8),
    background = Color(0xFF161A17), surface = Color(0xFF1E231F), onBackground = Color(0xFFE6E8E2), onSurface = Color(0xFFE6E8E2),
)

@Composable
fun FileMindTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
