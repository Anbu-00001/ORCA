package org.orca.advisory

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import kotlinx.coroutines.delay
import org.orca.advisory.ui.LocalPalette
import java.time.Instant

/**
 * Every fence at once, against this boat's position.
 *
 * <p>Four separate things can end a trip badly and they are all the same
 * question asked of the same GPS fix: the treaty line that gets boats
 * seized, the national park that gets them fined, a live storm polygon,
 * and the edge of mobile coverage. This screen answers all four and ranks
 * them, so a crew is told the WORST thing that is true rather than four
 * things in the order they happened to be computed.
 *
 * <p>THE COVERAGE FENCE IS NOT A PROHIBITION. It is a deadline. Mobile
 * signal dies around 15 km / 8 NM offshore and boats work 120-150 km out,
 * so the last useful moment to download an advisory is while still inside
 * it. That is worth saying once, loudly, in harbour -- not every fifteen
 * minutes for six days.
 *
 * <p>Every warning can be silenced with one button, and a silenced
 * warning comes back in fifteen minutes or the instant it gets worse.
 * See {@link Geofence#decide}.
 */
@Composable
fun GeofenceScreen(advisory: OrcaRepository.Advisory?, onEnsureLocation: () -> Boolean) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current

    var fix by remember { mutableStateOf<Location?>(null) }
    var acks by remember { mutableStateOf<Map<Geofence.Kind, Geofence.Ack>>(emptyMap()) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var permission by remember { mutableStateOf(true) }
    val seabed = remember { Bathymetry.load(context) }

    // Re-evaluate on a timer. This is what makes the fifteen-minute repeat
    // visible while the screen is open; the same rules run in
    // BoundaryWatchService when it is not.
    LaunchedEffect(Unit) {
        permission = onEnsureLocation()
        while (true) {
            if (permission) fix = lastFixOrNull(context)
            nowMs = System.currentTimeMillis()
            delay(5_000)
        }
    }

    val lat = fix?.latitude
    val lon = fix?.longitude

    if (lat == null || lon == null) {
        EmptyState(
            "இடம் தெரியவில்லை",
            if (permission)
                "Waiting for a GPS fix. A fence can only be checked against a position, " +
                    "and ORCA will not check it against a guess."
            else
                "Location permission is not granted, so ORCA cannot tell you where you are " +
                    "relative to the boundary, the park or a storm.",
        )
        return
    }

    val hazards = evaluate(lat, lon, advisory, seabed)
    val worst = Geofence.worst(hazards)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // --- the headline: the worst thing that is true -----------------
        if (worst == null) {
            Headline(
                tamil = "தடை எதுவும் இல்லை",
                english = "CLEAR OF EVERY FENCE",
                detail = "You are not near the sea boundary, not inside the marine park, " +
                    "and no live IMD warning covers this position.",
                tint = p.go,
            )
        } else {
            val d = Geofence.decide(worst, acks, nowMs)
            Headline(
                tamil = tamilFor(worst),
                english = englishFor(worst),
                detail = d.why,
                tint = tintFor(p, worst.band),
            )
        }

        OfflineMap(
            heightDp = 210,
            focusLat = lat, focusLon = lon,
            spanDeg = 1.6,
            polygons = listOf(
                MapPolygon(Geofence.MARINE_PARK, p.caution, fillAlpha = 0.22f),
            ) + livePolygons(advisory, p.deny),
            lines = advisory?.boundary?.segments?.map { MapLine(it, p.deny, dashed = true) }
                ?: emptyList(),
            markers = listOf(MapMarker(lat, lon, Color.White, radiusDp = 7f, ring = true)),
        )

        // --- every fence, ranked -----------------------------------------
        Section(bi("எல்லைகள் / FENCES", lang)) {
            hazards.sortedBy { Geofence.severity(it.band) }.forEach { h ->
                FenceRow(h, acks[h.kind], nowMs)
            }
        }

        // --- the reset ------------------------------------------------------
        Section(bi("அமைதி / SILENCE", lang)) {
            val active = hazards.any { it.band != Geofence.Band.CLEAR }
            BigButton(
                if (acks.isEmpty()) bi("15 நிமிடம் அமைதி  ·  Silence for 15 min", lang)
                else bi("மீண்டும் அமை  ·  Reset all warnings", lang),
                if (acks.isEmpty()) p.panel else p.accent,
            ) {
                acks = if (acks.isEmpty()) {
                    // Acknowledge everything currently raised. It comes back
                    // in fifteen minutes, or the moment any fence worsens.
                    hazards.filter { it.band != Geofence.Band.CLEAR }
                        .associate { it.kind to Geofence.Ack(it.band, nowMs) }
                } else {
                    emptyMap()
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (acks.isEmpty())
                    "Silencing does not switch anything off. A silenced warning speaks again " +
                        "after 15 minutes, and immediately if it gets worse — an acknowledgement " +
                        "covers the situation you saw, not one that has since deteriorated."
                else
                    "${acks.size} warning(s) silenced. They return in 15 minutes, or at once " +
                        "if the situation worsens.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            if (!active) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing is currently raised, so there is nothing to silence.",
                    color = p.muted, fontSize = 12.sp,
                )
            }
        }

        Section(bi("இது எதிலிருந்து / SOURCES", lang)) {
            SourceRow("Sea boundary", advisory?.boundary?.source ?: "not loaded")
            SourceRow(
                "Marine park",
                "Gulf of Mannar Marine National Park — an APPROXIMATE box around the " +
                    "published island coordinate, not the park's full 560 km² boundary, " +
                    "which is not publicly downloadable. Treat its edge as indicative.",
            )
            SourceRow("Storm areas", advisory?.alerts?.source ?: "not loaded")
            SourceRow(
                "Coverage edge",
                "Indian offshore mobile coverage is reported to end around 15 km " +
                    "(8 NM) from shore, while boats work 120–150 km out. This fence is a " +
                    "reminder to download, not a restriction.",
            )
        }
    }
}

// ---------------------------------------------------------------------

private fun evaluate(
    lat: Double,
    lon: Double,
    advisory: OrcaRepository.Advisory?,
    seabed: Bathymetry.Grid?,
): List<Geofence.Hazard> {
    val out = mutableListOf<Geofence.Hazard>()

    // 1. IMBL, using the bands the SERVER supplied.
    val b = advisory?.boundary
    if (b != null) {
        val km = Geofence.distanceToLineKm(lat, lon, b.segments)
        if (km != null) {
            out += Geofence.Hazard(
                Geofence.Kind.IMBL,
                Geofence.bandForDistance(km, b.urgentKm, b.warningKm, b.advisoryKm),
                km,
            )
        }
    }

    // 2. Marine national park.
    val inPark = Geofence.pointInPolygon(lat, lon, Geofence.MARINE_PARK)
    val parkKm = Geofence.distanceToLineKm(
        lat, lon, listOf(Geofence.MARINE_PARK + Geofence.MARINE_PARK.first()),
    ) ?: 999.0
    out += Geofence.Hazard(
        Geofence.Kind.PARK,
        if (inPark) Geofence.Band.INSIDE
        else Geofence.bandForDistance(parkKm, 1.0, 3.0, 6.0),
        if (inPark) 0.0 else parkKm,
    )

    // 3. A live IMD warning covering this position.
    val storms = StormAlerts.match(lat, lon, advisory?.alerts, Instant.now())
    out += Geofence.Hazard(
        Geofence.Kind.STORM,
        if (storms.covering.isNotEmpty()) Geofence.Band.INSIDE else Geofence.Band.CLEAR,
        0.0,
    )

    // 4. Coverage — ONLY if there is water under the boat.
    //
    //    First attempt used distance from the nearest ORCA harbour alone,
    //    and fired in the middle of Chennai with five bars of signal:
    //    everywhere inland is far from a harbour. The ETOPO soundings are
    //    already on the device, so ask them. No sounding near enough to
    //    answer means no warning -- not knowing is not a reason to guess.
    val atSea = Geofence.isAtSea(lat, lon, seabed)
    val nearest = advisory?.zones?.minOfOrNull {
        DriftModel.haversineKm(lat, lon, it.lat, it.lon)
    }
    if (atSea == true && nearest != null) {
        out += Geofence.Hazard(
            Geofence.Kind.COVERAGE,
            when {
                // Capped at WARNING. Losing signal is a deadline to
                // download, never a prohibition, and it must not outrank
                // the treaty line in red.
                nearest >= Geofence.COVERAGE_EDGE_KM -> Geofence.Band.WARNING
                nearest >= Geofence.COVERAGE_EDGE_KM * 0.7 -> Geofence.Band.ADVISORY
                else -> Geofence.Band.CLEAR
            },
            nearest,
        )
    }
    return out
}

private fun livePolygons(advisory: OrcaRepository.Advisory?, tint: Color): List<MapPolygon> {
    val now = Instant.now()
    return (advisory?.alerts?.alerts ?: emptyList())
        .filter { !StormAlerts.hasExpired(it.expires, now) && (it.polygon?.size ?: 0) >= 3 }
        .map { MapPolygon(it.polygon!!, tint) }
}

@Composable
private fun FenceRow(h: Geofence.Hazard, ack: Geofence.Ack?, nowMs: Long) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val tint = tintFor(p, h.band)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (h.kind) {
                Geofence.Kind.IMBL -> Icons.Filled.Fence
                Geofence.Kind.PARK -> Icons.Filled.Park
                Geofence.Kind.STORM -> Icons.Filled.Storm
                Geofence.Kind.COVERAGE -> Icons.Filled.SignalCellularAlt
            },
            null, tint = tint, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(englishFor(h), color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append(
                        when (h.band) {
                            Geofence.Band.INSIDE -> "inside"
                            Geofence.Band.CLEAR -> "clear"
                            else -> "${Units.distance(h.distanceKm)} away"
                        },
                    )
                    if (ack != null) append(" · silenced ${(nowMs - ack.atMs) / 60000} min ago")
                },
                color = p.muted, fontSize = 12.5.sp,
            )
        }
        Text(
            h.band.name, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SourceRow(what: String, from: String) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(what, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(from, color = p.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

private fun tintFor(p: org.orca.advisory.ui.Palette, band: Geofence.Band) = when (band) {
    Geofence.Band.INSIDE, Geofence.Band.URGENT -> p.deny
    Geofence.Band.WARNING -> p.caution
    Geofence.Band.ADVISORY -> p.accent
    Geofence.Band.CLEAR -> p.go
}

private fun englishFor(h: Geofence.Hazard) = when (h.kind) {
    Geofence.Kind.IMBL -> "Sri Lanka maritime boundary"
    Geofence.Kind.PARK -> "Marine national park (no fishing)"
    Geofence.Kind.STORM -> "IMD storm warning area"
    Geofence.Kind.COVERAGE -> "Edge of mobile coverage"
}

private fun tamilFor(h: Geofence.Hazard) = when (h.kind) {
    Geofence.Kind.IMBL -> "கடல் எல்லை அருகில்"
    Geofence.Kind.PARK -> "மீன்பிடி தடை பகுதி"
    Geofence.Kind.STORM -> "புயல் பகுதிக்குள்"
    Geofence.Kind.COVERAGE -> "சிக்னல் முடியப் போகிறது"
}

@SuppressLint("MissingPermission")
private fun lastFixOrNull(context: Context): Location? = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
} catch (e: SecurityException) {
    null
}
