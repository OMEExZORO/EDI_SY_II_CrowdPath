package com.crowdpath.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ── Brand Palette ──────────────────────────────────────────────────────────
val NavyBg        = Color(0xFF0A0F1E)
val NavySurface   = Color(0xFF131929)
val NavyCard      = Color(0xFF1A2235)
val ElectricBlue  = Color(0xFF3D8EFF)
val CyanAccent    = Color(0xFF00D4FF)
val SlateText     = Color(0xFF94A3B8)
val WhiteText     = Color(0xFFFFFFFF)
val ErrorRed      = Color(0xFFFF5252)
val SuccessGreen  = Color(0xFF00E676)
val AmberWarning  = Color(0xFFFFC107)

// ── Color Scheme ───────────────────────────────────────────────────────────
private val CrowdPathDarkScheme = darkColorScheme(
    primary              = ElectricBlue,
    onPrimary            = WhiteText,
    primaryContainer     = Color(0xFF0D2552),
    onPrimaryContainer   = CyanAccent,
    secondary            = CyanAccent,
    onSecondary          = NavyBg,
    secondaryContainer   = Color(0xFF0A2A35),
    onSecondaryContainer = CyanAccent,
    tertiary             = Color(0xFF7C3AED),
    onTertiary           = WhiteText,
    background           = NavyBg,
    onBackground         = WhiteText,
    surface              = NavySurface,
    onSurface            = WhiteText,
    surfaceVariant       = NavyCard,
    onSurfaceVariant     = SlateText,
    error                = ErrorRed,
    onError              = WhiteText,
    outline              = Color(0xFF334155),
)

// ── Typography (system default — no custom font to avoid asset setup) ───────
private val CrowdPathTypography = Typography(
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, color = SlateText),
    labelLarge     = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, color = SlateText),
)

@Composable
fun CrowdPathTheme(
    content: @Composable () -> Unit
) {
    // Always use our custom dark scheme — no dynamic color, no light mode
    val colorScheme = CrowdPathDarkScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = NavyBg.toArgb()
            window.navigationBarColor = NavyBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = CrowdPathTypography,
        content     = content,
    )
}
