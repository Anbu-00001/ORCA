package org.orca.advisory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * What this phone can ACTUALLY hear and say.
 *
 * <p>Written because "which languages will work tomorrow" is not a
 * question anyone should answer from memory. Speech support on Android is
 * not a property of the OS version: it depends on which recogniser is
 * installed, which language packs the owner has downloaded, and whether
 * the recogniser will run without a network. Two identically-specced
 * phones give different answers.
 *
 * <p>So this asks the device and logs what it says. Nothing here is a
 * claim; it is a measurement, and the numbers in any pitch should come
 * from its output on the phone being demoed, not from this comment.
 */
object VoiceProbe {

    private const val TAG = "ORCA-VOICE"

    /** Ask the recogniser which languages it supports, and whether offline. */
    fun probeRecognition(context: Context) {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.i(TAG, "recognition available = $available")
        if (!available) return

        // The recogniser answers this broadcast with its language list.
        context.sendOrderedBroadcast(
            Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
            null,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val extras: Bundle = getResultExtras(true) ?: run {
                        Log.i(TAG, "recogniser returned no language details")
                        return
                    }
                    val pref = extras.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
                    val all = extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    Log.i(TAG, "recogniser preferred = $pref")
                    Log.i(TAG, "recogniser supports ${all?.size ?: 0} languages")
                    // Only the ones this project would ever claim.
                    listOf("ta", "hi", "en", "ml", "te", "kn", "mr", "bn", "gu", "or", "pa", "ur").forEach { code ->
                        val hits = all?.filter { it.startsWith(code) } ?: emptyList()
                        if (hits.isNotEmpty()) Log.i(TAG, "  RECOG $code -> $hits")
                    }
                }
            },
            null, android.app.Activity.RESULT_OK, null, null,
        )
    }

    /** Ask the TTS engine which languages it can SPEAK, and which are on-device. */
    fun probeTts(context: Context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.i(TAG, "TTS init failed ($status)")
                return@TextToSpeech
            }
            val engine = tts?.defaultEngine
            Log.i(TAG, "TTS engine = $engine")
            val langs = runCatching { tts?.availableLanguages }.getOrNull()
            Log.i(TAG, "TTS reports ${langs?.size ?: 0} locales")
            listOf("ta", "hi", "en", "ml", "te", "kn", "mr", "bn", "gu", "or", "pa", "ur").forEach { code ->
                val hits = langs?.filter { it.language == code } ?: emptyList()
                if (hits.isNotEmpty()) {
                    // isLanguageAvailable distinguishes "supported" from
                    // "actually installed", which is the difference between
                    // working at sea and working in the harbour wifi.
                    val l = Locale(code, "IN")
                    val avail = tts?.isLanguageAvailable(l)
                    val word = when (avail) {
                        TextToSpeech.LANG_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "INSTALLED"
                        TextToSpeech.LANG_MISSING_DATA -> "missing data (needs download)"
                        TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"
                        else -> "unknown($avail)"
                    }
                    Log.i(TAG, "  TTS $code -> $word  ${hits.map { it.toLanguageTag() }}")
                }
            }
            tts?.shutdown()
        }
    }
}

/**
 * Does the recogniser actually run WITHOUT a network?
 *
 * `EXTRA_PREFER_OFFLINE` is a request, not a guarantee: the recogniser
 * may honour it, ignore it, or fail. The only honest way to know is to
 * start one and see which callback fires, which is what this does.
 */
fun probeOfflineRecognition(context: Context, onResult: (String) -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onResult("recognition unavailable on this device")
        return
    }
    val r = SpeechRecognizer.createSpeechRecognizer(context)
    r.setRecognitionListener(object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { onResult("READY — recogniser started offline") }
        override fun onError(e: Int) {
            onResult(
                when (e) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NEEDS NETWORK (error $e)"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE PACK NOT DOWNLOADED"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE NOT SUPPORTED"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC PERMISSION MISSING"
                    else -> "error $e"
                },
            )
            runCatching { r.destroy() }
        }
        override fun onResults(b: Bundle?) { runCatching { r.destroy() } }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
    })
    r.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        },
    )
}
