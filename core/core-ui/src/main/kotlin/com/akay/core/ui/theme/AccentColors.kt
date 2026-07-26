package com.akay.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Named accent palette. These are AxBrowser's own values — a cohesive
 * cosmic-themed set tuned for AMOLED backgrounds, not lifted from any app.
 */
data class AccentOption(val name: String, val color: Color)

val AccentColors: List<AccentOption> = listOf(
    AccentOption("Nebula Violet", Color(0xFF7C5CFF)),
    AccentOption("Aurora Cyan",   Color(0xFF22D3EE)),
    AccentOption("Pulsar Pink",   Color(0xFFF864A6)),
    AccentOption("Solar Amber",   Color(0xFFF6A821)),
    AccentOption("Comet Green",   Color(0xFF2DD4A7)),
    AccentOption("Ion Blue",      Color(0xFF4C8DFF)),
    AccentOption("Ember Red",     Color(0xFFFF6B6B)),
    AccentOption("Orchid",        Color(0xFFB57CFF))
)

val DefaultAccent: Color = AccentColors.first().color

fun accentByName(name: String): Color =
    AccentColors.firstOrNull { it.name == name }?.color ?: DefaultAccent

fun nameOfAccent(color: Color): String =
    AccentColors.firstOrNull { it.color == color }?.name ?: "Custom"

/** Current accent, provided by AxTheme so any component can tint to it. */
val LocalAccentColor = staticCompositionLocalOf { DefaultAccent }

/** Galaxy background configuration, provided by AxTheme. */
data class GalaxyConfig(val enabled: Boolean = true, val intensity: Float = 1f)

val LocalGalaxyConfig = staticCompositionLocalOf { GalaxyConfig() }
