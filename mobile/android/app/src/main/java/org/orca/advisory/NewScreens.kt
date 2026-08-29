package org.orca.advisory

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.orca.advisory.ui.LocalPalette
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// =====================================================================
// 9. STORM — IMD's own warnings, tested against THIS boat's position.
// =====================================================================

/**
 * India Meteorological Department warnings, matched offline.
 *
 * <p>The screen exists because of the single most-quoted failure in the
 * research: Ockhi survivors who had been at sea six days said "nobody told
 * us there was going to be a storm. There is no system." The gap is not
 * that IMD does not warn -- it does, continuously, in a signed public
 * feed. The gap is that the warning never reaches a boat with no signal.
 *
 * <p>So the warnings ride out in the bundle, polygons and all, and this
 * screen runs the containment test against the phone's own GPS fix. No
 * network, no server, no pre-matching to a zone centroid the boat is not
 * standing on.
 *
 * <p>THE THING THIS SCREEN MUST NEVER DO is turn "we have not checked"
 * into "you are fine". Those get different colours, different words, and
 * different Tamil.
 */
@Composable
fun StormScreen(advisory: OrcaRepository.Advisory?, onEnsureLocation: () -> Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var fix by remember { mutableStateOf<Location?>(null) }
    var fixError by remember { mutableStateOf<String?>(null) }
    var usingZone by remember { mutableStateOf<OrcaRepository.ZoneAdvisory?>(null) }

    LaunchedEffect(Unit) {
        if (onEnsureLocation()) {
            val result = lastKnownFix(context)
            fix = result
            if (result == null) fixError = "No GPS fix yet."
        } else {
            fixError = "Location permission not granted."
        }
        // Falling back to the first zone is honest ONLY because the screen
        // says which position it used, every time.
        usingZone = advisory?.zones?.firstOrNull()
    }

    val lat = fix?.latitude ?: usingZone?.lat
    val lon = fix?.longitude ?: usingZone?.lon

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (lat == null || lon == null) {
            EmptyState(
                "இடம் தெரியவில்லை",
                "ORCA does not know where this phone is, and a storm warning " +
                    "is only meaningful against a position. Allow location, or " +
                    "download an advisory so a harbour position is available.",
            )
            return@Column
        }

        val match = StormAlerts.match(lat, lon, advisory?.alerts, Instant.now())

        // --- the headline ------------------------------------------------
        when {
            !match.checked -> Headline(
                tamil = "சரிபார்க்கப்படவில்லை",
                english = "NOT CHECKED",
                detail = match.reason ?: "",
                tint = p.unknown,
            )
            match.covering.isNotEmpty() -> Headline(
                tamil = StormAlerts.severityTamil(match.worstSeverity ?: "Unknown"),
                english = "IMD WARNING OVER YOU",
                detail = "${match.covering.size} active warning" +
                    (if (match.covering.size == 1) "" else "s") +
                    " from the India Meteorological Department covers this position.",
                tint = if (match.worstSeverity == "Extreme") p.deny else p.caution,
            )
            else -> Headline(
                tamil = "இப்போது எச்சரிக்கை இல்லை",
                english = "NO IMD WARNING OVER THIS POSITION",
                detail = "IMD has published nothing covering where you are. " +
                    "That is not a promise the weather is fine — it means no " +
                    "warning has been issued for here.",
                tint = p.go,
            )
        }

        PositionRow(lat, lon, fix != null, usingZone?.zone, fixError)

        // --- warnings over the boat --------------------------------------
        if (match.covering.isNotEmpty()) {
            Section("இங்கே / OVER YOU") {
                match.covering.forEach { AlertCard(it, covering = true) }
            }
        }

        // --- warnings IMD could not place --------------------------------
        if (match.ungeolocated.isNotEmpty()) {
            Section("இடம் குறிப்பிடப்படவில்லை / AREA NOT GIVEN") {
                Text(
                    "IMD issued these without a map outline, so ORCA cannot tell " +
                        "whether they cover you. Read them yourself.",
                    color = p.muted, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                match.ungeolocated.forEach { AlertCard(it, covering = false) }
            }
        }

        // --- everything else, so the screen never looks broken -----------
        if (match.checked && match.elsewhere.isNotEmpty()) {
            Section("வேறு இடங்களில் / ELSEWHERE IN INDIA") {
                match.elsewhere.take(5).forEach { AlertCard(it, covering = false) }
            }
        }

        if (match.checked) {
            Section("எங்கிருந்து / SOURCE") {
                Text(match.source ?: "", color = p.ink, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Fetched ${humanTime(match.fetchedAt)}. Every alert is an OASIS " +
                        "CAP v1.2 document signed by the alert hub — ORCA transports " +
                        "them and tests their outlines, and writes none of them.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
private fun AlertCard(alert: StormAlerts.Alert, covering: Boolean) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(covering) }
    val tint = when (alert.severity) {
        "Extreme" -> p.deny
        "Severe" -> p.caution
        else -> p.accent
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp)).background(p.panel)
            .clickable { expanded = !expanded }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(tint))
            Spacer(Modifier.width(10.dp))
            Text(
                alert.severity.uppercase(), color = tint,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (!covering && alert.polygon != null) {
                // Approximate on purpose: nearest VERTEX, which over-states
                // the distance. Safe direction to be wrong in.
                Text("~${alert.distanceKm.toInt()} km away",
                    color = p.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(alert.headline, color = p.ink, fontSize = 16.sp,
            fontWeight = FontWeight.Bold, lineHeight = 22.sp)
        if (alert.areaDesc.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(alert.areaDesc, color = p.accent, fontSize = 13.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (alert.description.isNotEmpty()) {
                Text(readable(alert.description), color = p.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (alert.instruction.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("செய்ய வேண்டியது / DO", color = p.accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(readable(alert.instruction), color = p.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append(alert.senderName)
                    if (alert.expires != null) append(" · until ${humanTime(alert.expires)}")
                    append(if (alert.signed) " · signed" else " · UNSIGNED")
                },
                color = p.muted, fontSize = 12.sp,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text("tap for detail", color = p.muted, fontSize = 12.sp)
        }
    }
}

// =====================================================================
// 10. DRIFT — where you end up when the engine will not start.
// =====================================================================

/**
 * The engine-failure drift box.
 *
 * <p>An engine that will not restart is the commonest way a small boat
 * turns into a search. The crew's problem at that moment is not that they
 * are lost -- the GPS still works -- it is that by the time anyone comes
 * looking they will not be where they said they were.
 *
 * <p>This screen answers the one question that closes that gap: where will
 * we be in six hours? It runs the Leeway model (see DriftModel) on wind
 * and current the phone already carries, with no signal, and produces a
 * position and a search box that can be read down a VHF or sent as an SMS
 * while there is still a bar of signal left.
 *
 * <p>It is an aid to telling someone where to look. It is not a rescue,
 * and every horizon carries the sentence saying how much to trust it.
 */
@Composable
fun DriftScreen(
    advisory: OrcaRepository.Advisory?,
    onEnsureLocation: () -> Boolean,
    onSms: (String, String) -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var fix by remember { mutableStateOf<Location?>(null) }
    var hours by remember { mutableDoubleStateOf(6.0) }

    LaunchedEffect(Unit) { if (onEnsureLocation()) fix = lastKnownFix(context) }

    val inputs = advisory?.driftInputs
    if (inputs.isNullOrEmpty()) {
        EmptyState(
            "தரவு இல்லை",
            "This phone has no wind or current readings stored. Download an " +
                "advisory in harbour — drift cannot be worked out without them, " +
                "and ORCA will not invent them.",
        )
        return
    }

    // Use the zone nearest the fix; without a fix, the first zone. Which
    // one was used is stated on screen either way.
    val lat = fix?.latitude
    val lon = fix?.longitude
    val zone = if (lat != null && lon != null) {
        inputs.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) }!!
    } else inputs.first()

    val originLat = lat ?: zone.lat
    val originLon = lon ?: zone.lon

    val result = DriftModel.forecast(
        originLat, originLon,
        zone.windSpeedKmh, zone.windDirectionDeg,
        zone.currentSpeedKmh, zone.currentDirectionDeg,
        hours,
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!result.ok) {
            Headline(
                tamil = "கணக்கிட முடியவில்லை",
                english = "CANNOT WORK THIS OUT",
                detail = result.reason ?: "",
                tint = p.unknown,
            )
            Section("ஏன் / WHY") {
                Text(
                    "Missing: ${result.missing.joinToString(", ")}. A drift box " +
                        "built on an assumed wind direction is a made-up position, " +
                        "and this one gets read to the Coast Guard.",
                    color = p.ink, fontSize = 15.sp, lineHeight = 21.sp,
                )
            }
            return@Column
        }

        Headline(
            tamil = "${DriftModel.compassTamil(result.bearingDeg)} நோக்கி " +
                "${fmt(result.distanceKm)} கி.மீ",
            english = "${fmt(result.distanceKm)} km toward " +
                "${DriftModel.compass(result.bearingDeg)} in ${hours.toInt()} h",
            detail = result.confidenceNote,
            tint = p.caution,
        )

        // --- horizon ------------------------------------------------------
        Section("எவ்வளவு நேரம் / HOW LONG ADRIFT") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6.0, 12.0, 24.0).forEach { h ->
                    val selected = hours == h
                    Box(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) p.accent else p.panel)
                            .clickable { hours = h },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${h.toInt()} h",
                            color = if (selected) p.onAccent else p.ink,
                            fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- the position to read out ---------------------------------------
        Section("சொல்ல வேண்டிய இடம் / POSITION TO REPORT") {
            Text(coords(result.centreLat, result.centreLon),
                color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Centre of the search box after ${hours.toInt()} hours. The box " +
                    "itself spans roughly ${fmt(boxSpanKm(result))} km — give the " +
                    "Coast Guard your CURRENT position too, not only this one.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("இதை SMS அனுப்பு  ·  Send this as SMS", p.deny) {
                onSms("", driftSms(originLat, originLon, result, hours))
            }
        }

        // --- the box --------------------------------------------------------
        Section("தேடல் பெட்டி / SEARCH BOX") {
            result.box.forEachIndexed { i, corner ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${i + 1}", color = p.accent, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                    Text(coords(corner.first, corner.second), color = p.ink, fontSize = 14.sp)
                }
            }
        }

        // --- what it was computed from ---------------------------------------
        Section("எதிலிருந்து / WORKED OUT FROM") {
            InputRow("காற்று / Wind", "${fmt(zone.windSpeedKmh!!)} km/h from " +
                "${zone.windDirectionDeg!!.toInt()}°")
            InputRow("நீரோட்டம் / Current", "${fmt(zone.currentSpeedKmh!!)} km/h toward " +
                "${zone.currentDirectionDeg!!.toInt()}°")
            InputRow("இடம் / Readings from", zone.zone)
            InputRow("நேரம் / Measured", humanTime(zone.validTime))
            Spacer(Modifier.height(10.dp))
            Text(
                "Leeway model — " + DriftModel.SOURCE + ". Wind and current are " +
                    "the readings at ${zone.zone}, not at this exact position: " +
                    "ORCA does not interpolate a field it did not measure.",
                color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun InputRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = p.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** The SMS a crew sends while they still have a bar of signal. */
fun driftSms(
    lat: Double,
    lon: Double,
    r: DriftModel.Result,
    hours: Double,
): String = buildString {
    append("ORCA — ENGINE FAILURE / இயந்திரம் நின்றுவிட்டது\n")
    append("Now / இப்போது: ${coords(lat, lon)}\n")
    append("Adrift ${hours.toInt()} h -> ${coords(r.centreLat, r.centreLon)}\n")
    append("Drifting ${DriftModel.compass(r.bearingDeg)} " +
        "(${r.bearingDeg.toInt()}deg) at ${fmt(r.distanceKm / hours)} km/h\n")
    append("Search box:\n")
    r.box.forEach { append("  ${coords(it.first, it.second)}\n") }
    append("Leeway model, wind+current at last download. Estimate, not a fix.")
}

private fun boxSpanKm(r: DriftModel.Result): Double {
    if (r.box.size < 4) return 0.0
    val lats = r.box.map { it.first }
    val lons = r.box.map { it.second }
    return DriftModel.haversineKm(lats.min(), lons.min(), lats.max(), lons.max())
}

// =====================================================================
// shared bits
// =====================================================================

@Composable
fun Headline(tamil: String, english: String, detail: String, tint: Color) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(tint).padding(20.dp)) {
        Text(tamil, color = p.onAccent, fontSize = 28.sp,
            fontWeight = FontWeight.Black, lineHeight = 36.sp)
        Spacer(Modifier.height(6.dp))
        Text(english, color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(detail, color = p.onAccent, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun PositionRow(
    lat: Double,
    lon: Double,
    fromGps: Boolean,
    zoneName: String?,
    fixError: String?,
) {
    val p = LocalPalette.current
    Section("எந்த இடத்திற்கு / CHECKED FOR") {
        Text(coords(lat, lon), color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (fromGps) "This phone's GPS fix."
            else "No GPS fix — using ${zoneName ?: "the first zone"}'s harbour position. " +
                (fixError ?: "") + " A warning is tested against a POSITION, so if " +
                "you are far offshore this answer is for the harbour, not for you.",
            color = if (fromGps) p.muted else p.caution,
            fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

/**
 * Last known fix, with no continuous listener.
 *
 * A screen that is open for ten seconds does not justify starting GPS: the
 * boundary watch already runs a real location service when the crew asks
 * for it, and battery has to last the whole trip. A stale fix is labelled
 * as one rather than being refreshed at that cost.
 */
@SuppressLint("MissingPermission")
private fun lastKnownFix(context: Context): Location? = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
} catch (e: SecurityException) {
    // Permission was revoked between the check and the call. Not a crash,
    // and not a fabricated position either.
    null
}

fun coords(lat: Double, lon: Double): String =
    String.format("%.4f°%s  %.4f°%s",
        kotlin.math.abs(lat), if (lat >= 0) "N" else "S",
        kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

fun fmt(v: Double): String =
    if (v >= 10) String.format("%.0f", v) else String.format("%.1f", v)

/**
 * IMD's alert text, made readable on a phone.
 *
 * Seen on hardware: the instruction block of a real IMD warning renders
 * with tofu boxes through it. The cause is in IMD's own text -- the
 * bullets are Wingdings characters in the Unicode private use area
 * (U+F000..U+F8FF), which mean "a tick" only in a font Android does not
 * have. On a glare-lit deck a line of boxes reads as a broken app.
 *
 * This strips those glyphs and the tabs around them, and NOTHING ELSE. No
 * rewording, no summarising, no truncation: the words stay exactly as IMD
 * published them, and the raw text stays untouched in the bundle. Removing
 * a bullet nobody can render is display cleanup; changing a warning's
 * wording would be something else entirely.
 */
private fun readable(raw: String): String = raw
    .filter { it.code !in 0xF000..0xF8FF }
    .lines()
    .joinToString("\n") { it.replace('\t', ' ').trim() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/** CAP timestamps carry a real offset; show them in the phone's zone. */
fun humanTime(raw: String?): String {
    if (raw.isNullOrEmpty()) return "unknown"
    return try {
        OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    } catch (e: Exception) {
        raw
    }
}
