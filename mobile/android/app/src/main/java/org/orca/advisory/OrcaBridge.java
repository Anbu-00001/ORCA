package org.orca.advisory;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;
import java.util.Set;

/**
 * The native capabilities the web client cannot have, exposed to it.
 *
 * <p>Everything here answers one question: what can an APK do that a page
 * in a browser genuinely cannot? Wrapping a working web app in a WebView
 * and calling it a mobile app adds nothing. These four do:
 *
 * <ol>
 *   <li><b>Tamil speech OUT.</b> Android's on-device TTS engine, which
 *       keeps working with the radio off once the ta-IN voice is
 *       installed. The Web Speech API in a WebView is unreliable and on
 *       most Android builds routes through a network service, so it is
 *       exactly the thing that stops working at sea.</li>
 *   <li><b>Tamil speech IN.</b> A fisherman steering a boat in spray, at
 *       night, with wet hands, is not typing a question.</li>
 *   <li><b>SMS distress.</b> GSM voice/SMS reaches considerably further
 *       offshore than mobile data. A browser cannot compose an SMS.</li>
 *   <li><b>Boundary watch.</b> See BoundaryWatchService -- background GPS
 *       against the real IMBL geometry, alerting while the app is closed
 *       and the screen is off. A web page cannot run when it is not
 *       open.</li>
 * </ol>
 *
 * <p>WHAT THIS CLASS MUST NEVER DO: decide anything. It speaks strings it
 * is given and reports positions it measures. Every verdict still comes
 * from orca/policy.py by way of the bundle (docs/MOBILE_APP.md §2).
 */
public class OrcaBridge {

    private static final String TAG = "ORCA";
    public static final int REQ_SPEECH = 1001;
    public static final int REQ_LOCATION = 1002;

    private final Activity activity;
    private final WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean tamilAvailable = false;

    OrcaBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        initTts();
    }

    // --- speech out -----------------------------------------------------

    private void initTts() {
        tts = new TextToSpeech(activity, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS engine unavailable; the app stays silent and text-only");
                return;
            }
            ttsReady = true;
            // ta-IN, not "ta": the Indian Tamil voice. A generic language
            // code can resolve to a different regional voice or to none.
            int result = tts.setLanguage(new Locale("ta", "IN"));
            tamilAvailable = result != TextToSpeech.LANG_MISSING_DATA
                          && result != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!tamilAvailable) {
                // Reported, never swallowed (CLAUDE.md rule 2). Silence
                // here would look identical to a working Tamil voice.
                Log.w(TAG, "Tamil (ta-IN) TTS voice is not installed on this device. "
                        + "Speech falls back to English; install it from "
                        + "Settings > Accessibility > Text-to-speech > Install voice data.");
                tts.setLanguage(Locale.UK);
            } else {
                Log.i(TAG, "Tamil TTS ready" + describeOfflineness());
            }
        });
    }

    /** Whether the selected Tamil voice needs a network connection.
     *
     * <p>This matters more than it sounds: a TTS voice flagged
     * FEATURE_NETWORK_SYNTHESIS is exactly as useless at sea as no voice
     * at all, and it would only be discovered there. Better to know in
     * harbour. */
    private String describeOfflineness() {
        try {
            Voice v = tts.getVoice();
            if (v == null) return "";
            Set<String> features = v.getFeatures();
            boolean networkOnly = features != null
                    && features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                    && !features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS);
            return networkOnly
                    ? " (WARNING: this voice synthesises over the network and will fail offline)"
                    : " (on-device, works with no signal)";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Speak text aloud. `lang` is "ta" or "en".
     *
     * <p>QUEUE_FLUSH, not QUEUE_ADD: a new verdict replaces the one being
     * spoken. Stacking safety announcements so the crew hears a stale one
     * to completion first is the wrong behaviour on a boat.
     */
    @JavascriptInterface
    public void speak(String text, String lang) {
        if (!ttsReady || text == null || text.trim().isEmpty()) return;
        if ("ta".equals(lang) && tamilAvailable) {
            tts.setLanguage(new Locale("ta", "IN"));
        } else {
            tts.setLanguage(Locale.UK);
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orca-verdict");
    }

    @JavascriptInterface
    public void stopSpeaking() {
        if (ttsReady) tts.stop();
    }

    /** Lets the page hide the speaker button when there is nothing to press it for. */
    @JavascriptInterface
    public boolean canSpeakTamil() {
        return ttsReady && tamilAvailable;
    }

    // --- speech in ------------------------------------------------------

    /**
     * Open the system recogniser for a Tamil (or English) question.
     *
     * <p>EXTRA_PREFER_OFFLINE is set so the device uses a downloaded
     * language pack when it has one. Android may still fall back to a
     * network recogniser; when it does and there is no signal, the
     * recogniser returns an error and MainActivity leaves the typed input
     * alone. Failing back to the keyboard is correct -- inventing a
     * question the crew did not ask is not.
     */
    @JavascriptInterface
    public void listen(String lang) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String locale = "ta".equals(lang) ? "ta-IN" : "en-IN";
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                "ta".equals(lang) ? "கேளுங்கள்…" : "Ask ORCA…");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            activity.startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Log.w(TAG, "No speech recogniser on this device: " + e.getMessage());
            toWeb("window.__ORCA_VOICE_ERROR__ && window.__ORCA_VOICE_ERROR__("
                    + jsString("no recogniser") + ")");
        }
    }

    /** Called by MainActivity.onActivityResult with the recognised text. */
    void deliverSpeechResult(String text) {
        toWeb("window.__ORCA_VOICE_RESULT__ && window.__ORCA_VOICE_RESULT__("
                + jsString(text) + ")");
    }

    // --- SMS ------------------------------------------------------------

    /**
     * Hand a pre-filled distress SMS to the phone's messaging app.
     *
     * <p>DELIBERATELY an ACTION_SENDTO intent rather than SmsManager:
     *
     * <ul>
     *   <li>It needs NO permission at all. Android 15 reclassified
     *       SEND_SMS as hard-restricted for sideloaded apps -- the toggle
     *       is greyed out until someone digs through "Allow restricted
     *       settings" -- so a permission-based path would simply not work
     *       on the device this was built for.</li>
     *   <li>The crew sees and confirms the message before it goes. An app
     *       that can silently send SMS from a boat is a liability, and
     *       for a distress message the human should be in the loop
     *       anyway.</li>
     * </ul>
     *
     * <p>Why SMS at all: GSM voice and SMS reach much further offshore
     * than mobile data. When ORCA has nothing left to say because the
     * data link died, the phone can still get a position to shore.
     */
    @JavascriptInterface
    public void sendSms(String number, String message) {
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.parse("smsto:" + (number == null ? "" : number)));
            intent.putExtra("sms_body", message == null ? "" : message);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "No SMS app available: " + e.getMessage());
        }
    }

    // --- boundary watch -------------------------------------------------

    @JavascriptInterface
    public boolean hasLocationPermission() {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestLocationPermission() {
        activity.requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
        }, REQ_LOCATION);
    }

    /**
     * Start the background boundary watch.
     *
     * <p>`boundaryJson` is the `boundary` object straight out of
     * GET /bundle: the real Marine Regions IMBL geometry plus the
     * distance bands orca/agents.py uses. The app does not own those
     * numbers and must not -- see BoundaryWatchService.
     */
    @JavascriptInterface
    public void startBoundaryWatch(String boundaryJson, String lang) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Boundary watch not started: location permission not granted");
            return;
        }
        Intent intent = new Intent(activity, BoundaryWatchService.class);
        intent.putExtra(BoundaryWatchService.EXTRA_BOUNDARY, boundaryJson);
        intent.putExtra(BoundaryWatchService.EXTRA_LANG, lang);
        activity.startForegroundService(intent);
    }

    @JavascriptInterface
    public void stopBoundaryWatch() {
        activity.stopService(new Intent(activity, BoundaryWatchService.class));
    }

    @JavascriptInterface
    public boolean isBoundaryWatchRunning() {
        return BoundaryWatchService.isRunning();
    }

    /** So the page can render "native" affordances only where they exist. */
    @JavascriptInterface
    public boolean isNativeShell() {
        return true;
    }

    // --- plumbing -------------------------------------------------------

    private void toWeb(final String js) {
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /** JSON-escapes a string for injection into evaluateJavascript. */
    private static String jsString(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder out = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    static Bundle noExtras() { return new Bundle(); }
}
