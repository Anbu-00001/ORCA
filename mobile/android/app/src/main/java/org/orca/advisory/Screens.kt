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
    val lang = LocalLang.current
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
            Text(actionWord(zone.action, lang), color = p.onAccent,
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

        Section(bi("ஏன்? · Why", lang)) { Text(zone.reason, color = p.ink, fontSize = 15.sp, lineHeight = 22.sp) }

        // Every reading with its source. CLAUDE.md rule 3 does not weaken
        // because the number is on a phone instead of a browser.
        Section(bi("அளவீடுகள் · Readings", lang)) {
            if (zone.readings.isEmpty()) {
                Text("No readings for this zone. That is not the same as safe conditions.",
                    color = p.caution, fontSize = 14.sp, lineHeight = 21.sp)
            } else {
                zone.readings.forEach { r -> ReadingRow(r) }
            }
        }
    }
}


@Composable
private fun ReadingRow(r: OrcaRepository.Reading) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(readableTamil(r.variable, lang), color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            // Knots and nautical miles, like every other screen. This row
            // still read "8.9 km/h" while the home card showed the SAME
            // reading as "4.8 kn" -- one number, two units, one screen
            // apart. Units.convertedValue returns null for anything that
            // must stay as published (wave height in metres), so nothing
            // is converted that should not be.
            val shown = Units.convertedValue(r.variable, r.value)
            Text(
                shown?.let { (v, u) -> "$v $u" } ?: "${trim(r.value)} ${r.unit}",
                color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
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

private fun readableTamil(variable: String, lang: Lang) = when (variable) {
    "wave_height_m" -> bi("அலை உயரம் · wave height", lang)
    "wave_period_s" -> bi("அலை கால அளவு · wave period", lang)
    "wind_speed_kmh" -> bi("காற்றின் வேகம் · wind", lang)
    "wind_gusts_kmh" -> bi("காற்று சுழற்சி · gusts", lang)
    "sst_c" -> bi("கடல் வெப்பநிலை · sea temp", lang)
    "ocean_current_velocity_kmh" -> bi("நீரோட்டம் · current", lang)
    "chlorophyll_mg_m3" -> bi("பச்சையம் · chlorophyll", lang)
    "rain_mm", "precipitation_mm" -> bi("மழை · rain", lang)
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
    val lang = LocalLang.current
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
        Text(str(S.T_FISH, lang), color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                        true -> bi("மீன் இருக்கலாம் · Likely", lang)
                        false -> bi("குறைவு · Unlikely", lang)
                        null -> bi("மேகம் · Cloud — not seen", lang)
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
    val lang = LocalLang.current
    val context = LocalContext.current
    var running by remember { mutableStateOf(BoundaryWatchService.isRunning()) }
    var note by remember { mutableStateOf<String?>(null) }
    val boundary = remember { repo.loadLocal()?.boundary }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                label = if (running) bi("நிறுத்து · Stop watching", lang) else bi("தொடங்கு · Start watching", lang),
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
            Text(bi("எச்சரிக்கை தூரம் · Warning distances", lang), color = p.ink,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BandRow(Units.distance(boundary.advisoryKm), bi("கவனம் · Be careful", lang), p.caution)
            BandRow(Units.distance(boundary.warningKm), bi("மேற்கு நோக்கித் திரும்பு · Turn west", lang), p.caution)
            BandRow(Units.distance(boundary.urgentKm), bi("இப்போதே திரும்பு · Turn back now", lang), p.deny)
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
    val lang = LocalLang.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(28.dp).background(tint))
        Text(distance, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp).width(72.dp))
        Text(meaning, color = p.muted, fontSize = 14.sp)
    }
}

// =====================================================================
// 4. SOS - one press, already sent.
// =====================================================================

/**
 * The distress screen.
 *
 * <p>ONE PRESS. The button does not open a messaging app, does not ask
 * "are you sure", and does not hand the crew a draft to address. It sends.
 * Everything a person has to do between deciding they need help and help
 * being asked for is a step that can go wrong on a pitching deck at night,
 * so there is exactly one.
 *
 * <p>The position in that message comes from the GNSS receiver or the
 * message says POSITION UNKNOWN. It is never taken from the advisory's
 * harbour coordinates -- see SosDispatch's class comment for the bug that
 * rule replaced.
 */
@Composable
fun SosScreen(
    advisory: OrcaRepository.Advisory?,
    selected: String?,
    onOpenSettings: () -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current

    var settings by remember { mutableStateOf(Settings.load(context)) }
    // Seeded from the last send, so arriving here after a home-screen
    // hold or a volume-key alarm shows the outcome instead of a blank page.
    var report by remember { mutableStateOf(SosDispatch.lastReport) }
    var update by remember { mutableStateOf<SosDispatch.Report?>(null) }

    // Re-read on every entry: the crew may have just set their number, or
    // just come back from Android's accessibility settings.
    LaunchedEffect(Unit) {
        settings = Settings.load(context)
        PanicStatus.onAccessibilityConnected(PanicKeyService.isEnabled(context))
    }

    // The nearest harbour is a NAME in the message, never a coordinate.
    val zoneHint = (advisory?.zones?.firstOrNull { it.zone == selected }
        ?: advisory?.zones?.firstOrNull())?.zone
    val fix = remember(report) { SosDispatch.lastFix(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        PanicWatchCard()

        // --- an SOS is counting down ------------------------------------
        // Rendered above everything, because while this is on screen it is
        // the only thing that matters. Doing nothing SENDS; the one action
        // available is to stop it.
        SosCountdown.secondsLeft?.let { left ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(p.deny).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(bi("அவசர அழைப்பு அனுப்பப்படுகிறது · SENDING SOS IN", lang),
                    color = p.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("$left", color = p.onAccent, fontSize = 64.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(
                    bi("நிறுத்து · CANCEL", lang),
                    color = p.deny, fontSize = 20.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .clickable { PanicService.cancel(context) }
                        .padding(vertical = 18.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(bi("எதுவும் செய்யாவிட்டால் அனுப்பப்படும் · Do nothing and it sends.", lang),
                    color = p.onAccent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        if (SosCountdown.lastCancelled && !SosCountdown.running) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(p.caution.copy(alpha = 0.16f)).padding(14.dp)) {
                Text(bi("நிறுத்தப்பட்டது · CANCELLED", lang), color = p.caution,
                    fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("You stopped it. Nothing was sent.",
                    color = p.ink, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        // --- what will actually be sent, before it is sent -------------
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.panel).padding(14.dp)) {
            Text(bi("இடம் · Your position", lang), color = p.muted,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (fix != null) {
                Text(SosDispatch.formatPosition(fix.lat, fix.lon), color = p.ink,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(fix.provider.uppercase())
                        fix.accuracyM?.let { append("  +-").append(it.toInt()).append(" m") }
                        append(if (fix.ageMinutes <= 0) "  just now" else "  ${fix.ageMinutes} min old")
                    },
                    color = if (fix.ageMinutes > SosDispatch.STALE_MINUTES) p.caution else p.muted,
                    fontSize = 12.sp,
                )
            } else {
                // Stated plainly. The alternative -- showing the harbour --
                // is the bug this screen was rebuilt to remove.
                Text(bi("இடம் தெரியவில்லை · Position unknown", lang), color = p.caution,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("No GPS fix yet. ORCA will send POSITION UNKNOWN rather than a guess, " +
                    "and will follow up the moment it gets a fix.",
                    color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(bi("யாருக்கு · Sends to", lang), color = p.muted,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                settings.contacts.joinToString(", ").ifBlank { "— nobody —" },
                color = if (settings.contacts.isEmpty()) p.deny else p.ink,
                fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (settings.contacts.isEmpty()) {
            // Nothing to send to. Said loudly and fixed in one tap, rather
            // than discovered at the moment it matters.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(p.deny.copy(alpha = 0.12f))
                    .clickable { onOpenSettings() }.padding(14.dp),
            ) {
                Text(bi("அவசர எண்ணை சேர்க்கவும் · Add an emergency number", lang),
                    color = p.deny, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("ORCA will not guess a number to send a distress call to. " +
                    "Tap here to set one — family, or your harbour.",
                    color = p.ink, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        // --- the button ------------------------------------------------
        BigButton(bi("இப்போதே அனுப்பு · SEND SOS NOW", lang), p.deny) {
            val r = SosDispatch.fire(context, settings.contacts, zoneHint,
                settings.boatId.takeIf { it.isNotBlank() })
            report = r
            update = null
            if (r.outcome == SosDispatch.Outcome.SENT) {
                // The light costs nothing and works with a dead network.
                runCatching { TorchSos.start(context) }
                SosDispatch.requestUpdate(
                    context, settings.contacts, zoneHint,
                    settings.boatId.takeIf { it.isNotBlank() }, r.fix,
                ) { update = it }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(bi("ஒரே அழுத்தத்தில் அனுப்பப்படும் · Sends immediately — no confirmation", lang),
            color = p.muted, fontSize = 12.sp)

        Spacer(Modifier.height(14.dp))

        // 1554 is a VOICE line, so this places a call. It used to open an
        // SMS to 1554, which most likely went nowhere at all.
        BigButton(bi("கடலோர காவல்படை 1554 அழை · Call Coast Guard 1554", lang), p.panel) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:${SosDispatch.COAST_GUARD}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                // Without CALL_PHONE granted, fall back to the dialler with
                // the number already in it. Still one tap from a call.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${SosDispatch.COAST_GUARD}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        // --- what happened ---------------------------------------------
        (update ?: report)?.let { r ->
            Spacer(Modifier.height(18.dp))
            val tint = when (r.outcome) {
                SosDispatch.Outcome.SENT -> p.go
                SosDispatch.Outcome.PARTIAL -> p.caution
                else -> p.deny
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f)).padding(14.dp)) {
                Text(
                    when (r.outcome) {
                        SosDispatch.Outcome.SENT -> bi("அனுப்பப்பட்டது · SENT", lang)
                        SosDispatch.Outcome.NO_CONTACT -> bi("எண் இல்லை · NO NUMBER SET", lang)
                        SosDispatch.Outcome.NO_PERMISSION -> bi("அனுமதி இல்லை · SMS NOT ALLOWED", lang)
                        SosDispatch.Outcome.FAILED -> bi("தோல்வி · NOT SENT", lang)
                        SosDispatch.Outcome.PARTIAL -> bi("பகுதியாக · SENT TO SOME", lang)
                    },
                    color = tint, fontSize = 18.sp, fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(6.dp))
                Text(r.detail, color = p.ink, fontSize = 13.sp, lineHeight = 19.sp)
                if (r.outcome == SosDispatch.Outcome.NO_PERMISSION) {
                    Spacer(Modifier.height(6.dp))
                    // The exact Android 15 sideload path, spelled out. A
                    // vague "check permissions" is useless in an emergency.
                    Text(
                        "Android blocks SMS for apps installed outside the Play Store until " +
                        "you allow it once: App info → ⋮ → Allow restricted settings, " +
                        "then Permissions → SMS → Allow.",
                        color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(r.message, color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            // The send starts the torch. Without this the crew has no way
            // to stop it from the screen that started it.
            if (TorchSos.running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    bi("விளக்கை நிறுத்து · SOS light is flashing — STOP IT", lang),
                    color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.caution)
                        .clickable { TorchSos.stop(context) }
                        .padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            if (update != null) {
                Spacer(Modifier.height(8.dp))
                Text(bi("புதிய இடம் அனுப்பப்பட்டது · A fresher position was sent after the first message.", lang),
                    color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "SMS reaches much further out to sea than mobile data. " +
            "ORCA is not a substitute for a distress beacon or VHF channel 16.",
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
    val lang = LocalLang.current
    // Only zones ORCA is actually warning about. Forwarding a GO as an
    // "alert" would train people to ignore the real ones.
    val risky = advisory?.zones?.filter { it.action != "GO" } ?: emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Named apart from the STORM screen on purpose: that one is IMD's
        // warning about the weather, this one forwards ORCA's own verdict
        // to a crew who cannot get it. Two cards both reading "storm" was
        // one feature as far as anyone tapping could tell.
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
    val lang = LocalLang.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(str(S.T_ASK, lang), color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
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
    val lang = LocalLang.current
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
    val lang = LocalLang.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = p.line)
}

@Composable
fun EmptyState(title: String, detail: String) {
    // `title` is Tamil at every call site; the detail beneath it is
    // English. In English or Hindi the Tamil headline is noise, so it is
    // dropped rather than shown to someone who cannot read it.
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lang == Lang.TA) {
            Text(title, color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
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
    val lang = LocalLang.current
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
                Text(seaBand(e.heightM, lang), color = p.accent,
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
        Text(bi("இது எப்படி வேலை செய்கிறது · How this works", lang), color = p.accent,
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
private fun seaBand(m: Double, lang: Lang) = when {
    m < 0.5 -> bi("அமைதி · Calm", lang)
    m < 1.25 -> bi("லேசான அலை · Slight", lang)
    m < 2.5 -> bi("மிதமான அலை · Moderate", lang)
    else -> bi("கடும் அலை · Rough", lang)
}

// =====================================================================
// 8. FLEET — boat-to-boat relay.
// =====================================================================

@Composable
fun FleetScreen(advisory: OrcaRepository.Advisory?) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current
    val relay = remember { FleetRelay(context) }
    var on by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { relay.stop() } }

    val ourAge = advisory?.readingAgeMinutes()?.toInt()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                label = if (on) bi("நிறுத்து · Stop sharing", lang) else bi("தொடங்கு · Start sharing", lang),
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
        Text(bi("பாதுகாப்பு · Is this safe?", lang), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    val lang = LocalLang.current
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
            "What happens: the phone vibrates hard, speaks in Tamil, starts the SOS " +
                "light, and counts down ${SosCountdown.SECONDS} seconds on your lock " +
                "screen. Touch CANCEL in that window and nothing is sent. Do nothing " +
                "and your position goes to every number in your list.",
            color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        // --- the screen-off trigger --------------------------------------
        // Given top billing because it is the ONLY one that works with the
        // display asleep, which is the state a phone in a pocket is in.
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(p.go.copy(alpha = 0.14f)).padding(14.dp),
        ) {
            Text(
                bi("பவர் பொத்தானை ${PowerPressDetector.PRESSES} முறை அழுத்தவும் · PRESS POWER ${PowerPressDetector.PRESSES} TIMES", lang),
                color = p.go, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Works with the screen off, the phone locked and ORCA closed — " +
                    "in a pocket, with wet hands, in the dark. Five quick presses, " +
                    "then ${SosCountdown.SECONDS} seconds to cancel if it was a mistake.",
                color = p.ink, fontSize = 13.sp, lineHeight = 19.sp,
            )
            if (PanicStatus.powerProgress > 0f) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "counting: ${(PanicStatus.powerProgress * PowerPressDetector.PRESSES).toInt()}/${PowerPressDetector.PRESSES}",
                    color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }

        // --- the setup that actually makes the volume key work -----------
        // Led with, because without it the volume key does nothing at all
        // when the phone is locked -- which is the only state that matters.
        if (!PanicStatus.accessibilityOn) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(p.caution.copy(alpha = 0.16f))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(14.dp),
            ) {
                Text(
                    bi("ஒரு முறை அமைக்கவும் · ONE-TIME SETUP NEEDED", lang),
                    color = p.caution, fontSize = 16.sp, fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Optional. This adds the volume-down hold, which works while the " +
                        "screen is ON. Android will not give any app the volume key once " +
                        "the display sleeps — that is what the power-button trigger above " +
                        "is for. Switching this on must be done by hand.",
                    color = p.ink, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "TAP HERE → Installed apps → ORCA → turn on",
                    color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                bi("✓ ஒலி பொத்தான் தயார் · Volume key is live, even with the phone locked", lang),
                color = p.go, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp,
            )
        }

        // --- live proof, or live disproof --------------------------------
        // The only instrument for a feature no script can test. Press the
        // key once: if this counter does not move, the events are not
        // reaching ORCA on this handset, and that is a real answer.
        if (PanicStatus.armed || PanicStatus.accessibilityOn) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(p.hull).padding(12.dp),
            ) {
                Text("VOLUME KEYS SEEN", color = p.muted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${PanicStatus.keyEvents}",
                        color = if (PanicStatus.keyEvents > 0) p.go else p.deny,
                        fontSize = 40.sp, fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (PanicStatus.keyEvents == 0) {
                            "press volume-down once — this must move"
                        } else {
                            "via ${PanicStatus.lastPath}"
                        },
                        color = p.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (PanicStatus.progress > 0f) {
                    Text(
                        "hold ${(PanicStatus.progress * 100).toInt()}% complete",
                        color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (PanicStatus.accessibilityOn) {
                        "Reading the key directly, so a 5-second hold is measured exactly."
                    } else {
                        "Accessibility is OFF — with the phone locked this counter will " +
                            "stay at zero no matter how long you hold."
                    },
                    color = if (PanicStatus.accessibilityOn) p.muted else p.caution,
                    fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Limits, honestly: if another app is playing media AND your volume is " +
                "already at zero, Android gives ORCA no key events and the hold will " +
                "not be seen. And an APK installed by tapping the file may need SMS " +
                "allowed once under App info → Allow restricted settings.",
            color = p.caution, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}
