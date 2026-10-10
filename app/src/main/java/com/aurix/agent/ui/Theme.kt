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
    background = Color(0xFFF5F4EE), onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFEDEBE2), onSurfaceVariant = Color(0xFF6B6A68),
    outline = Color(0xFFDDDACE), error = Color(0xFFB3261E), tertiary = Color(0xFFB7791F),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE5262B), onPrimary = Color.White,
    background = Color(0xFF07070A), onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF14141A), onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1C1C24), onSurfaceVariant = Color(0xFFA0A0AE),
    outline = Color(0xFF34343F), error = Color(0xFFFF6B6B), tertiary = Color(0xFFFFB74D),
)

val AurixGreen = Color(0xFF5BA874)
val AurixGreenBg = Color(0x225BA874)
val AurixRedBg = Color(0x22E5534B)

private val Type = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
)

@Composable
fun AurixTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Dark, typography = Type) {
        // Opaque root so a screen transition or drawer scrim can never expose a black window behind the content.
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
