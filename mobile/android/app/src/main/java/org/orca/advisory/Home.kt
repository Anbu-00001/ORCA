package org.orca.advisory

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.orca.advisory.ui.LocalPalette
import org.orca.advisory.ui.colorForAction

/**
 * The home screen.
 *
 * <p>WHAT THIS REPLACED, and why. The previous home was ten stacked cards
 * of Tamil-then-English text. Every feature was one tap away, which was
 * the goal, but the screen read as a list of frequently-asked questions
 * rather than an instrument. The verdict -- the single thing the whole
 * app exists to deliver -- had the same visual weight as "share with
 * nearby boats".
 *
 * <p>So this is built around a hierarchy instead of a list:
 *
 * <ol>
 *  <li>ONE answer, large, in colour, with the readings that produced it.
 *      A crew glancing at this from two metres away gets the verdict and
 *      nothing else competes for that glance.
 *  <li>ONE emergency control, fixed in the same place on every launch, at
 *      thumb height. It is never scrolled to and never hunted for.
 *  <li>Everything else as a grid of small tiles, because at that point
 *      the user is browsing, not deciding.
 * </ol>
 *
 * <p>Nothing here computes anything. The verdict, its colour and every
 * number come from `/bundle`, decided by `orca/policy.py` on shore.
 */
@Composable
fun HomeScreen(
    advisory: OrcaRepository.Advisory?,
    refreshing: Boolean,
    refreshNote: String?,
    onSos: () -> Unit,
    go: (Screen) -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val zone = advisory?.zones?.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        VerdictCard(advisory, zone, refreshing, refreshNote) { go(Screen.VERDICT) }

        SosBlock(onSos)

        QuickGrid(go)

        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------
// The verdict, as an instrument rather than a paragraph
// ---------------------------------------------------------------------

@Composable
private fun VerdictCard(
    advisory: OrcaRepository.Advisory?,
    zone: OrcaRepository.ZoneAdvisory?,
    refreshing: Boolean,
    refreshNote: String?,
    onOpen: () -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val tint = zone?.let { p.colorForAction(it.action) } ?: p.unknown

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.panel)
            .border(1.dp, p.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .padding(18.dp),
    ) {
        // --- header row: what this is, and how old ---------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            // NO letterSpacing on Tamil. Tracking is applied per character,
            // and Tamil letters are grapheme CLUSTERS -- a consonant plus its
            // pulli or vowel sign. Spacing them apart detaches the mark from
            // its letter: "அவசரம் என்றால்" rendered as "அவசரம எனறால" on
            // hardware, which is not a typographic nicety, it is a different
            // word. English-only labels may still use it.
            Text(
                str(S.TODAYS_VERDICT, lang),
                color = p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            AgeChip(advisory, refreshing)
        }

        Spacer(Modifier.height(16.dp))

        // --- the ring and the answer -----------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            VerdictRing(zone?.action, tint)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    zone?.let { actionWord(it.action, lang) } ?: str(S.NO_ADVISORY, lang),
                    color = p.ink, fontSize = 25.sp,
                    fontWeight = FontWeight.Black, lineHeight = 30.sp,
                )
                Text(
                    zone?.let { "${it.zone} · ${it.action}" } ?: "No advisory stored",
                    color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                if (zone?.reason?.isNotEmpty() == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        zone.reason, color = p.muted, fontSize = 13.sp,
                        lineHeight = 18.sp, maxLines = 3,
                    )
                }
            }
        }

        // --- the readings that produced it ------------------------------
        if (zone != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = p.line)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric(Icons.Filled.Waves, str(S.WAVE, lang), zone.reading("wave_height_m"), "m", Modifier.weight(1f))
                // Knots, not km/h. Every chart, forecast and conversation at
                // sea is in knots; km/h is the unit the API happens to
                // publish, not the unit anyone navigates in.
                Metric(Icons.Filled.Air, str(S.WIND, lang), zone.reading("wind_speed_kmh"), "kn", Modifier.weight(1f))
                Metric(Icons.Filled.Thermostat, str(S.TEMP, lang), zone.reading("sst_c"), "°C", Modifier.weight(1f))
            }
        }

        if (refreshNote != null) {
            Spacer(Modifier.height(12.dp))
            Text(refreshNote, color = p.caution, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/**
 * The ring.
 *
 * <p>It is NOT a percentage. The reference design this borrows its shape
 * from filled a ring with a "safety index" out of 100, and ORCA has no
 * such number and will not invent one -- a single score would flatten
 * four sources with different confidences into a figure with no units
 * (CLAUDE.md rule 1). The ring here is a full circle whose COLOUR is the
 * verdict, with the verdict's own icon inside. It reads at a glance the
 * same way, and it claims nothing.
 */
@Composable
private fun VerdictRing(action: String?, tint: Color) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val sweep by animateFloatAsState(if (action == null) 0.25f else 1f, label = "ring")
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            drawArc(
                color = tint.copy(alpha = 0.16f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
            drawArc(
                color = tint,
                startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
        Icon(
            when (action) {
                "GO" -> Icons.Filled.Check
                "DO NOT GO" -> Icons.Filled.Close
                "SAFER ALTERNATIVE" -> Icons.Filled.SwapHoriz
                else -> Icons.Filled.QuestionMark
            },
            contentDescription = action,
            tint = tint,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun Metric(icon: ImageVector, label: String, r: OrcaRepository.Reading?, unit: String, m: Modifier) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = p.accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(6.dp))
        // An absent reading prints a dash, never a zero. A 0.0 m sea is a
        // measurement; "we have no measurement" is not (CLAUDE.md rule 1).
        Text(
            if (r == null) "—" else Units.convertedValue(r.variable, r.value)?.first ?: trimNum(r.value),
            color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        )
        Text(if (r == null) label else "$label · $unit", color = p.muted, fontSize = 11.sp)
    }
}

@Composable
private fun AgeChip(advisory: OrcaRepository.Advisory?, refreshing: Boolean) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val mins = advisory?.readingAgeMinutes()
    val text = when {
        refreshing -> str(S.REFRESHING, lang)
        advisory == null -> "—"
        mins == null -> str(S.AGE_UNKNOWN, lang)
        mins < 60 -> "$mins ${str(S.MIN, lang)}"
        mins < 2880 -> "${mins / 60} ${str(S.HOUR, lang)}"
        else -> "${mins / 1440} ${str(S.DAY, lang)}"
    }
    // Stale data is the failure mode that kills people quietly, so age is
    // never neutral once it is old.
    val tone = when {
        mins == null -> p.muted
        mins > 1440 -> p.deny
        mins > 360 -> p.caution
        else -> p.muted
    }
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, tint = tone, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------
// SOS — fixed position, thumb height, never scrolled to
// ---------------------------------------------------------------------

@Composable
private fun SosBlock(onSos: () -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            str(S.IN_EMERGENCY, lang),
            color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        HoldToSos(onSos)
        Spacer(Modifier.height(12.dp))
        Text(
            str(S.SOS_HINT_1, lang),
            color = p.muted, fontSize = 12.5.sp, lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            str(S.SOS_HINT_2, lang),
            color = p.muted, fontSize = 11.5.sp, lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Hold-to-fire, not tap-to-fire.
 *
 * <p>A single tap on a pocketed phone would send a false distress call,
 * and a false SOS is not a harmless bug -- it spends a rescue that
 * somebody else needed. The ring fills while held so the crew can see the
 * commitment happening and let go.
 */
@Composable
private fun HoldToSos(onSos: () -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val started = System.currentTimeMillis()
        while (holding && progress < 1f) {
            progress = ((System.currentTimeMillis() - started) / 2000f).coerceAtMost(1f)
            if (progress >= 1f) { holding = false; onSos() }
            delay(16)
        }
    }

    Box(
        Modifier.size(184.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(p.deny.copy(alpha = 0.13f), size.minDimension / 2)
            drawCircle(p.deny, size.minDimension / 2 - 14.dp.toPx())
            if (progress > 0f) {
                val w = 10.dp.toPx()
                drawArc(
                    color = Color.White,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(width = w, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(4.dp))
            Text("SOS", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(
                if (holding) str(S.HOLDING, lang) else str(S.HOLD_2S, lang),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Everything else, as tiles
// ---------------------------------------------------------------------

private data class Tile(
    val icon: ImageVector,
    val key: S,
    val screen: Screen,
)

@Composable
private fun QuickGrid(go: (Screen) -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    // Each tile shows the chosen language on top and ENGLISH beneath, unless
    // English IS the chosen language -- a Tamil reader still benefits from
    // seeing the English word that is printed on every official form.
    val tiles = listOf(
        Tile(Icons.Filled.Map, S.T_CHART, Screen.MAP),
        Tile(Icons.Filled.Storm, S.T_STORM, Screen.STORM),
        Tile(Icons.Filled.Phishing, S.T_FISH, Screen.FISH),
        Tile(Icons.Filled.Fence, S.T_BOUNDARY, Screen.BOUNDARY),
        Tile(Icons.Filled.GppMaybe, S.T_FENCE, Screen.FENCE),
        Tile(Icons.Filled.Explore, S.T_DRIFT, Screen.DRIFT),
        Tile(Icons.Filled.FlashlightOn, S.T_LIGHT, Screen.SIGNAL),
        Tile(Icons.Filled.Waves, S.T_WAVE, Screen.WAVE),
        Tile(Icons.Filled.Hub, S.T_FLEET, Screen.FLEET),
        Tile(Icons.Filled.Mic, S.T_ASK, Screen.ASK),
        Tile(Icons.Filled.Sms, S.T_WARN, Screen.ALERTS),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            str(S.EVERYTHING_ELSE, lang),
            color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    TileCard(t, Modifier.weight(1f)) { go(t.screen) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TileCard(t: Tile, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(
        m.clip(RoundedCornerShape(16.dp))
            .background(p.panel)
            .border(1.dp, p.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background(p.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.icon, null, tint = p.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(str(t.key, lang), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (lang != Lang.EN) {
            Text(str(t.key, Lang.EN), color = p.muted, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------------
// shared
// ---------------------------------------------------------------------

fun actionTa(action: String) = when (action) {
    "GO" -> "போகலாம்"
    "DO NOT GO" -> "போக வேண்டாம்"
    "SAFER ALTERNATIVE" -> "வேறு இடம்"
    "CANNOT ASSESS" -> "தெரியவில்லை"
    else -> action
}

fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
