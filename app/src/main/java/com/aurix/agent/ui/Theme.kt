package com.aurix.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFFC96442), onPrimary = Color.White,
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF0EEE6), onSurfaceVariant = Color(0xFF6B6A68),
    outline = Color(0xFFD9D6CC), error = Color(0xFFB3261E), tertiary = Color(0xFFB7791F),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFD97757), onPrimary = Color(0xFF1F1E1D),
    background = Color(0xFF262624), onBackground = Color(0xFFF5F4EE),
    surface = Color(0xFF262624), onSurface = Color(0xFFF5F4EE),
    surfaceVariant = Color(0xFF30302E), onSurfaceVariant = Color(0xFFB5B3AB),
    outline = Color(0xFF4A4945), error = Color(0xFFE5766C), tertiary = Color(0xFFE0A14A),
)

val AurixGreen = Color(0xFF5BA874)
val AurixGreenBg = Color(0x225BA874)
val AurixRedBg = Color(0x22E5534B)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
)

@Composable
fun AurixTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
