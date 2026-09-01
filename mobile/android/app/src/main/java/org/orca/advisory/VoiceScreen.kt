package org.orca.advisory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.orca.advisory.ui.LocalPalette

/**
 * Speak, and get an answer that looks unlike the other four.
 *
 * <h3>WHY THE ANSWERS LOOK SO DIFFERENT</h3>
 * A crew has to be able to tell, from across a boat and without reading,
 * WHICH of the five commands the phone understood. If every answer were a
 * card of text, a mis-heard command and a correct one would be
 * indistinguishable until somebody read the small print -- and on a
 * pitching deck nobody reads the small print. So each answer gets its own
 * colour, its own icon and its own single large number.
 *
 * <h3>EVERY NUMBER HERE IS REAL</h3>
 * The five PHRASES are a hardcoded closed set. The five ANSWERS are not:
 * wave height and wind come from the advisory bundle, the position comes
 * from the GNSS receiver, the storm count comes from IMD's CAP feed. Where
 * ORCA does not have a value it says so in words and shows no number,
 * because a demo that renders a comforting fixed figure is precisely the
 * fabrication CLAUDE.md rule 1 exists to prevent.
 */
@Composable
fun VoiceScreen(
    advisory: OrcaRepository.Advisory?,
    selected: String?,
    spoken: String?,
    onListen: () -> Unit,
    onOpenSos: () -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current
    // Tapped instead of spoken. A deck is loud, a recogniser needs a
    // network it may not have, and a crew with a wet screen still has to be
    // able to ask. Tapping a command shows exactly the same answer, so the
    // voice path is a convenience over a working app rather than the only
    // way in.
    var tapped by remember { mutableStateOf<VoiceDemo.Intent?>(null) }
    // A fresh utterance always wins over an older tap.
    LaunchedEffect(spoken) { if (!spoken.isNullOrBlank()) tapped = null }
    val heard = VoiceDemo.match(spoken) ?: tapped

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        // --- the microphone -------------------------------------------
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(p.accent).clickable(onClick = onListen).padding(vertical = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, null, tint = p.onAccent, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    bi("பேசுங்கள் · TAP AND SPEAK", lang),
                    color = p.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Black,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- what it heard, verbatim ----------------------------------
        if (!spoken.isNullOrBlank()) {
            Text(
                bi("கேட்டது · Heard", lang), color = p.muted,
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
            // The raw string, unedited. If the recogniser mangled the
            // words, the crew must be able to see that it did rather than
            // wonder why the answer looks wrong.
            Text("“$spoken”", color = p.ink, fontSize = 17.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(14.dp))
        }

        when {
            heard != null -> VoiceAnswer(heard, advisory, selected, onOpenSos)

            !spoken.isNullOrBlank() -> {
                // Understood nothing. Said plainly, never guessed at --
                // one of the five is a distress call.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(p.caution.copy(alpha = 0.16f)).padding(16.dp),
                ) {
                    Text(
                        bi("புரியவில்லை · NOT UNDERSTOOD", lang),
                        color = p.caution, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ORCA only acts on the five commands below. It will never guess " +
                            "at something close, because one of them calls for help.",
                        color = p.ink, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // --- the five, always listed ----------------------------------
        Text(
            bi("இதைச் சொல்லுங்கள் அல்லது தொடவும் · SAY ONE OF THESE — OR TAP IT", lang),
            color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))
        VoiceDemo.Intent.entries.forEach { intent ->
            val lit = intent == heard
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (lit) accentFor(intent, p).copy(alpha = 0.20f) else p.panel)
                    .clickable { tapped = intent }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    iconFor(intent), null,
                    tint = accentFor(intent, p), modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        VoiceDemo.example(intent, Lang.TA),
                        color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        VoiceDemo.example(intent, Lang.EN),
                        color = p.muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun iconFor(intent: VoiceDemo.Intent) = when (intent) {
    VoiceDemo.Intent.SEA -> Icons.Filled.Waves
    VoiceDemo.Intent.FISH -> Icons.Filled.Phishing
    VoiceDemo.Intent.STORM -> Icons.Filled.Storm
    VoiceDemo.Intent.POSITION -> Icons.Filled.MyLocation
    VoiceDemo.Intent.HELP -> Icons.Filled.Warning
}

@Composable
private fun accentFor(intent: VoiceDemo.Intent, p: org.orca.advisory.ui.Palette): Color =
    when (intent) {
        VoiceDemo.Intent.SEA -> p.accent
        VoiceDemo.Intent.FISH -> p.go
        VoiceDemo.Intent.STORM -> p.caution
        VoiceDemo.Intent.POSITION -> p.ink
        VoiceDemo.Intent.HELP -> p.deny
    }

/**
 * One answer, shaped to its question.
 *
 * Each branch deliberately uses a different layout, not just a different
 * colour: a big number, a list, a count, a coordinate pair, an action.
 */
@Composable
private fun VoiceAnswer(
    intent: VoiceDemo.Intent,
    advisory: OrcaRepository.Advisory?,
    selected: String?,
    onOpenSos: () -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current
    val zone = advisory?.zones?.firstOrNull { it.zone == selected } ?: advisory?.zones?.firstOrNull()
    val tint = accentFor(intent, p)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(tint).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(intent), null, tint = p.onAccent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                VoiceDemo.title(intent, lang),
                color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(14.dp))

        when (intent) {
            // 1. SEA — one enormous number, the wave height.
            VoiceDemo.Intent.SEA -> {
                val wave = zone?.reading("wave_height_m")
                val wind = zone?.reading("wind_speed_kmh")
                if (wave == null) {
                    Missing("ORCA has no wave reading stored for this harbour.")
                } else {
                    Text(
                        String.format("%.1f", wave.value),
                        color = p.onAccent, fontSize = 76.sp, fontWeight = FontWeight.Black,
                    )
                    Text(
                        bi("மீட்டர் அலை · metres of wave", lang),
                        color = p.onAccent, fontSize = 16.sp,
                    )
                    wind?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            bi("காற்று · Wind", lang) + "  ${Units.speed(it.value)}",
                            color = p.onAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Source(wave.source, wave.validTime)
                }
            }

            // 2. FISH — a list, not a number.
            VoiceDemo.Intent.FISH -> {
                Text(
                    bi("இன்று மீன்பிடிக்க ஏற்ற இடங்கள் · Where conditions favour fish", lang),
                    color = p.onAccent, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                // Fish zones need a live satellite pass, so this screen
                // says where to look rather than reciting a stored answer.
                Text(
                    bi("மீன் திரை திறக்கவும் · Open the Fish zones screen", lang),
                    color = p.onAccent, fontSize = 20.sp, fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A fishing zone is a statement about TODAY's satellite pass, so " +
                        "ORCA fetches it live rather than answering from memory.",
                    color = p.onAccent, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }

            // 3. STORM — a count, and it is either zero or it is not.
            VoiceDemo.Intent.STORM -> {
                val feed = advisory?.alerts
                if (feed == null) {
                    Missing("ORCA has not fetched IMD's warning feed on this phone.")
                } else {
                    Text(
                        "${feed.alerts.size}",
                        color = p.onAccent, fontSize = 76.sp, fontWeight = FontWeight.Black,
                    )
                    Text(
                        bi("IMD எச்சரிக்கைகள் · IMD warnings in the feed", lang),
                        color = p.onAccent, fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (feed.alerts.isEmpty()) {
                            "IMD has published nothing. That is not a promise the weather " +
                                "is fine — it means no warning has been issued."
                        } else {
                            "Open the Storm screen to see which of these cover where you are."
                        },
                        color = p.onAccent, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    Source(feed.source, feed.fetchedAt)
                }
            }

            // 4. POSITION — coordinates, in the format a rescuer wants.
            VoiceDemo.Intent.POSITION -> {
                val fix = SosDispatch.lastFix(context)
                if (fix == null) {
                    Missing("No GPS fix yet. ORCA will not read out a guessed position.")
                } else {
                    Text(
                        SosDispatch.formatPosition(fix.lat, fix.lon),
                        color = p.onAccent, fontSize = 30.sp, fontWeight = FontWeight.Black,
                        lineHeight = 38.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append(fix.provider.uppercase())
                            fix.accuracyM?.let { append("  ±").append(it.toInt()).append(" m") }
                            append(if (fix.ageMinutes <= 0) "  just now" else "  ${fix.ageMinutes} min old")
                        },
                        color = p.onAccent, fontSize = 14.sp,
                    )
                }
            }

            // 5. HELP — not information. An action.
            VoiceDemo.Intent.HELP -> {
                Text(
                    bi("உதவி கேட்கவா? · Send an SOS?", lang),
                    color = p.onAccent, fontSize = 22.sp, fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Speaking does NOT send it. A recogniser mishears, and a distress " +
                        "call sent by a misheard word spends a rescue somebody else needed. " +
                        "This opens the SOS screen; the send is still yours.",
                    color = p.onAccent, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    bi("SOS திரைக்குச் செல் · OPEN SOS", lang),
                    color = tint, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)).background(Color.White)
                        .clickable(onClick = onOpenSos).padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

/** Where the number came from. Shown on every answer that has one. */
@Composable
private fun Source(source: String, validTime: String) {
    val p = LocalPalette.current
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = p.onAccent.copy(alpha = 0.3f))
    Spacer(Modifier.height(8.dp))
    Text(
        "$source · $validTime",
        color = p.onAccent.copy(alpha = 0.85f), fontSize = 11.sp, lineHeight = 16.sp,
    )
}

/** No value. Said in words, with no number anywhere near it. */
@Composable
private fun Missing(why: String) {
    val p = LocalPalette.current
    Text("—", color = p.onAccent, fontSize = 56.sp, fontWeight = FontWeight.Black)
    Text(why, color = p.onAccent, fontSize = 14.sp, lineHeight = 20.sp)
}
