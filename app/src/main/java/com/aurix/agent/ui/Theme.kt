package com.aurix.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE3EAF0),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF1B2330),
    onSurfaceVariant = Color(0xFF9FB0C0),
    tertiary = Color(0xFFFFB74D),
)

@Composable
fun AurixTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
