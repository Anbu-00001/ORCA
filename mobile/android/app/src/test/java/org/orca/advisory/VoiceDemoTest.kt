package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five spoken commands.
 *
 * <p>The assertion that matters most is [nonsenseIsNotHeardAsHelp]: an
 * unrecognised phrase must return null, never the nearest match. One of
 * the five is a distress call, and a mis-heard word must not be able to
 * raise one.
 */
class VoiceDemoTest {

    @Test
    fun tamilStemsMatchEachIntent() {
        assertEquals(VoiceDemo.Intent.SEA, VoiceDemo.match("கடல் எப்படி இருக்கு"))
        assertEquals(VoiceDemo.Intent.FISH, VoiceDemo.match("மீன் எங்கே"))
        assertEquals(VoiceDemo.Intent.STORM, VoiceDemo.match("புயல் இருக்கா"))
        assertEquals(VoiceDemo.Intent.POSITION, VoiceDemo.match("நான் எங்கே இருக்கேன்"))
        assertEquals(VoiceDemo.Intent.HELP, VoiceDemo.match("உதவி வேணும்"))
    }

    @Test
    fun agglutinatedFormsStillMatch() {
        // Tamil inflects: கடல் -> கடலில், மீன் -> மீன்பிடி. A recogniser
        // returns whatever the speaker actually said.
        assertEquals(VoiceDemo.Intent.SEA, VoiceDemo.match("கடலில் அலை"))
        assertEquals(VoiceDemo.Intent.FISH, VoiceDemo.match("மீன்பிடி பகுதி"))
        assertEquals(VoiceDemo.Intent.HELP, VoiceDemo.match("அவசரம் அவசரம்"))
    }

    @Test
    fun englishWorksBecauseTheRecogniserMayReturnIt() {
        assertEquals(VoiceDemo.Intent.SEA, VoiceDemo.match("how is the sea"))
        assertEquals(VoiceDemo.Intent.FISH, VoiceDemo.match("where are the fish"))
        assertEquals(VoiceDemo.Intent.STORM, VoiceDemo.match("is there a storm"))
        assertEquals(VoiceDemo.Intent.POSITION, VoiceDemo.match("where am i"))
        assertEquals(VoiceDemo.Intent.HELP, VoiceDemo.match("help"))
    }

    @Test
    fun caseAndPaddingDoNotMatter() {
        assertEquals(VoiceDemo.Intent.STORM, VoiceDemo.match("  IS THERE A STORM  "))
    }

    // --- the safety rule ---------------------------------------------------

    @Test
    fun nonsenseIsNotHeardAsHelp() {
        // The single most important test in this file.
        listOf("banana", "play some music", "காலை வணக்கம்", "xyzzy", "12345").forEach {
            assertNull("must not guess an intent from: $it", VoiceDemo.match(it))
        }
    }

    @Test
    fun emptyAndNullAreNotCommands() {
        assertNull(VoiceDemo.match(null))
        assertNull(VoiceDemo.match(""))
        assertNull(VoiceDemo.match("   "))
    }

    @Test
    fun longestMatchWins() {
        // "warning" (7) beats nothing else here; the point is that a
        // longer, more specific phrase is preferred over a short stem.
        assertEquals(VoiceDemo.Intent.POSITION, VoiceDemo.match("where am i right now"))
    }

    // --- presentation ------------------------------------------------------

    @Test
    fun everyIntentHasATamilAndEnglishExample() {
        VoiceDemo.Intent.entries.forEach { i ->
            assertNotNull(VoiceDemo.example(i, Lang.TA))
            assertNotNull(VoiceDemo.example(i, Lang.EN))
            assertNotNull(VoiceDemo.title(i, Lang.TA))
            assertNotNull(VoiceDemo.title(i, Lang.EN))
            // Tamil and English must actually differ, or the toggle is a lie.
            org.junit.Assert.assertNotEquals(
                VoiceDemo.example(i, Lang.TA), VoiceDemo.example(i, Lang.EN),
            )
        }
    }

    @Test
    fun everyIntentHasPhrases() {
        VoiceDemo.Intent.entries.forEach {
            org.junit.Assert.assertTrue(
                "no phrases for $it", VoiceDemo.PHRASES[it]?.isNotEmpty() == true,
            )
        }
    }

    @Test
    fun thereAreExactlyFiveCommands() {
        assertEquals(5, VoiceDemo.Intent.entries.size)
    }
}
