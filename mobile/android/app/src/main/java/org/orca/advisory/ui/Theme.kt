package org.orca.advisory.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/**
 * DAY — sea blue, ivory, emerald. A LIGHT theme, deliberately.
 *
 * <p>The earlier day palette was dark, on the reasoning that a dark
 * ground fights glare. That is backwards for an LCD in sunlight: a phone
 * screen competes with the sun by being BRIGHT, and a light ground drives
 * the backlight hardest. Dark text on ivory is also what every printed
 * chart and every harbour noticeboard already looks like.
 *
 * <p>Ivory rather than pure white: #FFFFFF at full brightness on a deck
 * is genuinely painful, and a warm off-white loses nothing in contrast.
 * Panels are the pure white, so a card lifts off the page without a
 * shadow -- shadows disappear in glare, tone does not.
 */
val DayPalette = Palette(
    name = "day",
    hull = Color(0xFFF7F4EC),        // ivory
    panel = Color(0xFFFFFFFF),
    ink = Color(0xFF0B2B3C),         // deep sea blue, near-black
    muted = Color(0xFF5A7382),
    line = Color(0xFFE2E7E4),
    accent = Color(0xFF0E6E8C),      // sea blue
    onAccent = Color(0xFFFFFFFF),
    // Status colours are dark enough that WHITE sits on them at 4.5:1 --
    // Headline paints `onAccent` over these, so a pale emerald would put
    // white on white the one time the screen matters most.
    go = Color(0xFF0B7D57),          // emerald
    caution = Color(0xFFB26A00),
    deny = Color(0xFFC62828),
    unknown = Color(0xFF5F7482),
)

/**
 * DUSK — the same sea blue, after sunset.
 *
 * <p>The eye is not dark-adapted yet, so this is not the night palette
 * dimmed: it is the day palette inverted, keeping full colour. Ivory
 * becomes deep twilight blue, and the accent warms toward the horizon it
 * is named for. Emerald and the warning colours brighten, because they
 * now have to carry on a dark ground rather than a light one.
 */
val DuskPalette = Palette(
    name = "dusk",
    hull = Color(0xFF0C1E29),
    panel = Color(0xFF14303E),
    ink = Color(0xFFEAF1F4),
    muted = Color(0xFF9BB3BF),
    line = Color(0xFF1F4256),
    accent = Color(0xFFE0A24A),      // low sun
    onAccent = Color(0xFF0A1922),
    go = Color(0xFF35B587),          // emerald, lifted for a dark ground
    caution = Color(0xFFE09A42),
    deny = Color(0xFFE06055),
    unknown = Color(0xFF7E97A4),
)

/**
 * NIGHT — red-dominant, and deliberately the dimmest thing on the boat.
 *
 * <p>This one breaks the sea-blue scheme on purpose. Rod cells barely
 * respond to long wavelengths, so a red-shifted display costs a helmsman
 * almost none of the twenty-odd minutes of dark adaptation that let them
 * see an unlit hull. Every wheelhouse in the world dims to red for the
 * same reason.
 *
 * <p>Nothing here is bright, INCLUDING the good state. A green flash at
 * 3 a.m. destroys night vision exactly as thoroughly as a white one, so
 * `go` is a desaturated sage rather than the emerald used by day. The
 * verdict is still legible; it just does not shout.
 */
val NightPalette = Palette(
    name = "night",
    hull = Color(0xFF07090B),
    panel = Color(0xFF11161A),
    ink = Color(0xFFD9B7B2),
    muted = Color(0xFF94706C),
    line = Color(0xFF221A1B),
    accent = Color(0xFFC4584E),
    onAccent = Color(0xFF07090B),
    go = Color(0xFF7E9E8A),
    caution = Color(0xFFB87A4A),
    deny = Color(0xFFD9584A),
    unknown = Color(0xFF6E7C82),
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
    // The scheme follows the PALETTE, never the system setting. Day is now
    // a light ground and dusk/night are dark, so handing Material a
    // darkColorScheme for all three would have it derive ripples, scrims
    // and disabled states for the wrong ground -- the inherited-token mess
    // this file exists to prevent. isSystemInDarkTheme() is referenced so
    // the dependency stays explicit rather than accidentally absent: ORCA
    // deliberately does NOT follow it, because which palette a crew wants
    // is about the sky outside, not a setting in Android.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    val scheme = if (palette.name == "day") {
        lightColorScheme(
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
    } else {
        darkColorScheme(
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
    }
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
