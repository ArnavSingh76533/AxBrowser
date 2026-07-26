package com.akay.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun darkScheme(accent: Color, amoled: Boolean) = darkColorScheme(
    primary            = accent,
    onPrimary          = if (accent.luminance() > 0.5f) Color.Black else Color.White,
    primaryContainer   = accent.copy(alpha = 0.22f),
    onPrimaryContainer = Color(0xFFEDEBFF),
    secondary          = accent.copy(alpha = 0.85f),
    secondaryContainer = accent.copy(alpha = 0.20f),
    tertiary           = Color(0xFF22D3EE),
    background         = if (amoled) Color(0xFF000000) else Surface,
    surface            = if (amoled) Color(0xFF07070C) else Surface,
    surfaceVariant     = if (amoled) Color(0xFF141420) else SurfaceVariant,
    onSurface          = OnSurface,
    onSurfaceVariant   = OnSurfaceVariant,
    outline            = Color(0x33FFFFFF),
    error              = Error,
    onError            = OnError,
    errorContainer     = ErrorContainer
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary            = accent,
    onPrimary          = if (accent.luminance() > 0.6f) Color.Black else Color.White,
    primaryContainer   = accent.copy(alpha = 0.16f),
    secondary          = accent,
    background         = LightSurface,
    surface            = LightSurface,
    surfaceVariant     = LightSurfaceVariant,
    onSurface          = LightOnSurface,
    onSurfaceVariant   = LightOnSurfaceVariant,
    error              = LightError,
    onError            = OnPrimary
)

@Composable
fun AxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = true,
    accent: Color = DefaultAccent,
    galaxy: GalaxyConfig = GalaxyConfig(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkScheme(accent, amoled) else lightScheme(accent)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalGalaxyConfig provides galaxy
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = AxTypography,
            shapes      = AxShapes,
            content     = content
        )
    }
}
