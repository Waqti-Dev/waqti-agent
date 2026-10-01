package com.waqti.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Waqti's design system.
 *
 * The interface is a working instrument, not a chat demo, so the system is
 * deliberately small: one accent colour family, a 4dp spacing scale, two corner
 * radii families and a type scale that only changes where hierarchy needs it.
 *
 * Identity decisions, and why:
 * - Petrol teal is the accent. It reads as instrumentation rather than as one of
 *   the stock Material identities, and it stays legible against warm paper in
 *   light mode and ink in dark mode without a second accent colour.
 * - Warm paper light / cool ink dark. The two schemes are not inversions: the
 *   light surface is warm and the dark surface is cool, so text contrast in dark
 *   mode does not turn grey.
 * - Every surface role used by the chat is named explicitly, so no component
 *   silently falls back to a stock Material shade.
 *
 * No font files are bundled; hierarchy comes from size, weight, line height and
 * tracking on the platform family, which keeps the APK small and renders
 * identically to the system text the user already reads everywhere else.
 */

// --- colour ------------------------------------------------------------------

/** Petrol teal on warm paper. Primary contrast on [WaqtiLightBackground] is ~5.4:1. */
private val WaqtiLightBackground = Color(0xFFF7F6F2)
private val WaqtiLightContainerLowest = Color(0xFFFFFFFF)
private val WaqtiLightContainerLow = Color(0xFFFDFCF9)
private val WaqtiLightContainer = Color(0xFFF1EFEA)
private val WaqtiLightContainerHigh = Color(0xFFEAE8E2)
private val WaqtiLightContainerHighest = Color(0xFFE4E2DC)

private val WaqtiLightColors = lightColorScheme(
    primary = Color(0xFF0B6B5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7EDE2),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF44635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7EAE4),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF7E5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB2),
    onTertiaryContainer = Color(0xFF281700),
    background = WaqtiLightBackground,
    onBackground = Color(0xFF181C1B),
    surface = WaqtiLightBackground,
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFE7E5DF),
    onSurfaceVariant = Color(0xFF474A47),
    surfaceContainerLowest = WaqtiLightContainerLowest,
    surfaceContainerLow = WaqtiLightContainerLow,
    surfaceContainer = WaqtiLightContainer,
    surfaceContainerHigh = WaqtiLightContainerHigh,
    surfaceContainerHighest = WaqtiLightContainerHighest,
    surfaceTint = Color(0xFF0B6B5F),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFF1F0EB),
    inversePrimary = Color(0xFF8AD6C4),
    outline = Color(0xFF787B77),
    outlineVariant = Color(0xFFCBCCC6),
    error = Color(0xFFA02F27),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDBD7),
    onErrorContainer = Color(0xFF3F0B08),
    scrim = Color(0xFF000000)
)

/** Cool ink dark. [WaqtiDarkPrimary] on the background is ~9:1. */
private val WaqtiDarkPrimary = Color(0xFF7FD6C3)
private val WaqtiDarkColors = darkColorScheme(
    primary = WaqtiDarkPrimary,
    onPrimary = Color(0xFF003830),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFF9CF2DE),
    secondary = Color(0xFFB0CCC4),
    onSecondary = Color(0xFF1B352F),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFF5BE73),
    onTertiary = Color(0xFF452700),
    tertiaryContainer = Color(0xFF653C00),
    onTertiaryContainer = Color(0xFFFFDDB2),
    background = Color(0xFF0E1112),
    onBackground = Color(0xFFE4E3DE),
    surface = Color(0xFF0E1112),
    onSurface = Color(0xFFE4E3DE),
    surfaceVariant = Color(0xFF21272A),
    onSurfaceVariant = Color(0xFFBEC8C6),
    surfaceContainerLowest = Color(0xFF090B0C),
    surfaceContainerLow = Color(0xFF16191B),
    surfaceContainer = Color(0xFF1A1E20),
    surfaceContainerHigh = Color(0xFF24292B),
    surfaceContainerHighest = Color(0xFF2F3437),
    surfaceTint = WaqtiDarkPrimary,
    inverseSurface = Color(0xFFE4E3DE),
    inverseOnSurface = Color(0xFF181C1B),
    inversePrimary = Color(0xFF0B6B5F),
    outline = Color(0xFF3B4345),
    outlineVariant = Color(0xFF293033),
    error = Color(0xFFFFB3AA),
    onError = Color(0xFF5F120C),
    errorContainer = Color(0xFF8C1F18),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color(0xFF000000)
)

// --- type --------------------------------------------------------------------

private val WaqtiTypeFamily = FontFamily.Default

private fun waqtiText(
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = WaqtiTypeFamily,
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    letterSpacing = tracking.sp
)

/**
 * Reading text carries the most line height in the scale (1.55) because a Waqti
 * answer is often a paragraph or three; micro labels tighten tracking instead of
 * shrinking, so status text stays legible at large font scales.
 */
private val WaqtiTypography = Typography(
    displaySmall = waqtiText(29.sp, 35.sp, FontWeight.SemiBold, -0.6),
    headlineMedium = waqtiText(24.sp, 30.sp, FontWeight.SemiBold, -0.4),
    headlineSmall = waqtiText(21.sp, 27.sp, FontWeight.SemiBold, -0.3),
    titleLarge = waqtiText(18.sp, 24.sp, FontWeight.SemiBold, -0.2),
    titleMedium = waqtiText(16.sp, 22.sp, FontWeight.SemiBold, -0.1),
    titleSmall = waqtiText(14.sp, 20.sp, FontWeight.SemiBold, 0.0),
    bodyLarge = waqtiText(15.5.sp, 24.sp, FontWeight.Normal, 0.1),
    bodyMedium = waqtiText(14.sp, 21.sp, FontWeight.Normal, 0.1),
    bodySmall = waqtiText(12.5.sp, 18.sp, FontWeight.Normal, 0.15),
    labelLarge = waqtiText(13.sp, 18.sp, FontWeight.Medium, 0.2),
    labelMedium = waqtiText(12.sp, 16.sp, FontWeight.Medium, 0.3),
    labelSmall = waqtiText(10.5.sp, 14.sp, FontWeight.SemiBold, 0.9)
)

// --- shape -------------------------------------------------------------------

/** Two families only: a 12dp "detail" radius and a 22dp "panel" radius. */
private val WaqtiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// --- space and motion --------------------------------------------------------

/**
 * A 4dp base scale. Components read these instead of inventing their own dp, so
 * vertical rhythm stays consistent across the header, conversation and composer.
 */
object WaqtiSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 22.dp
    val xxl = 30.dp

    /** Horizontal screen margin. Widened by [contentGutter] on roomy screens. */
    val gutter = 20.dp

    /** Distance between consecutive conversation items. */
    val messageGap = 18.dp

    /** Maximum readable line length for an answer on a wide screen. */
    val measure = 620.dp

    fun contentGutter(available: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        if (available >= 480.dp) 28.dp else gutter
}

/**
 * Motion durations in milliseconds. Every animation in the app uses one of
 * these, and each one is tied to a state change the user can actually observe.
 */
object WaqtiMotion {
    const val FAST = 140
    const val MEDIUM = 240
    const val SLOW = 400
}

// --- theme -------------------------------------------------------------------

@Composable
fun WaqtiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WaqtiDarkColors else WaqtiLightColors,
        typography = WaqtiTypography,
        shapes = WaqtiShapes,
        content = content
    )
}