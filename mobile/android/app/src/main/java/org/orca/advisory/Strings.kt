package org.orca.advisory

import androidx.compose.runtime.compositionLocalOf

/**
 * Tamil, English and Hindi for the interface.
 *
 * <p>WHY A TABLE AND NOT `res/values-ta/strings.xml`. Android's own
 * resource system switches on the DEVICE locale. That is the wrong
 * switch here: the phone belongs to whoever bought it, is frequently set
 * to English by the shop, and is shared between a boat owner and crew who
 * do not read the same script. The language is a property of the person
 * holding it right now, so it is a control in the app, changeable in one
 * tap, and it persists.
 *
 * <p>WHAT IS AND IS NOT TRANSLATED. This table covers the interface --
 * navigation, labels, headings, the verdict words. It does NOT cover the
 * evidence sentences, which come from the server already phrased
 * (`orca/phrase.py` and `orca/phrase_ta.py`), nor IMD's warning text,
 * which is quoted verbatim in the language IMD published it. Translating
 * a government warning ourselves would be putting words in their mouth.
 *
 * <p>&gt;&gt;&gt; REVIEW REQUIRED &lt;&lt;&lt; The Tamil here is carried over from
 * `orca/phrase_ta.py` and is awaiting a native speaker (see
 * `docs/TAMIL_REVIEW.md`). The HINDI HAS NOT BEEN REVIEWED BY ANYONE. It
 * is a good-faith translation and nothing more. Before this is shown to a
 * Hindi-speaking judge or user, someone who speaks it must read the
 * verdict rows in particular: an inverted negation on DO NOT GO is the
 * one bug in this file that can put a boat to sea in a 2.5 m swell.
 */
enum class Lang(val code: String, val label: String) {
    TA("ta", "தமிழ்"),
    EN("en", "English"),
    HI("hi", "हिन्दी"),
    ;

    /** The next language in the cycle, for the one-tap toggle. */
    fun next(): Lang = entries[(ordinal + 1) % entries.size]
}

val LocalLang = compositionLocalOf { Lang.TA }

/** Every interface string, in the three languages. */
enum class S {
    GREETING, SUBTITLE, TODAYS_VERDICT, NO_ADVISORY,
    WAVE, WIND, TEMP,
    IN_EMERGENCY, HOLD_2S, HOLDING, SOS_HINT_1, SOS_HINT_2,
    EVERYTHING_ELSE,
    T_CHART, T_STORM, T_FISH, T_BOUNDARY, T_DRIFT, T_LIGHT,
    T_WAVE, T_FLEET, T_ASK, T_WARN, T_FENCE, T_SETTINGS,
    NAV_HOME, NAV_CHART, NAV_STORM, NAV_FENCE, NAV_SOS,
    AGE_UNKNOWN, REFRESHING, MIN, HOUR, DAY,
    ACT_GO, ACT_NO, ACT_ALT, ACT_UNKNOWN,
}

private val TABLE: Map<S, Triple<String, String, String>> = mapOf(
    //                        Tamil                    English                  Hindi
    S.GREETING to Triple("வணக்கம்", "Welcome", "नमस्ते"),
    S.SUBTITLE to Triple("ORCA · கடல் ஆலோசனை", "ORCA · sea advisory", "ORCA · समुद्री सलाह"),
    S.TODAYS_VERDICT to Triple("இன்றைய தீர்ப்பு", "TODAY'S VERDICT", "आज का फ़ैसला"),
    S.NO_ADVISORY to Triple("தரவு இல்லை", "No advisory stored", "कोई सलाह नहीं"),

    S.WAVE to Triple("அலை", "Wave", "लहर"),
    S.WIND to Triple("காற்று", "Wind", "हवा"),
    S.TEMP to Triple("வெப்பம்", "Temp", "तापमान"),

    S.IN_EMERGENCY to Triple("அவசரம் என்றால்", "IN AN EMERGENCY", "आपात स्थिति में"),
    S.HOLD_2S to Triple("2 விநாடி பிடி", "Hold 2 sec", "2 सेकंड दबाएँ"),
    S.HOLDING to Triple("பிடித்திரு…", "Holding…", "दबाए रखें…"),
    // Corrected: the volume key does NOT work with the screen off. Android
    // gives no app a volume key once the display sleeps, so the old wording
    // ("even with the app closed") promised something that cannot happen and
    // would have been believed at exactly the wrong moment. The power button
    // is the trigger that survives a sleeping screen.
    S.SOS_HINT_1 to Triple(
        "2 விநாடி அழுத்திப் பிடி. அல்லது பவர் பொத்தானை 5 முறை — திரை அணைந்திருந்தாலும்.",
        "Hold for 2 seconds. Or press the power button 5 times — works with the screen off.",
        "2 सेकंड दबाए रखें। या पावर बटन 5 बार दबाएँ — स्क्रीन बंद होने पर भी।",
    ),
    S.SOS_HINT_2 to Triple(
        "பாக்கெட்டில் இருந்தாலும் வேலை செய்யும். 10 விநாடிக்குள் நிறுத்தலாம்.",
        "Works from your pocket. You get 10 seconds to cancel.",
        "जेब से भी काम करता है। रद्द करने के लिए 10 सेकंड मिलते हैं।",
    ),
    S.EVERYTHING_ELSE to Triple("மற்றவை", "EVERYTHING ELSE", "अन्य सब"),

    S.T_CHART to Triple("வரைபடம்", "Sea chart", "समुद्री नक्शा"),
    S.T_STORM to Triple("புயல்", "Storm", "तूफ़ान"),
    S.T_FISH to Triple("மீன்", "Fish zones", "मछली क्षेत्र"),
    S.T_BOUNDARY to Triple("எல்லை", "Boundary", "समुद्री सीमा"),
    S.T_DRIFT to Triple("இழுவை", "Drift", "बहाव"),
    S.T_LIGHT to Triple("விளக்கு", "SOS light", "आपात रोशनी"),
    S.T_WAVE to Triple("அலை அளவு", "Measure sea", "लहर मापें"),
    S.T_FLEET to Triple("படகுகள்", "Fleet", "नौकाएँ"),
    S.T_ASK to Triple("குரலில் கேள்", "Ask by voice", "बोलकर पूछें"),
    S.T_WARN to Triple("பிறருக்கு", "Warn a boat", "दूसरी नाव को चेताएँ"),
    S.T_FENCE to Triple("தடை பகுதிகள்", "Restricted areas", "प्रतिबंधित क्षेत्र"),
    S.T_SETTINGS to Triple("அமைப்புகள்", "Settings", "सेटिंग्स"),

    S.NAV_HOME to Triple("முகப்பு", "Home", "होम"),
    S.NAV_CHART to Triple("வரைபடம்", "Chart", "नक्शा"),
    S.NAV_STORM to Triple("புயல்", "Storm", "तूफ़ान"),
    S.NAV_FENCE to Triple("தடை", "Fences", "सीमाएँ"),
    S.NAV_SOS to Triple("அவசரம்", "SOS", "आपात"),

    S.AGE_UNKNOWN to Triple("வயது தெரியவில்லை", "age unknown", "समय अज्ञात"),
    S.REFRESHING to Triple("புதுப்பிக்கிறது…", "refreshing…", "अपडेट हो रहा…"),
    S.MIN to Triple("நிமிடம்", "min", "मिनट"),
    S.HOUR to Triple("மணி", "h", "घंटे"),
    S.DAY to Triple("நாள்", "days", "दिन"),

    // The four verdicts. These mirror orca/phrase_ta.py's ACTION table and
    // are the rows a reviewer must check first -- an inverted negation here
    // sends a boat out in a sea that ORCA said to stay out of.
    S.ACT_GO to Triple("போகலாம்", "You may go", "जा सकते हैं"),
    S.ACT_NO to Triple("போக வேண்டாம்", "Do not go", "मत जाइए"),
    S.ACT_ALT to Triple("வேறு இடம்", "Better to avoid", "दूसरी जगह जाएँ"),
    S.ACT_UNKNOWN to Triple("தெரியவில்லை", "Cannot assess", "आकलन नहीं हो सका"),
)

/**
 * Look up a string.
 *
 * A missing entry returns the key name rather than an empty string or a
 * silent fallback to another language: a visible `T_FLEET` on screen gets
 * fixed, and a blank label does not.
 */
fun str(key: S, lang: Lang): String {
    val row = TABLE[key] ?: return key.name
    return when (lang) {
        Lang.TA -> row.first
        Lang.EN -> row.second
        Lang.HI -> row.third
    }
}

/** The verdict word for an action, in the chosen language. */
fun actionWord(action: String, lang: Lang): String = str(
    when (action) {
        "GO" -> S.ACT_GO
        "DO NOT GO" -> S.ACT_NO
        "SAFER ALTERNATIVE" -> S.ACT_ALT
        else -> S.ACT_UNKNOWN
    },
    lang,
)

/**
 * The one setting ORCA remembers between launches.
 *
 * Its own tiny store rather than OrcaRepository's, because a preference
 * has nothing to do with the advisory cache and must survive a bundle
 * being cleared.
 */
object Prefs {
    private const val FILE = "orca.prefs"
    private const val KEY_LANG = "lang"

    fun loadLang(context: android.content.Context): Lang {
        val code = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .getString(KEY_LANG, null) ?: return Lang.TA
        return Lang.entries.firstOrNull { it.code == code } ?: Lang.TA
    }

    fun saveLang(context: android.content.Context, lang: Lang) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.code).apply()
    }
}

/**
 * Pick one half of a "Tamil / English" or "Tamil · English" label.
 *
 * <p>WHY THIS EXISTS. Most of this app was written Tamil-first with the
 * English printed underneath, before there was a language setting. That
 * left 122 hard-coded bilingual literals across the screens, so switching
 * to English changed the navigation and nothing else: the Storm screen
 * still headlined in Tamil and every section header still read
 * "எங்கிருந்து / SOURCE".
 *
 * <p>Machine-translating a hundred safety strings into two languages
 * unreviewed would have been the worse fix. These labels ALREADY contain
 * a human-written pair, so this splits the pair rather than inventing
 * anything: Tamil gets the Tamil, English gets the English.
 *
 * <p>Hindi falls back to the ENGLISH half, deliberately. An unreviewed
 * Hindi guess at "Turn back now" is worse than English a reader can at
 * least recognise — and the strings that genuinely matter (the four
 * verdicts, the emergency wording) have real reviewed Hindi in TABLE
 * above, which takes precedence because callers look there first.
 *
 * <p>A label with no separator is returned unchanged, so this is safe to
 * apply anywhere.
 */
fun bi(label: String, lang: Lang): String {
    val sep = when {
        label.contains(" / ") -> " / "
        label.contains(" · ") -> " · "
        else -> return label
    }
    val i = label.indexOf(sep)
    val first = label.substring(0, i).trim()
    val second = label.substring(i + sep.length).trim()
    if (first.isEmpty() || second.isEmpty()) return label

    // Which half is Tamil? Tamil script starts at U+0B80.
    val firstIsTamil = first.any { it.code in 0x0B80..0x0BFF }
    val tamil = if (firstIsTamil) first else second
    val other = if (firstIsTamil) second else first

    return when (lang) {
        Lang.TA -> tamil
        Lang.EN, Lang.HI -> other
    }
}
