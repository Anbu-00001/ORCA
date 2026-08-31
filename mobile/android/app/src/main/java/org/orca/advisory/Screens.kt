package org.orca.advisory

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.orca.advisory.ui.LocalPalette
import org.orca.advisory.ui.colorForAction

// =====================================================================
// 1. VERDICT — "Can I go out today?"
// =====================================================================

@Composable
fun VerdictScreen(
    advisory: OrcaRepository.Advisory?,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val p = LocalPalette.current
    val zone = advisory?.zones?.firstOrNull { it.zone == selected } ?: advisory?.zones?.firstOrNull()
    if (advisory == null || zone == null) {
        EmptyState("No advisory stored", "Connect once in harbour to download one.")
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Harbour picker. Horizontal chips rather than a dropdown: a
        // dropdown on a moving deck is a target you miss.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            advisory.zones.forEach { z ->
                val on = z.zone == zone.zone
                Text(
                    z.zone,
                    color = if (on) p.onAccent else p.ink,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (on) p.accent else p.panel)
                        .clickable { onSelect(z.zone) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        // THE VERDICT. Nothing competes with it on this screen.
        // policy.py decided it; this only paints it.
        Column(
            Modifier.fillMaxWidth().background(p.colorForAction(zone.action))
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Text(actionTamil(zone.action), color = p.onAccent,
                fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
            Text(zone.action, color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(zone.zone, color = p.onAccent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            if (zone.alternative != null) {
                Spacer(Modifier.height(8.dp))
                Text("→ ${zone.alternative}-க்குப் போகலாம்  ·  Go to ${zone.alternative} instead",
                    color = p.onAccent, fontSize = 16.sp, lineHeight = 22.sp)
            }
        }

        Section("ஏன்? · Why") { Text(zone.reason, color = p.ink, fontSize = 15.sp, lineHeight = 22.sp) }

        // Every reading with its source. CLAUDE.md rule 3 does not weaken
        // because the number is on a phone instead of a browser.
        Section("அளவீடுகள் · Readings") {
            if (zone.readings.isEmpty()) {
                Text("No readings for this zone. That is not the same as safe conditions.",
                    color = p.caution, fontSize = 14.sp, lineHeight = 21.sp)
            } else {
                zone.readings.forEach { r -> ReadingRow(r) }
            }
        }
    }
}

private fun actionTamil(action: String) = when (action) {
    "GO" -> "போகலாம்"
    "DO NOT GO" -> "போக வேண்டாம்"
    "SAFER ALTERNATIVE" -> "வேறு இடம் பாதுகாப்பானது"
    "CANNOT ASSESS" -> "சொல்ல முடியவில்லை"
    else -> "தெரியவில்லை"        // never renders as permission
}

@Composable
private fun ReadingRow(r: OrcaRepository.Reading) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(readableTamil(r.variable), color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${trim(r.value)} ${r.unit}", color = p.ink,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text("source: ${r.source}", color = p.muted, fontSize = 12.sp)
            Text("valid: ${r.validTime}", color = p.muted, fontSize = 12.sp)
            Text("confidence: ${r.confidence}", color = p.muted, fontSize = 12.sp)
            Text("id: ${r.id}", color = p.muted, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = p.line)
}

private fun readableTamil(variable: String) = when (variable) {
    "wave_height_m" -> "அலை உயரம் · wave height"
    "wave_period_s" -> "அலை கால அளவு · wave period"
    "wind_speed_kmh" -> "காற்றின் வேகம் · wind"
    "wind_gusts_kmh" -> "காற்று சுழற்சி · gusts"
    "sst_c" -> "கடல் வெப்பநிலை · sea temp"
    "ocean_current_velocity_kmh" -> "நீரோட்டம் · current"
    "chlorophyll_mg_m3" -> "பச்சையம் · chlorophyll"
    "rain_mm", "precipitation_mm" -> "மழை · rain"
    else -> variable
}

private fun trim(v: Double): String {
    val s = String.format("%.2f", v).trimEnd('0').trimEnd('.')
    return if (s.isEmpty() || s == "-") "0" else s
}

// =====================================================================
// 2. FISH — Potential Fishing Zones. SIH26176's first example query.
// =====================================================================

@Composable
fun FishScreen(repo: OrcaRepository) {
    val p = LocalPalette.current
    var entries by remember { mutableStateOf<List<OrcaRepository.PfzEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try { entries = repo.fetchPfz() } catch (e: Exception) {
            // A PFZ is a statement about TODAY's satellite pass. A cached
            // one is worse than none, so there is deliberately no offline
            // fallback here -- unlike the safety verdict, which always has
            // one.
            error = "Needs a connection. A fishing zone is about today's satellite pass, " +
                    "so ORCA will not show you a stored one."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("மீன் எங்கே இருக்கும்?", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Where conditions favour fish — chlorophyll and sea temperature from satellite",
            color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(16.dp))

        when {
            error != null -> Text(error!!, color = p.caution, fontSize = 15.sp, lineHeight = 22.sp)
            entries == null -> Text("Loading…", color = p.muted, fontSize = 15.sp)
            else -> {
                entries!!.forEach { e ->
                    val tint = when (e.productive) {
                        true -> p.go
                        false -> p.muted
                        null -> p.unknown     // unseen, NOT unproductive
                    }
                    val label = when (e.productive) {
                        true -> "மீன் இருக்கலாம் · Likely"
                        false -> "குறைவு · Unlikely"
                        null -> "மேகம் · Cloud — not seen"
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Box(Modifier.width(6.dp).height(56.dp).background(tint))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(e.zone, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(e.why, color = p.muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                    HorizontalDivider(color = p.line)
                }
                Spacer(Modifier.height(14.dp))
                // INCOIS's own PFZ advisories carry this limitation. Saying
                // so is the honest thing, not a hedge.
                Text(
                    "ORCA shows where conditions favour fish gathering. It cannot tell you " +
                    "how many fish are there, and a zone can move with the current.",
                    color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

// =====================================================================
// 3. BOUNDARY — the feature no web app can have.
// =====================================================================

@Composable
fun BoundaryScreen(repo: OrcaRepository, ensureLocation: () -> Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var running by remember { mutableStateOf(BoundaryWatchService.isRunning()) }
    var note by remember { mutableStateOf<String?>(null) }
    val boundary = remember { repo.loadLocal()?.boundary }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("கடல் எல்லை எச்சரிக்கை", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Sea boundary warning", color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "The India–Sri Lanka maritime boundary is invisible and unmarked. " +
            "Turn this on and ORCA watches your position against it and warns you " +
            "aloud in Tamil — even with the app closed and the screen off.",
            color = p.ink, fontSize = 15.sp, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(18.dp))

        if (boundary == null) {
            Text("No boundary map stored yet. Connect once to download it.",
                color = p.caution, fontSize = 14.sp, lineHeight = 21.sp)
        } else {
            BigButton(
                label = if (running) "நிறுத்து · Stop watching" else "தொடங்கு · Start watching",
                tint = if (running) p.deny else p.go,
            ) {
                if (running) {
                    context.stopService(Intent(context, BoundaryWatchService::class.java))
                    running = false
                    note = "Watch stopped."
                } else if (!ensureLocation()) {
                    note = "ORCA needs location permission to watch the boundary."
                } else {
                    val json = repo.boundaryJson()
                    if (json == null) {
                        note = "No boundary geometry stored."
                    } else {
                        context.startForegroundService(
                            Intent(context, BoundaryWatchService::class.java).apply {
                                putExtra(BoundaryWatchService.EXTRA_BOUNDARY, json)
                                putExtra(BoundaryWatchService.EXTRA_LANG, "ta")
                            })
                        running = true
                        note = "Watching. You can close the app — it keeps warning you."
                    }
                }
            }
            note?.let { Spacer(Modifier.height(12.dp)); Text(it, color = p.muted, fontSize = 14.sp, lineHeight = 21.sp) }

            Spacer(Modifier.height(22.dp))
            Text("எச்சரிக்கை தூரம் · Warning distances", color = p.ink,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BandRow("${trim(boundary.advisoryKm)} km", "கவனம் · Be careful", p.caution)
            BandRow("${trim(boundary.warningKm)} km", "மேற்கு நோக்கித் திரும்பு · Turn west", p.caution)
            BandRow("${trim(boundary.urgentKm)} km", "இப்போதே திரும்பு · Turn back now", p.deny)
            Spacer(Modifier.height(14.dp))
            // These distances are the SERVER's, read out of orca/agents.py.
            // The app owning its own copy would be a second safety
            // threshold that can silently disagree with the advisory.
            Text("Boundary: ${boundary.source}", color = p.muted, fontSize = 11.sp, lineHeight = 16.sp)
            Text("Warning distances come from ORCA's server, not from this app.",
                color = p.muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BandRow(distance: String, meaning: String, tint: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(28.dp).background(tint))
        Text(distance, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp).width(72.dp))
        Text(meaning, color = p.muted, fontSize = 14.sp)
    }
}

// =====================================================================
// 4. SOS — position by SMS. Reaches where mobile data does not.
// =====================================================================

@Composable
fun SosScreen(
    advisory: OrcaRepository.Advisory?,
    selected: String?,
    onSms: (String, String) -> Unit,
) {
    val p = LocalPalette.current
    val zone = advisory?.zones?.firstOrNull { it.zone == selected } ?: advisory?.zones?.firstOrNull()
    // Position first: whoever reads this on shore needs coordinates before
    // anything else, and an SMS can be truncated.
    val message = buildString {
        append("ORCA EMERGENCY. ")
        if (zone != null) append("Position ${zone.lat}, ${zone.lon} (near ${zone.zone}). ")
        append("Need help. Sent from the ORCA app.")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        // --- the volume-key watch --------------------------------------
        PanicWatchCard()
        Text("அவசர உதவி", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Send my position for help", color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "SMS reaches much further out to sea than mobile data. This opens your " +
            "messaging app with the message already written — you choose who to send it to, " +
            "and nothing is sent until you press send.",
            color = p.ink, fontSize = 15.sp, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.panel).padding(14.dp)) {
            Text(message, color = p.ink, fontSize = 14.sp, lineHeight = 21.sp)
        }
        Spacer(Modifier.height(18.dp))
        BigButton("உதவி கேள் · Send for help", p.deny) { onSms("", message) }
        Spacer(Modifier.height(14.dp))
        BigButton("கடலோர காவல்படை 1554 · Coast Guard 1554", p.panel) { onSms("1554", message) }
        Spacer(Modifier.height(12.dp))
        Text(
            "1554 is the Indian Coast Guard's toll-free maritime distress number. " +
            "ORCA is not a substitute for a distress beacon.",
            color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}

// =====================================================================
// 5. ALERTS — pass a warning on to boats that have no app.
// =====================================================================

@Composable
fun AlertsScreen(advisory: OrcaRepository.Advisory?, onSms: (String, String) -> Unit) {
    val p = LocalPalette.current
    // Only zones ORCA is actually warning about. Forwarding a GO as an
    // "alert" would train people to ignore the real ones.
    val risky = advisory?.zones?.filter { it.action != "GO" } ?: emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Named apart from the STORM screen on purpose: that one is IMD's
        // warning about the weather, this one forwards ORCA's own verdict
        // to a crew who cannot get it. Two cards both reading "storm" was
        // one feature as far as anyone tapping could tell.
        Text("பிற படகுகளுக்குச் சொல்", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Pass ORCA's verdict to another boat", color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Most boats do not have this app, but every boat has SMS. " +
            "Send ORCA's warning to a crew who cannot get it themselves.",
            color = p.ink, fontSize = 15.sp, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(18.dp))

        if (risky.isEmpty()) {
            Text("ORCA is not warning about any harbour right now.",
                color = p.muted, fontSize = 15.sp, lineHeight = 22.sp)
        } else {
            risky.forEach { z ->
                val text = "ORCA: ${z.zone} — ${z.action}. ${z.reason}"
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(p.panel)
                        .clickable { onSms("", text) }.padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(6.dp).height(22.dp).background(p.colorForAction(z.action)))
                        Text(z.zone, color = p.ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                        Spacer(Modifier.weight(1f))
                        Text("SMS →", color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(z.reason, color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

// =====================================================================
// 6. ASK — Tamil voice input.
// =====================================================================

@Composable
fun AskScreen(onListen: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("தமிழில் கேளுங்கள்", color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Say the name of a harbour", color = p.muted, fontSize = 15.sp)
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(168.dp).clip(RoundedCornerShape(84.dp))
                .background(p.accent).clickable(onClick = onListen),
            contentAlignment = Alignment.Center,
        ) {
            Text("பேசுங்கள்", color = p.onAccent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "\"மண்டபத்தில் பாதுகாப்பானதா?\"\n\"நாகப்பட்டினம்\"",
            color = p.muted, fontSize = 15.sp, lineHeight = 24.sp,
        )
    }
}

// =====================================================================
// shared
// =====================================================================

@Composable
fun BigButton(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clip(RoundedCornerShape(6.dp)).background(tint)
            .clickable(onClick = onClick).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (tint == p.panel) p.ink else p.onAccent,
            fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = p.line)
}

@Composable
fun EmptyState(title: String, detail: String) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = p.muted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** Tamil harbour names, for matching a spoken question. Stems, because
 *  Tamil is agglutinative: மண்டபம் becomes மண்டபத்தில். Mirrors
 *  orca/phrase_ta.py's ZONE_STEMS_TA. */
object TamilNames {
    private val stems = mapOf(
        "சென்னை" to "Chennai", "கடலூர" to "Cuddalore", "காரைக்கா" to "Karaikal",
        "நாகப்பட்டின" to "Nagapattinam", "கோடியக்கரை" to "Point Calimere",
        "மண்டப" to "Mandapam", "ராமேஸ்வர" to "Rameswaram", "இராமேஸ்வர" to "Rameswaram",
        "தூத்துக்குடி" to "Thoothukudi", "கன்னியாகுமரி" to "Kanyakumari", "கொளச்ச" to "Colachel",
    )
    fun stemFor(spoken: String): String? =
        stems.entries.sortedByDescending { it.key.length }
            .firstOrNull { spoken.contains(it.key) }?.value
}

// =====================================================================
// 7. WAVE — the phone as a wave buoy.
// =====================================================================

@Composable
fun WaveScreen() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val sensor = remember { WaveSensor(context) }
    var estimate by remember { mutableStateOf<WaveSensor.Estimate?>(null) }
    var running by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        running = sensor.start()
        onDispose { sensor.stop() }
    }
    // Re-read once a second. The estimator itself keeps a 60 s window, so
    // the number settles rather than jumping.
    LaunchedEffect(running) {
        while (running) {
            estimate = sensor.estimate()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("அலை அளவு", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("The sea, sensed from your own boat", color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        if (!sensor.available) {
            Text("This phone has no motion sensor, so ORCA cannot sense the sea here.",
                color = p.caution, fontSize = 15.sp, lineHeight = 22.sp)
            return@Column
        }

        val e = estimate
        when {
            e == null -> Text("Collecting motion… keep the phone flat on the boat.",
                color = p.muted, fontSize = 15.sp, lineHeight = 22.sp)
            e.confidence == 0.0 -> Text(e.note, color = p.caution, fontSize = 15.sp, lineHeight = 22.sp)
            else -> {
                // A BAND, not a decimal.
                //
                // The headline used to be "6.2" in 64sp. A phone on a desk
                // produced exactly that, and a number that precise implies
                // an accuracy a zero-crossing estimate on consumer
                // hardware does not have. This module's own docstring says
                // it is "good enough to say the motion is closer to 2 m
                // than 0.5 m, and not good enough to put a decimal on" --
                // so the band is the headline and the number is secondary.
                Text(seaBand(e.heightM), color = p.accent,
                    fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
                Text("roughly ${String.format("%.1f", e.heightM)} m  ·  " +
                     "period ${String.format("%.1f", e.periodS)} s  ·  ${e.windowS.toInt()} s of motion",
                    color = p.muted, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                // The honesty is the feature. This is a proxy on consumer
                // hardware, and it must never look like a buoy reading.
                Text(e.note, color = p.ink, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = p.line)
        Spacer(Modifier.height(16.dp))
        Text("இது எப்படி வேலை செய்கிறது · How this works", color = p.accent,
            fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your phone already has the same kind of motion sensor a wave buoy uses. " +
            "ORCA watches how the boat rises and falls and estimates the sea state where " +
            "you are — with no extra device to buy.\n\n" +
            "This is an ESTIMATE, not a measurement, and it never changes ORCA's advice. " +
            "The safety verdict always comes from the satellite and forecast data on shore.",
            color = p.muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}

/** Douglas-scale wording for an estimate that cannot support a decimal.
 *  The boundaries are the Douglas sea-state degrees ORCA already uses on
 *  the web client's ruler, so the phone and the shore describe the same
 *  sea in the same words. */
private fun seaBand(m: Double) = when {
    m < 0.5 -> "அமைதி · Calm"
    m < 1.25 -> "லேசான அலை · Slight"
    m < 2.5 -> "மிதமான அலை · Moderate"
    else -> "கடும் அலை · Rough"
}

// =====================================================================
// 8. FLEET — boat-to-boat relay.
// =====================================================================

@Composable
fun FleetScreen(advisory: OrcaRepository.Advisory?) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val relay = remember { FleetRelay(context) }
    var on by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { relay.stop() } }

    val ourAge = advisory?.readingAgeMinutes()?.toInt()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("படகுகள் இணைப்பு", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Share the advisory with nearby boats", color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Mobile signal stops about 8 NM (15 km) from shore. But boats meet each other out " +
            "there. Turn this on and your phone quietly offers what it is carrying to any " +
            "nearby boat running ORCA — and takes theirs if it is newer.\n\n" +
            "No tower. No satellite. No extra device.",
            color = p.ink, fontSize = 15.sp, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(18.dp))

        if (!relay.available) {
            Text("Bluetooth is off, so ORCA cannot see nearby boats. Turn it on to share.",
                color = p.caution, fontSize = 15.sp, lineHeight = 22.sp)
        } else {
            BigButton(
                label = if (on) "நிறுத்து · Stop sharing" else "தொடங்கு · Start sharing",
                tint = if (on) p.deny else p.go,
            ) {
                if (on) { relay.stop(); on = false; note = "Sharing stopped." }
                else {
                    val manifest = FleetRelay.Manifest(
                        ageMinutes = ourAge ?: 9999,
                        zoneCount = advisory?.zones?.size ?: 0,
                        hops = 0,
                    )
                    val adv = relay.startAdvertising(manifest)
                    val scan = relay.startScanning(ourAge) { _, _ -> seen += 1 }
                    on = adv || scan
                    note = if (on) "Sharing. Your phone is visible to nearby boats."
                           else "Could not start — check Bluetooth permissions."
                }
            }
            note?.let { Spacer(Modifier.height(12.dp)); Text(it, color = p.muted, fontSize = 14.sp) }
            if (on) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (seen == 0) "No other ORCA boats nearby yet."
                    else "$seen nearby boat(s) had a newer advisory.",
                    color = p.accent, fontSize = 15.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = p.line)
        Spacer(Modifier.height(16.dp))
        Text("பாதுகாப்பு · Is this safe?", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        // Nothing is trusted because it arrived. Saying so plainly is part
        // of the design, not a disclaimer.
        Text(
            "An advisory from another boat is only taken if it is genuinely newer than " +
            "yours, and it is always shown with its age and where it came from. " +
            "ORCA never re-decides anything on the phone — the safety verdict inside a " +
            "shared advisory is the same one the shore system issued.",
            color = p.muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
    }
}


// =====================================================================
// The volume-key panic watch, armed from the SOS screen.
// =====================================================================

/**
 * Arm or disarm the five-second volume-key hold.
 *
 * <p>Opt-in on purpose. It runs a foreground service with a permanent
 * notification, and a crew that did not ask for it would reasonably read
 * that as the app misbehaving. It also has real limits (below), and a
 * safety feature nobody switched on knowingly is a safety feature nobody
 * can rely on.
 */
@Composable
fun PanicWatchCard() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var on by remember { mutableStateOf(PanicService.running) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) p.deny.copy(alpha = 0.10f) else p.panel)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ஒலி பொத்தான் அவசர அழைப்பு",
                    color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Volume-key SOS — works with the app closed",
                    color = p.muted, fontSize = 13.sp,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    if (it) PanicService.start(context) else PanicService.stop(context)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = p.onAccent,
                    checkedTrackColor = p.deny,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (on)
                "ஒலி குறைப்பு பொத்தானை 5 விநாடி அழுத்திப் பிடியுங்கள். திரை அணைந்திருந்தாலும், செயலி மூடியிருந்தாலும் வேலை செய்யும்."
            else
                "இதை இயக்கினால், ஒலி பொத்தானை 5 விநாடி பிடித்தாலே அவசர அழைப்பு தொடங்கும்.",
            color = p.ink, fontSize = 14.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (on)
                "Hold volume-down for 5 seconds. Works with the screen off and the app closed. " +
                    "Volume-UP cancels a hold in progress."
            else
                "Turn this on and holding volume-down for 5 seconds raises an SOS without " +
                    "unlocking the phone or finding this screen.",
            color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "What it does NOT do: it cannot send an SMS by itself — Android blocks that " +
                "for a sideloaded app, and a message sent by a pocket would send a rescue " +
                "to empty sea. It vibrates hard, speaks in Tamil, starts the SOS light and " +
                "puts the message one tap from sending, over your lock screen.",
            color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Limit: if another app is playing media AND your volume is already at zero, " +
                "Android gives ORCA no key events and the hold will not be seen.",
            color = p.caution, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}
