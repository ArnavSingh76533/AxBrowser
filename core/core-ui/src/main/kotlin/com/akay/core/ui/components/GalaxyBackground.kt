package com.akay.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.akay.core.ui.theme.LocalAccentColor
import com.akay.core.ui.theme.LocalGalaxyConfig
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val phase: Float,
    val twinkleSpeed: Float,
    val baseAlpha: Float
)

/**
 * An animated deep-space backdrop: a vertical night-sky gradient, two soft
 * nebula glows tinted by the current accent, and a field of twinkling stars.
 * Fully self-contained and cheap (one Canvas, a single driving phase).
 */
@Composable
fun GalaxyBackground(
    modifier: Modifier = Modifier,
    accent: Color = LocalAccentColor.current,
    intensity: Float = LocalGalaxyConfig.current.intensity,
    enabled: Boolean = LocalGalaxyConfig.current.enabled,
    content: @Composable () -> Unit = {}
) {
    val baseGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF05060B), Color(0xFF0B0A1A), Color(0xFF07070F))
    )

    Box(modifier = modifier.background(baseGradient)) {
        if (enabled) {
            val starCount = (70 + (intensity.coerceIn(0f, 1f) * 90)).toInt()
            val stars = remember(starCount) {
                val rnd = Random(42)
                List(starCount) {
                    Star(
                        x = rnd.nextFloat(),
                        y = rnd.nextFloat(),
                        radius = rnd.nextFloat() * 1.6f + 0.4f,
                        phase = rnd.nextFloat() * 6.28f,
                        twinkleSpeed = rnd.nextFloat() * 0.9f + 0.4f,
                        baseAlpha = rnd.nextFloat() * 0.5f + 0.3f
                    )
                }
            }

            val transition = rememberInfiniteTransition(label = "galaxy")
            val t by transition.animateFloat(
                initialValue = 0f,
                targetValue = 6.2832f,
                animationSpec = infiniteRepeatable(
                    animation = tween((7000 / intensity.coerceIn(0.3f, 2f)).toInt(), easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "twinkle"
            )

            val nebulaBright = accent.luminance().coerceIn(0.2f, 0.9f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Nebula glows (accent-tinted) high and low.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f * nebulaBright), Color.Transparent),
                        center = Offset(w * 0.18f, h * 0.16f),
                        radius = w * 0.7f
                    ),
                    radius = w * 0.7f,
                    center = Offset(w * 0.18f, h * 0.16f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF3A2C6E).copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.85f, h * 0.72f),
                        radius = w * 0.8f
                    ),
                    radius = w * 0.8f,
                    center = Offset(w * 0.85f, h * 0.72f)
                )

                // Twinkling stars.
                stars.forEach { star ->
                    val a = (star.baseAlpha + 0.35f * sin(t * star.twinkleSpeed + star.phase))
                        .coerceIn(0.05f, 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = a),
                        radius = star.radius,
                        center = Offset(star.x * w, star.y * h)
                    )
                }
            }
        }
        content()
    }
}
