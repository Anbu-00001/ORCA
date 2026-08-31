package org.orca.advisory

/**
 * Turning what the recogniser heard into something the app does.
 *
 * <p>Pure string matching over a CLOSED SET of phrases. No model, no
 * inference, no network of its own. That is the whole design: whatever
 * speech engine is installed hands back a string, and from there the
 * behaviour is deterministic and testable, so "voice navigation works"
 * does not depend on a claim about anyone's acoustic model.
 *
 * <p>WHAT THIS DOES NOT DO, stated plainly because the distinction is the
 * difference between a working feature and a demo that fails on stage:
 *
 * <ul>
 *  <li>It does not do speech-to-text. Android's recogniser does that, and
 *      whether it works offline depends on packs the phone owner has
 *      downloaded. See VoiceProbe.
 *  <li>It does not understand free-form questions. A question that is not
 *      a command is handed to the existing /ask path, which has its own
 *      deterministic floor in orca/extract.py.
 *  <li>It never decides a verdict. It picks a SCREEN.
 * </ul>
 *
 * <p>MATCHING IS SUBSTRING, NOT EQUALITY, and stems rather than whole
 * words. Tamil is agglutinative -- "வரைபடம்" becomes "வரைபடத்தை" -- and a
 * recogniser will return whichever the speaker used. Matching on the stem
 * catches both. The same trick is already used for harbour names in
 * TamilNames and in orca/phrase_ta.py.
 */
object VoiceCommands {

    data class Command(val screen: Screen, val phrases: List<String>)

    /**
     * Every phrase that navigates, in all three languages.
     *
     * Longest phrase wins, so "storm warning" beats a bare "warning" and
     * a two-word command is never shadowed by a one-word one.
     */
    val COMMANDS: List<Command> = listOf(
        Command(Screen.VERDICT, listOf(
            "போகலாம", "போகல", "இன்ன", "verdict", "can i go", "should i go", "today",
            "जा सकत", "फैसला", "आज",
        )),
        Command(Screen.MAP, listOf(
            "வரைபட", "படம", "chart", "map", "नक्श", "मानचित्र",
        )),
        Command(Screen.STORM, listOf(
            "புயல", "எச்சரிக்க", "storm", "cyclone", "warning", "तूफ़ान", "तूफान", "चेतावनी",
        )),
        Command(Screen.FISH, listOf(
            "மீன", "fish", "pfz", "मछली",
        )),
        Command(Screen.BOUNDARY, listOf(
            "எல்ல", "கடல் எல்ல", "boundary", "border", "imbl", "सीमा",
        )),
        Command(Screen.FENCE, listOf(
            "தடை", "restricted", "fence", "no go", "प्रतिबंध",
        )),
        Command(Screen.DRIFT, listOf(
            "இழுவ", "இயந்திர", "drift", "engine", "adrift", "बहाव", "इंजन",
        )),
        Command(Screen.SIGNAL, listOf(
            "விளக்க", "light", "torch", "flash", "रोशनी", "बत्ती",
        )),
        Command(Screen.SOS, listOf(
            "அவசர", "உதவ", "sos", "help", "emergency", "आपात", "मदद", "बचाओ",
        )),
        Command(Screen.WAVE, listOf(
            "அலை அளவ", "wave", "sea state", "लहर",
        )),
        Command(Screen.FLEET, listOf(
            "படகுகள", "fleet", "nearby", "नौका",
        )),
        Command(Screen.SETTINGS, listOf(
            "அமைப்ப", "settings", "setting", "सेटिंग",
        )),
        Command(Screen.HOME, listOf(
            "முகப்ப", "home", "back", "होम", "वापस",
        )),
    )

    /**
     * Which screen, if any, the crew asked for.
     *
     * Returns null when nothing matched -- and null must stay null. The
     * caller then treats the utterance as a QUESTION rather than guessing
     * at a screen, because navigating somewhere the user did not ask for
     * is worse than admitting the command was not understood.
     */
    fun screenFor(spoken: String?): Screen? {
        if (spoken.isNullOrBlank()) return null
        val s = spoken.lowercase().trim()
        var best: Pair<Screen, Int>? = null
        COMMANDS.forEach { c ->
            c.phrases.forEach { phrase ->
                if (s.contains(phrase.lowercase())) {
                    // Longest match wins: "sea state" must not lose to "sos".
                    if (best == null || phrase.length > best!!.second) {
                        best = c.screen to phrase.length
                    }
                }
            }
        }
        return best?.first
    }

    /**
     * A harbour name inside the utterance, if there is one.
     *
     * Separate from the screen, because "is Rameswaram safe" is both a
     * navigation AND a zone selection, and the two are independent.
     */
    fun zoneFor(spoken: String?, zones: List<String>): String? {
        if (spoken.isNullOrBlank()) return null
        val s = spoken.lowercase()
        zones.firstOrNull { s.contains(it.lowercase()) }?.let { return it }
        return TamilNames.stemFor(spoken)?.takeIf { it in zones }
    }

    /**
     * Is this a question rather than a command?
     *
     * Used only to decide whether to hand the text to /ask. Deliberately
     * generous: anything that is not a recognised command is treated as a
     * question, because /ask can say "I did not understand" and a wrong
     * screen cannot.
     */
    fun looksLikeQuestion(spoken: String?): Boolean =
        !spoken.isNullOrBlank() && screenFor(spoken) == null
}
