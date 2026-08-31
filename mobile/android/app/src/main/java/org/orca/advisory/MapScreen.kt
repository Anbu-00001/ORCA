package org.orca.advisory

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.orca.advisory.ui.LocalPalette
import org.orca.advisory.ui.colorForAction
import java.time.Instant

/**
 * The sea chart — every layer ORCA holds, drawn with the radio off.
 *
 * <p>This is the screen that answers "why not just use the website".
 * The web client's map is better in harbour and is a grey rectangle at
 * sea, because every tile is a network request. This one is drawn from
 * NOAA soundings and treaty geometry that live on the phone, so it is
 * exactly as good 60 km out as it is alongside.
 *
 * <p>Layers, all real, all cited on the screen itself:
 * seabed (ETOPO 2022), the India–Sri Lanka IMBL, each zone coloured by
 * the verdict `orca/policy.py` issued for it, any live IMD warning
 * polygon, and the boat.
 */
@Composable
fun MapScreen(advisory: OrcaRepository.Advisory?, onEnsureLocation: () -> Boolean) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current
    var fix by remember { mutableStateOf<Location?>(null) }
    var showStorms by remember { mutableStateOf(true) }
    var showBoundary by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { if (onEnsureLocation()) fix = lastFix(context) }

    if (advisory == null) {
        EmptyState("வரைபடம் இல்லை", "No advisory is stored on this phone yet, so there is nothing to draw.")
        return
    }

    val zones = advisory.zones
    val markers = buildList {
        zones.forEach {
            add(MapMarker(it.lat, it.lon, p.colorForAction(it.action), radiusDp = 6f))
        }
        fix?.let { add(MapMarker(it.latitude, it.longitude, Color.White, radiusDp = 7f, ring = true)) }
    }

    val lines = if (showBoundary) {
        advisory.boundary?.segments?.map { MapLine(it, p.deny, dashed = true) } ?: emptyList()
    } else emptyList()

    // Only warnings that are actually live get drawn. An expired polygon
    // on a chart is worse than no polygon.
    val stormPolys = if (!showStorms) emptyList() else {
        val now = Instant.now()
        (advisory.alerts?.alerts ?: emptyList())
            .filter { !StormAlerts.hasExpired(it.expires, now) && (it.polygon?.size ?: 0) >= 3 }
            .map {
                MapPolygon(
                    it.polygon!!,
                    if (it.severity == "Extreme") p.deny else p.caution,
                    label = it.areaDesc,
                )
            }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        OfflineMap(
            heightDp = 340,
            focusLat = fix?.latitude,
            focusLon = fix?.longitude,
            spanDeg = if (fix != null) 3.0 else 6.5,
            polygons = stormPolys,
            lines = lines,
            markers = markers,
        )

        Section(bi("அடுக்குகள் / LAYERS", lang)) {
            LayerToggle(bi("கடல் எல்லை / Sea boundary (IMBL)", lang), showBoundary) { showBoundary = it }
            LayerToggle(bi("புயல் எச்சரிக்கை / IMD warning areas", lang), showStorms) { showStorms = it }
            Spacer(Modifier.height(8.dp))
            Text(
                "Pinch to zoom, drag to pan. " +
                    if (fix != null) "The white ring is this phone."
                    else "No GPS fix yet, so the whole coast is shown.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }

        Section(bi("குறியீடு / KEY", lang)) {
            zones.forEach { z ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Box(
                        Modifier.size(12.dp).padding(top = 2.dp)
                            .then(Modifier)
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawCircle(p.colorForAction(z.action))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(z.zone, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(z.action, color = p.colorForAction(z.action),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Section(bi("இது எதிலிருந்து / WHAT THIS IS DRAWN FROM", lang)) {
            SourceLine("Seabed", "NOAA NCEI ETOPO 2022, 4,760 soundings shipped in the app")
            SourceLine("Sea boundary", advisory.boundary?.source ?: "not loaded")
            SourceLine("Warning areas", advisory.alerts?.source ?: "not loaded")
            SourceLine("Zone colours", "orca/policy.py, computed on shore")
            Spacer(Modifier.height(10.dp))
            Text(
                "No tiles, no basemap, no network. Every shape on this chart is a " +
                    "measurement or a treaty line ORCA can name. A web map cannot " +
                    "draw this out of coverage — its tiles live on someone else's server.",
                color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LayerToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent,
                checkedTrackColor = p.accent,
            ),
        )
    }
}

@Composable
private fun SourceLine(what: String, from: String) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(what, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(from, color = p.ink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@SuppressLint("MissingPermission")
private fun lastFix(context: Context): Location? = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
} catch (e: SecurityException) { null }

// =====================================================================
// 12. SIGNAL — the phone as a distress light.
// =====================================================================

/**
 * Flash SOS from the camera light.
 *
 * <p>Paired with the SOS screen rather than replacing it: an SMS reaches
 * the shore if there is any signal at all, and this reaches the boat two
 * kilometres away when there is none. They fail in different ways, which
 * is the point of having both.
 */
@Composable
fun SignalScreen() {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current
    val available = remember { TorchSos.isAvailable(context) }
    // Read straight from TorchSos rather than caching a copy. The SOS send
    // and the volume-key watch can both start the light while this screen
    // is open, and a local Boolean would sit here reading "off" beside a
    // flashing torch with no way to stop it.
    val on = TorchSos.running

    // If the screen is left while flashing, keep flashing -- that is the
    // whole point. A distress light that switches itself off when the crew
    // navigates away is worse than no light.
    DisposableEffect(Unit) { onDispose { } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!available) {
            Headline(
                tamil = "இந்த ஃபோனில் விளக்கு இல்லை",
                english = "THIS PHONE HAS NO CAMERA FLASH",
                detail = "ORCA cannot signal with a light on this device. Use the " +
                    "emergency SMS screen instead.",
                tint = p.unknown,
            )
            return@Column
        }

        Headline(
            tamil = if (on) "விளக்கு எரிகிறது" else "ஆபத்து விளக்கு",
            english = if (on) "SIGNALLING SOS" else "DISTRESS LIGHT",
            detail = if (on)
                "The camera light is flashing S-O-S in Morse, about once every " +
                    "${TorchSos.cycleSeconds().toInt()} seconds. Point the back of the " +
                    "phone at the boat you want to reach. It keeps flashing with the " +
                    "screen off."
            else
                "Flashes S-O-S from the camera light. A light is a recognised distress " +
                    "signal at sea and carries about a mile after dark — further than a " +
                    "shout, and it works when there is no signal at all.",
            tint = if (on) p.deny else p.caution,
        )

        Section("") {
            BigButton(
                if (on) bi("நிறுத்து  ·  Stop signalling", lang) else bi("SOS விளக்கை ஆரம்பி  ·  Start SOS light", lang),
                if (on) p.panel else p.deny,
            ) {
                TorchSos.toggle(context)
            }
            // The reason comes from TorchSos, which is the only thing that
            // knows why -- no flash at all, or the camera taken mid-signal.
            TorchSos.problem?.let { why ->
                Spacer(Modifier.height(10.dp))
                Text(why, color = p.caution, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (on) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Flashing for ${TorchSos.runningSeconds()} s. It will not stop on its " +
                        "own — that is deliberate, so press Stop when you no longer need it.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }

        Section(bi("இதை ஏன் பயன்படுத்த வேண்டும் / WHY A LIGHT", lang)) {
            Text(
                "COLREGS lists flashes among the recognised signals of distress. A boat " +
                    "that has lost its engine after dark often has no flare left and no " +
                    "working VHF, and the nearest boat is looking the wrong way. Morse " +
                    "S-O-S is slow on purpose: a rhythm someone recognises is worth more " +
                    "than a bright light nobody reads.",
                color = p.ink, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Battery: the light draws far less than the screen does. Turn the screen " +
                    "off and leave this running.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
    }
}
