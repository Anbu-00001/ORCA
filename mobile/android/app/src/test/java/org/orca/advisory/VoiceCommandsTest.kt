package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice navigation.
 *
 * These test the part ORCA controls. Whether the phone's recogniser hears
 * Tamil correctly is not testable here and is not claimed anywhere: this
 * covers what happens to the string once it arrives.
 */
class VoiceCommandsTest {

    @Test
    fun `Tamil commands reach the right screen`() {
        assertEquals(Screen.MAP, VoiceCommands.screenFor("வரைபடம் காட்டு"))
        assertEquals(Screen.STORM, VoiceCommands.screenFor("புயல் வருகிறதா"))
        assertEquals(Screen.SOS, VoiceCommands.screenFor("அவசர உதவி வேண்டும்"))
        assertEquals(Screen.FISH, VoiceCommands.screenFor("மீன் எங்கே"))
    }

    @Test
    fun `English commands reach the right screen`() {
        assertEquals(Screen.MAP, VoiceCommands.screenFor("open the chart"))
        assertEquals(Screen.DRIFT, VoiceCommands.screenFor("my engine died"))
        assertEquals(Screen.SIGNAL, VoiceCommands.screenFor("turn on the torch"))
        assertEquals(Screen.SETTINGS, VoiceCommands.screenFor("settings please"))
    }

    @Test
    fun `Hindi commands reach the right screen`() {
        assertEquals(Screen.STORM, VoiceCommands.screenFor("तूफ़ान की चेतावनी"))
        assertEquals(Screen.SOS, VoiceCommands.screenFor("मदद चाहिए"))
        assertEquals(Screen.MAP, VoiceCommands.screenFor("नक्शा दिखाओ"))
    }

    @Test
    fun `Tamil agglutination still matches, because stems are used`() {
        // A recogniser returns whatever the speaker said. "வரைபடம்" and
        // "வரைபடத்தை" are the same request.
        assertEquals(Screen.MAP, VoiceCommands.screenFor("வரைபடத்தை திற"))
        assertEquals(Screen.SOS, VoiceCommands.screenFor("அவசரமாக உதவி"))
    }

    @Test
    fun `the longest phrase wins so short commands do not shadow long ones`() {
        // "sea state" contains no "sos", but this guards the ordering rule
        // that keeps two-word commands from losing to one-word ones.
        assertEquals(Screen.WAVE, VoiceCommands.screenFor("what is the sea state"))
        assertEquals(Screen.BOUNDARY, VoiceCommands.screenFor("how far is the boundary"))
    }

    @Test
    fun `an unrecognised utterance returns null, never a guess`() {
        // Navigating somewhere the crew did not ask for is worse than
        // admitting the command was not understood.
        assertNull(VoiceCommands.screenFor("what is the price of diesel"))
        assertNull(VoiceCommands.screenFor(""))
        assertNull(VoiceCommands.screenFor(null))
    }

    @Test
    fun `anything not a command is treated as a question`() {
        assertTrue(VoiceCommands.looksLikeQuestion("will it rain in Mandapam tomorrow"))
        assertFalse(VoiceCommands.looksLikeQuestion("open the chart"))
        assertFalse(VoiceCommands.looksLikeQuestion(null))
    }

    @Test
    fun `a harbour name is picked out independently of the command`() {
        val zones = listOf("Chennai", "Rameswaram", "Mandapam")
        assertEquals("Rameswaram", VoiceCommands.zoneFor("is Rameswaram safe today", zones))
        assertEquals("Mandapam", VoiceCommands.zoneFor("மண்டபத்தில் பாதுகாப்பானதா", zones))
        assertNull(VoiceCommands.zoneFor("is the sea rough", zones))
    }

    @Test
    fun `a zone ORCA does not cover is not invented`() {
        val zones = listOf("Chennai", "Rameswaram")
        assertNull(VoiceCommands.zoneFor("is Kochi safe", zones))
    }

    @Test
    fun `every screen worth speaking to has at least one phrase per language`() {
        // A command list that covers only English would make the feature
        // useless to exactly the users it exists for.
        VoiceCommands.COMMANDS.forEach { c ->
            val hasTamil = c.phrases.any { it.any { ch -> ch.code in 0x0B80..0x0BFF } }
            val hasDeva = c.phrases.any { it.any { ch -> ch.code in 0x0900..0x097F } }
            val hasLatin = c.phrases.any { it.any { ch -> ch in 'a'..'z' } }
            assertTrue("${c.screen} has no Tamil phrase", hasTamil)
            assertTrue("${c.screen} has no Hindi phrase", hasDeva)
            assertTrue("${c.screen} has no English phrase", hasLatin)
        }
    }
}
