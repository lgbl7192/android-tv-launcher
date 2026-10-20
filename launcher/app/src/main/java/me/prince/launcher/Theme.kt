package me.prince.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp

@Immutable
data class LauncherColors(
    val background: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val glassFill: Color,
    val glassHighlight: Color,
    // Top-left to bottom-right, brightest where light would catch the edge
    val glassRim: List<Color>,
    // Darkens in dark mode, lightens in light mode; callers pick the alpha
    val scrim: Color,
    val divider: Color,
    val tileBorder: Color,
    val tileBorderFocused: Color,
    // A white glow on dark, a soft shadow on light
    val focusGlow: Color,
    // Scales the focused app's colour glowing behind the shelves
    val glowStrength: Float,
)

// Pure black switches OLED pixels off entirely
private val DarkColors = LauncherColors(
    background = Color.Black,
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.75f),
    glassFill = Color.White.copy(alpha = 0.09f),
    glassHighlight = Color.White.copy(alpha = 0.16f),
    glassRim = listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.22f)),
    scrim = Color.Black,
    divider = Color.White.copy(alpha = 0.24f),
    tileBorder = Color.White.copy(alpha = 0.10f),
    tileBorderFocused = Color.White.copy(alpha = 0.6f),
    focusGlow = Color.White.copy(alpha = 0.25f),
    glowStrength = 1f,
)

private val Ink = Color(0xFF1C1C1E)

private val LightColors = LauncherColors(
    background = Color(0xFFE5E5EA),
    textPrimary = Ink,
    textSecondary = Ink.copy(alpha = 0.65f),
    glassFill = Color.White.copy(alpha = 0.45f),
    glassHighlight = Color.White.copy(alpha = 0.5f),
    glassRim = listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.6f)),
    scrim = Color.White,
    divider = Ink.copy(alpha = 0.12f),
    tileBorder = Ink.copy(alpha = 0.08f),
    tileBorderFocused = Color.White.copy(alpha = 0.9f),
    focusGlow = Ink.copy(alpha = 0.22f),
    glowStrength = 0.6f,
)

val LocalColors = compositionLocalOf { DarkColors }

internal const val THEME_FADE_MS = 1000

@Composable
fun LauncherTheme(light: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(if (light) 1f else 0f, tween(THEME_FADE_MS), label = "theme")
    val colors = remember(progress) { lerp(DarkColors, LightColors, progress) }
    CompositionLocalProvider(LocalColors provides colors, content = content)
}

private fun lerp(a: LauncherColors, b: LauncherColors, t: Float): LauncherColors = when (t) {
    0f -> a
    1f -> b
    else -> LauncherColors(
        background = lerp(a.background, b.background, t),
        textPrimary = lerp(a.textPrimary, b.textPrimary, t),
        textSecondary = lerp(a.textSecondary, b.textSecondary, t),
        glassFill = lerp(a.glassFill, b.glassFill, t),
        glassHighlight = lerp(a.glassHighlight, b.glassHighlight, t),
        glassRim = a.glassRim.zip(b.glassRim) { x, y -> lerp(x, y, t) },
        scrim = lerp(a.scrim, b.scrim, t),
        divider = lerp(a.divider, b.divider, t),
        tileBorder = lerp(a.tileBorder, b.tileBorder, t),
        tileBorderFocused = lerp(a.tileBorderFocused, b.tileBorderFocused, t),
        focusGlow = lerp(a.focusGlow, b.focusGlow, t),
        glowStrength = lerp(a.glowStrength, b.glowStrength, t),
    )
}
