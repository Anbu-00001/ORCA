package org.orca.advisory

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.orca.advisory.ui.*

/**
 * ORCA on a phone. A real Compose UI, not a WebView.
 *
 * <p>v1 of this app was a WebView over web/. That was a fair thing to be
 * called out for: it looked identical to the website and did nothing a
 * browser tab could not. This is the rebuild.
 *
 * <p>THE DESIGN BRIEF, which is not the website's brief:
 *
 *  - The reference user is standing on a moving deck, in glare or in the
 *    dark, with wet hands, possibly not reading Tamil fluently and
 *    certainly not reading English. So: Tamil first and larger, English
 *    beneath it; 96dp touch targets; one decision per screen.
 *  - Every feature is ONE TAP from the landing page. There is no menu, no
 *    tab bar, no nested navigation. Six cards, six features.
 *  - Nothing here computes a verdict. Every action, severity and number
 *    comes from GET /bundle, decided by orca/policy.py on shore.
 */
class MainActivity : ComponentActivity() {

    private lateinit var repo: OrcaRepository
    private var voiceResult by mutableStateOf<String?>(null)

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // No result is a real outcome -- cancelled, or the recogniser
        // needed a network it did not have. The field is left as it was;
        // inventing a question the crew did not ask would be far worse.
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) Log.i("ORCA", "Speech returned no result")
        else voiceResult = text
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        Log.i("ORCA", "Location permission granted=${granted.values.any { it }}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = OrcaRepository(this)
        setContent { OrcaApp(repo, ::listen, ::sendSms, ::ensureLocation, voiceResult) { voiceResult = null } }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ta-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "கேளுங்கள்…")
            // Use a downloaded language pack when the device has one.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { speechLauncher.launch(intent) } catch (e: Exception) {
            Log.w("ORCA", "No speech recogniser: ${e.message}")
        }
    }

    /**
     * Hand a pre-filled SMS to the phone's messaging app.
     *
     * ACTION_SENDTO, deliberately, not SmsManager: it needs NO permission
     * (Android 15 hard-restricts SEND_SMS for sideloaded apps, so a
     * permission path would simply not work here), and the crew sees and
     * confirms the message before it goes.
     */
    private fun sendSms(number: String, body: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", body)
            })
        } catch (e: Exception) {
            Log.w("ORCA", "No SMS app: ${e.message}")
        }
    }

    private fun ensureLocation(): Boolean {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
        return granted
    }
}

// ---------------------------------------------------------------------
// Navigation. A sealed set of six screens and a back stack of one --
// deliberately the simplest thing that works, because every feature is
// reachable directly from home and nothing nests.
// ---------------------------------------------------------------------

enum class Screen { HOME, VERDICT, FISH, BOUNDARY, SOS, ALERTS, ASK, WAVE, FLEET, STORM, DRIFT, MAP, SIGNAL }

@Composable
fun OrcaApp(
    repo: OrcaRepository,
    onListen: () -> Unit,
    onSms: (String, String) -> Unit,
    onEnsureLocation: () -> Boolean,
    voiceResult: String?,
    onVoiceConsumed: () -> Unit,
) {
    var paletteIndex by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var advisory by remember { mutableStateOf<OrcaRepository.Advisory?>(null) }
    var selectedZone by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Local first, always. The app is usable before any network attempt.
    LaunchedEffect(Unit) {
        advisory = repo.loadLocal()
        selectedZone = selectedZone ?: advisory?.zones?.firstOrNull()?.zone
        refreshing = true
        try {
            advisory = repo.refresh()
            refreshNote = null
        } catch (e: Exception) {
            // Never swallowed, and never fatal: the stored or seeded
            // advisory stays on screen, labelled with its real age.
            Log.w("ORCA", "Refresh failed, using stored advisory: ${e.message}")
            refreshNote = "No connection — showing what is stored on this phone."
        } finally {
            refreshing = false
        }
    }

    // A spoken question jumps straight to that zone's verdict.
    LaunchedEffect(voiceResult) {
        val spoken = voiceResult ?: return@LaunchedEffect
        val match = advisory?.zones?.firstOrNull { z ->
            spoken.contains(z.zone, ignoreCase = true) ||
                TamilNames.stemFor(spoken) == z.zone
        }
        if (match != null) { selectedZone = match.zone; screen = Screen.VERDICT }
        onVoiceConsumed()
    }

    // BACK GOES BACK, not out.
    //
    // Reported on hardware: pressing back from any feature screen quit the
    // app outright. The Java MainActivity had an onBackPressed override;
    // rewriting in Compose dropped it, and ComponentActivity's default is
    // to finish the Activity. On a feature screen that means one careless
    // edge swipe -- easy with wet hands on a moving deck -- throws you out
    // of a safety app.
    //
    // Enabled ONLY when there is somewhere to go back to. On HOME this
    // stays disabled so the system default applies and back leaves the app,
    // which is what a user expects from a home screen.
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    OrcaTheme(Palettes[paletteIndex]) {
        val palette = LocalPalette.current
        Surface(Modifier.fillMaxSize(), color = palette.hull) {
            // WINDOW INSETS. Measured on an OPPO CPH2591: without these the
            // "ORCA" title sat under the status clock and the last two
            // feature cards were hidden behind the gesture bar -- so two of
            // eight features were unreachable, which on this app means the
            // wave sensor and the fleet relay simply did not exist for the
            // user. statusBarsPadding on the bar, navigationBarsPadding on
            // the content, so a scrolling list still runs to the true
            // bottom edge before it stops.
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopBar(
                    screen = screen,
                    paletteName = palette.name,
                    onBack = { screen = Screen.HOME },
                    onCyclePalette = { paletteIndex = (paletteIndex + 1) % Palettes.size },
                )
                Box(Modifier.weight(1f).navigationBarsPadding()) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(advisory, refreshing, refreshNote) { screen = it }
                        Screen.VERDICT -> VerdictScreen(advisory, selectedZone) { selectedZone = it }
                        Screen.FISH -> FishScreen(repo)
                        Screen.BOUNDARY -> BoundaryScreen(repo, onEnsureLocation)
                        Screen.SOS -> SosScreen(advisory, selectedZone, onSms)
                        Screen.ALERTS -> AlertsScreen(advisory, onSms)
                        Screen.ASK -> AskScreen(onListen)
                        Screen.WAVE -> WaveScreen()
                        Screen.FLEET -> FleetScreen(advisory)
                        Screen.STORM -> StormScreen(advisory, onEnsureLocation)
                        Screen.DRIFT -> DriftScreen(advisory, onEnsureLocation, onSms)
                        Screen.MAP -> MapScreen(advisory, onEnsureLocation)
                        Screen.SIGNAL -> SignalScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(screen: Screen, paletteName: String, onBack: () -> Unit, onCyclePalette: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.hull).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (screen != Screen.HOME) {
            Text("←", color = p.accent, fontSize = 30.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
        }
        Text("ORCA", color = p.ink, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.weight(1f))
        // Day / dusk / night. One control, cycling, because a dropdown at
        // night is three taps you do not want to make.
        Text(
            paletteName.uppercase(),
            color = p.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onCyclePalette)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    HorizontalDivider(color = p.accent, thickness = 2.dp)
}

// ---------------------------------------------------------------------
// HOME — every feature, one tap, Tamil first.
// ---------------------------------------------------------------------

@Composable
private fun HomeScreen(
    advisory: OrcaRepository.Advisory?,
    refreshing: Boolean,
    refreshNote: String?,
    go: (Screen) -> Unit,
) {
    val p = LocalPalette.current
    val first = advisory?.zones?.firstOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DataAgeBanner(advisory, refreshing, refreshNote)

        FeatureCard(
            tamil = "இன்று போகலாமா?",
            english = "Can I go out today?",
            detail = first?.let { "${it.zone} — ${it.action}" } ?: "No advisory stored yet",
            tint = first?.let { p.colorForAction(it.action) } ?: p.unknown,
        ) { go(Screen.VERDICT) }

        FeatureCard(
            tamil = "கடல் வரைபடம்",
            english = "Sea chart — works with no signal",
            detail = "Seabed, boundary, warnings and your boat, drawn offline",
            tint = p.accent,
        ) { go(Screen.MAP) }

        FeatureCard(
            tamil = "மீன் எங்கே இருக்கும்?",
            english = "Where are the fish likely to be?",
            detail = "Potential Fishing Zones from satellite chlorophyll",
            tint = p.accent,
        ) { go(Screen.FISH) }

        FeatureCard(
            tamil = "கடல் எல்லை எச்சரிக்கை",
            english = "Sea boundary warning",
            detail = "Warns you before you cross, even with the app closed",
            tint = p.deny,
        ) { go(Screen.BOUNDARY) }

        FeatureCard(
            tamil = "புயல் வருகிறதா?",
            english = "Is a storm coming here?",
            detail = "India Meteorological Department warnings, checked offline",
            tint = p.caution,
        ) { go(Screen.STORM) }

        FeatureCard(
            tamil = "இயந்திரம் நின்றுவிட்டதா?",
            english = "Engine dead — where will I drift?",
            detail = "Works out your search box so rescuers know where to look",
            tint = p.deny,
        ) { go(Screen.DRIFT) }

        FeatureCard(
            tamil = "அவசர உதவி",
            english = "Emergency — send my position",
            detail = "By SMS. Works where mobile data does not.",
            tint = p.deny,
        ) { go(Screen.SOS) }

        FeatureCard(
            tamil = "ஆபத்து விளக்கு",
            english = "Distress light — flash SOS",
            detail = "Camera light blinks S-O-S. Seen a mile away, needs no signal",
            tint = p.deny,
        ) { go(Screen.SIGNAL) }

        FeatureCard(
            tamil = "பிற படகுகளுக்குச் சொல்",
            english = "Warn another boat by SMS",
            detail = "Most boats have no app, but every boat has SMS",
            tint = p.caution,
        ) { go(Screen.ALERTS) }

        FeatureCard(
            tamil = "அலை அளவு பார்",
            english = "Measure the sea from your boat",
            detail = "Your phone senses the boat's motion — no extra device",
            tint = p.accent,
        ) { go(Screen.WAVE) }

        FeatureCard(
            tamil = "படகுகள் இணைப்பு",
            english = "Share with nearby boats",
            detail = "Pass the advisory on beyond mobile range",
            tint = p.go,
        ) { go(Screen.FLEET) }

        FeatureCard(
            tamil = "தமிழில் கேளுங்கள்",
            english = "Ask in Tamil, by voice",
            detail = "Speak instead of typing",
            tint = p.accent,
        ) { go(Screen.ASK) }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The two ages, always visible.
 *
 * `freshness_min` inside the bundle is computed at FETCH time and does not
 * grow while the phone is at sea -- a two-day-old cache once displayed
 * "14 h old" on the web client. So this states, from the device clock,
 * how old the READINGS are, and separately whether the bundle was
 * downloaded or shipped with the app. Both, or neither is honest.
 */
@Composable
private fun DataAgeBanner(advisory: OrcaRepository.Advisory?, refreshing: Boolean, note: String?) {
    val p = LocalPalette.current
    val text = when {
        refreshing -> "Checking for a newer advisory…"
        advisory == null -> "No advisory on this phone. Connect once to download one."
        else -> {
            val age = advisory.readingAgeMinutes()
            val readings = if (age == null) "age unknown" else "collected ${humanAge(age)} ago"
            val origin = when {
                advisory.fromSeed -> "shipped with the app"
                advisory.downloadAgeMinutes() != null ->
                    "downloaded ${humanAge(advisory.downloadAgeMinutes()!!)} ago"
                else -> "downloaded — age unknown"
            }
            "${advisory.zones.size} zones · $readings · $origin"
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(p.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
        if (note != null) Text(note, color = p.caution, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun FeatureCard(
    tamil: String,
    english: String,
    detail: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp)   // wet-thumb target
            .clip(RoundedCornerShape(8.dp)).background(p.panel)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A colour bar rather than an icon: it reads at a glance in glare,
        // carries the verdict colour where there is one, and needs no
        // icon set to be shipped or understood.
        Box(Modifier.width(8.dp).height(96.dp).background(tint))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(tamil, color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Text(english, color = p.ink, fontSize = 14.sp)
            Text(detail, color = p.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private fun humanAge(minutes: Long): String = when {
    minutes < 1 -> "under a minute"
    minutes < 60 -> "$minutes min"
    minutes < 1440 -> "${minutes / 60} h"
    else -> "${minutes / 1440} d"
}
