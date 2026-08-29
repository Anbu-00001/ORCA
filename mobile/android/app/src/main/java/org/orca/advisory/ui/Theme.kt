package org.orca.advisory.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ORCA's three palettes, as complete token sets.
 *
 * WHY THREE, AND WHY THIS IS NOT DECORATION. Certified marine chart
 * displays are required to offer day, dusk and night modes, because a
 * bright screen in a wheelhouse at night destroys the helmsman's dark
 * adaptation for twenty minutes. Night mode here is red-dominant for
 * exactly that reason: red light preserves scotopic vision.
 *
 * WHY EVERY PALETTE DEFINES EVERY TOKEN. The web client's dusk and night
 * modes render as overlapping, unreadable panels because they redefine
 * only *some* variables and inherit the rest from day -- so a dark panel
 * ends up with day's dark text on it. Here a palette is a complete
 * `Palette` value or it does not compile. That is the whole fix, and it
 * is structural rather than a patch.
 */
data class Palette(
    val name: String,
    /** Page ground. */
    val hull: Color,
    /** Panel ground, one step up from the hull. */
    val panel: Color,
    /** Primary text on hull/panel. */
    val ink: Color,
    /** Secondary text. Must stay legible, never below 4.5:1 on panel. */
    val muted: Color,
    /** Hairlines and dividers. */
    val line: Color,
    /** The single accent. Every palette has exactly one. */
    val accent: Color,
    /** Text that sits ON the accent. */
    val onAccent: Color,
    val go: Color,
    val caution: Color,
    val deny: Color,
    val unknown: Color,
)

/** Daylight on deck: maximum contrast, because the competition is the sun. */
val DayPalette = Palette(
    name = "day",
    hull = Color(0xFF071C26),
    panel = Color(0xFF0E2A36),
    ink = Color(0xFFF4F7F8),
    muted = Color(0xFFA8BDC6),
    line = Color(0xFF1E3E4C),
    accent = Color(0xFFD9B048),
    onAccent = Color(0xFF071C26),
    go = Color(0xFF3FBF7F),
    caution = Color(0xFFE8A33D),
    deny = Color(0xFFE3564C),
    unknown = Color(0xFF8FA3AD),
)

/** Dusk: the sun is down but the eye is not dark-adapted yet. Warmer,
 *  dimmer, still full colour. */
val DuskPalette = Palette(
    name = "dusk",
    hull = Color(0xFF120E1A),
    panel = Color(0xFF1C1726),
    ink = Color(0xFFE8E2F0),
    muted = Color(0xFF9E93B0),
    line = Color(0xFF2E2740),
    accent = Color(0xFFC98A4B),
    onAccent = Color(0xFF120E1A),
    go = Color(0xFF4FA87A),
    caution = Color(0xFFD1893C),
    deny = Color(0xFFCF5347),
    unknown = Color(0xFF847A94),
)

/** Night: red-dominant, deliberately low luminance. Nothing here is
 *  bright, including the "good" state -- a green flash at 3 a.m. costs
 *  the helmsman their night vision just as surely as a white one. */
val NightPalette = Palette(
    name = "night",
    hull = Color(0xFF0A0405),
    panel = Color(0xFF160A0B),
    ink = Color(0xFFE8B4B0),
    muted = Color(0xFFA1706D),
    line = Color(0xFF32191A),
    accent = Color(0xFFC85A50),
    onAccent = Color(0xFF0A0405),
    go = Color(0xFFB5705E),
    caution = Color(0xFFC46A4A),
    deny = Color(0xFFE05A4A),
    unknown = Color(0xFF8A5F5C),
)

val Palettes = listOf(DayPalette, DuskPalette, NightPalette)

val LocalPalette = compositionLocalOf { DayPalette }

/** Big by default. The reference user is reading this at arm's length,
 *  on a moving deck, in glare, possibly without their glasses. */
private val OrcaTypography = Typography(
    displayLarge = TextStyle(fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.Black),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun OrcaTheme(palette: Palette = DayPalette, content: @Composable () -> Unit) {
    // darkColorScheme in every palette: all three grounds are dark, so the
    // system's own light/dark guess is irrelevant here and following it
    // would produce the exact inherited-token mess this file exists to
    // prevent. isSystemInDarkTheme() is referenced only so the dependency
    // is explicit rather than accidentally absent.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        background = palette.hull,
        onBackground = palette.ink,
        surface = palette.panel,
        onSurface = palette.ink,
        surfaceVariant = palette.panel,
        onSurfaceVariant = palette.muted,
        outline = palette.line,
        error = palette.deny,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = OrcaTypography, content = content)
    }
}

/** The colour for a verdict. NEVER a default that means permission: an
 *  action ORCA does not recognise renders neutral, exactly as the web
 *  client's actionClass() and the 3D view's ACTION_COLOR do. */
fun Palette.colorForAction(action: String): Color = when (action) {
    "GO" -> go
    "SAFER ALTERNATIVE" -> caution
    "DO NOT GO" -> deny
    else -> unknown
}
