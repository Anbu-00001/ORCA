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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

    companion object {
        /** Set when PanicService's full-screen alarm launches us: open
         *  straight on SOS, because the crew has already asked for it with
         *  a five-second hold and must not have to navigate. */
        const val EXTRA_OPEN_SOS = "orca.open_sos"
    }

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
        val openSos = intent?.getBooleanExtra(EXTRA_OPEN_SOS, false) == true
        setContent {
            OrcaApp(repo, ::listen, ::sendSms, ::ensureLocation, voiceResult, openSos) {
                voiceResult = null
            }
        }
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

enum class Screen { HOME, VERDICT, FISH, BOUNDARY, SOS, ALERTS, ASK, WAVE, FLEET, STORM, DRIFT, MAP, SIGNAL, FENCE }

@Composable
fun OrcaApp(
    repo: OrcaRepository,
    onListen: () -> Unit,
    onSms: (String, String) -> Unit,
    onEnsureLocation: () -> Boolean,
    voiceResult: String?,
    openSos: Boolean = false,
    onVoiceConsumed: () -> Unit,
) {
    var paletteIndex by remember { mutableIntStateOf(0) }
    // The language belongs to the PERSON holding the phone, not to the
    // device: these handsets are bought set to English and are shared
    // between an owner and crew who do not read the same script. So it is
    // a control in the app, one tap, and it survives a restart.
    val context = LocalContext.current
    var lang by remember { mutableStateOf(Prefs.loadLang(context)) }
    var screen by remember { mutableStateOf(if (openSos) Screen.SOS else Screen.HOME) }
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
      CompositionLocalProvider(LocalLang provides lang) {
        val palette = LocalPalette.current

        // The system's own status/nav icons are painted by Android, not by
        // us, so they have to be told which ground they are sitting on.
        // Day is a light palette and left the clock and battery white on
        // ivory -- invisible in exactly the sunlight the light theme was
        // chosen for.
        val view = LocalView.current
        val lightBars = palette.name == "day"
        LaunchedEffect(lightBars) {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
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
                    lang = lang,
                    onBack = { screen = Screen.HOME },
                    onCyclePalette = { paletteIndex = (paletteIndex + 1) % Palettes.size },
                    onCycleLang = { lang = lang.next().also { Prefs.saveLang(context, it) } },
                )
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            advisory, refreshing, refreshNote,
                            onSos = { screen = Screen.SOS },
                        ) { screen = it }
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
                        Screen.FENCE -> GeofenceScreen(advisory, onEnsureLocation)
                    }
                }
                // The bar sits BELOW the content and carries the gesture-bar
                // inset itself, so a scrolling screen runs to the true bottom
                // edge and the bar is never behind the system pill.
                Box(Modifier.navigationBarsPadding()) {
                    BottomBar(screen) { screen = it }
                }
            }
        }
      }
    }
}

@Composable
private fun TopBar(
    screen: Screen,
    paletteName: String,
    lang: Lang,
    onBack: () -> Unit,
    onCyclePalette: () -> Unit,
    onCycleLang: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.hull).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (screen != Screen.HOME) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "back", tint = p.ink,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack).padding(end = 2.dp),
            )
            Spacer(Modifier.width(14.dp))
            // weight + ellipsis, not a fixed width: a long Tamil title
            // ("ஆபத்து விளக்கு") otherwise pushed the two chips off the row
            // and wrapped "DAY" down three lines. The title yields; the
            // controls keep their intrinsic size.
            Text(
                titleFor(screen, lang),
                color = p.ink, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f)) {
                Text(str(S.GREETING, lang), color = p.ink, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    str(S.SUBTITLE, lang), color = p.muted, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        // Language: one tap cycles Tamil -> English -> Hindi. The label is
        // always written IN the language it selects, so it is readable by
        // the person who needs it without knowing the current setting.
        Row(
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(p.accent.copy(alpha = 0.12f))
                .clickable(onClick = onCycleLang)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Translate, "language", tint = p.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(lang.label, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))

        // Day / dusk / night. One control, cycling, because a dropdown at
        // night is three taps you do not want to make.
        Row(
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(p.accent.copy(alpha = 0.12f))
                .clickable(onClick = onCyclePalette)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (paletteName) {
                    "day" -> Icons.Filled.LightMode
                    "dusk" -> Icons.Filled.WbTwilight
                    else -> Icons.Filled.DarkMode
                },
                null, tint = p.accent, modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(paletteName.uppercase(), color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Screen titles for the bar. Tamil first, as everywhere. */
private fun titleFor(s: Screen, lang: Lang) = when (s) {
    Screen.VERDICT -> str(S.TODAYS_VERDICT, lang)
    Screen.FISH -> str(S.T_FISH, lang)
    Screen.BOUNDARY -> str(S.T_BOUNDARY, lang)
    Screen.SOS -> str(S.NAV_SOS, lang)
    Screen.ALERTS -> str(S.T_WARN, lang)
    Screen.ASK -> str(S.T_ASK, lang)
    Screen.WAVE -> str(S.T_WAVE, lang)
    Screen.FLEET -> str(S.T_FLEET, lang)
    Screen.STORM -> str(S.T_STORM, lang)
    Screen.DRIFT -> str(S.T_DRIFT, lang)
    Screen.MAP -> str(S.T_CHART, lang)
    Screen.SIGNAL -> str(S.T_LIGHT, lang)
    Screen.FENCE -> str(S.T_FENCE, lang)
    Screen.HOME -> "ORCA"
}

/**
 * The bottom bar.
 *
 * <p>Four destinations, chosen because they are what a crew opens
 * repeatedly on one trip: the verdict, the chart, the weather, and the
 * emergency. Everything else lives in the grid on home -- a bar with ten
 * items is a menu, and a menu is what this redesign was getting away
 * from.
 */
@Composable
private fun BottomBar(current: Screen, go: (Screen) -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val items = listOf(
        Triple(Screen.HOME, Icons.Filled.Home, str(S.NAV_HOME, lang)),
        Triple(Screen.MAP, Icons.Filled.Map, str(S.NAV_CHART, lang)),
        Triple(Screen.FENCE, Icons.Filled.Fence, str(S.NAV_FENCE, lang)),
        Triple(Screen.SOS, Icons.Filled.Emergency, str(S.NAV_SOS, lang)),
    )
    Column {
        HorizontalDivider(color = p.line)
        Row(
            Modifier.fillMaxWidth().background(p.panel).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { (screen, icon, label) ->
                val on = current == screen
                val tint = if (on) p.accent else p.muted
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .clickable { go(screen) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, label, tint = tint, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label, color = tint, fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
