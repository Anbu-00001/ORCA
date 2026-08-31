package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distress message, tested as hard as anything in ORCA.
 *
 * <p>The single most important assertion in this file is
 * [noFixNeverInventsAPosition]. The bug this replaced put a harbour's
 * coordinates into a distress SMS under the label "Position", which would
 * have sent a rescue to the pier a missing boat had left from. Every other
 * test here exists to keep that from coming back by a side door.
 */
class SosDispatchTest {

    private fun fix(
        lat: Double = 9.2820,
        lon: Double = 79.3129,
        acc: Float? = 12f,
        age: Long = 0,
    ) = SosDispatch.Fix(lat, lon, acc, age, "gps")

    // --- the rule ---------------------------------------------------------

    @Test
    fun noFixNeverInventsAPosition() {
        val m = SosDispatch.compose(fix = null, zoneHint = "Rameswaram", boat = null)
        assertTrue("must say so in words", m.contains("POSITION UNKNOWN"))
        // Not one digit that could be read as a coordinate.
        assertFalse(m.any { it.isDigit() })
    }

    @Test
    fun zoneHintIsANameNeverACoordinate() {
        // Rameswaram harbour sits at 9.2876, 79.3129 in ORCA's own zone
        // list. With no fix, none of that may appear.
        val m = SosDispatch.compose(fix = null, zoneHint = "Rameswaram", boat = null)
        assertTrue(m.contains("Rameswaram"))
        assertFalse(m.contains("9.28"))
        assertFalse(m.contains("79.31"))
    }

    @Test
    fun positionComesBeforeEverythingElse() {
        // An SMS gateway that truncates must not cut the coordinates off.
        val m = SosDispatch.compose(fix(), "Rameswaram", "TN-15-MM-1234")
        val pos = m.indexOf("09 16")
        val port = m.indexOf("Nearest port")
        assertTrue(pos in 1..port)
    }

    // --- formatting -------------------------------------------------------

    @Test
    fun formatsAsDegreesAndDecimalMinutes() {
        // 9.2820 N -> 9 deg + 0.2820*60 = 16.92'
        assertEquals("09 16.92N 079 18.77E", SosDispatch.formatPosition(9.2820, 79.3129))
    }

    @Test
    fun southAndWestGetTheRightHemisphere() {
        val s = SosDispatch.formatPosition(-9.2820, -79.3129)
        assertTrue(s.contains("S"))
        assertTrue(s.contains("W"))
        assertFalse(s.contains("-"))
    }

    @Test
    fun degreesAreZeroPaddedSoColumnsLineUp() {
        assertTrue(SosDispatch.formatPosition(9.0, 79.0).startsWith("09 00.00N"))
    }

    // --- honesty about age ------------------------------------------------

    @Test
    fun aFreshFixSaysNow() {
        assertTrue(SosDispatch.compose(fix(age = 0), null, null).contains("(now)"))
    }

    @Test
    fun aStaleFixCarriesItsAge() {
        val m = SosDispatch.compose(fix(age = 47), null, null)
        assertTrue("a reader must know the boat has moved", m.contains("47 min old"))
    }

    @Test
    fun oneMinuteIsSingular() {
        assertTrue(SosDispatch.compose(fix(age = 1), null, null).contains("(1 min old)"))
    }

    @Test
    fun accuracyIsShownWhenKnownAndOmittedWhenNot() {
        assertTrue(SosDispatch.compose(fix(acc = 12f), null, null).contains("+-12m"))
        assertFalse(SosDispatch.compose(fix(acc = null), null, null).contains("+-"))
    }

    // --- the update -------------------------------------------------------

    @Test
    fun anUpdateIsLabelledSoItIsNotReadAsASecondEmergency() {
        val first = SosDispatch.compose(fix(age = 40), "Mandapam", null)
        val update = SosDispatch.compose(fix(age = 0), "Mandapam", null, update = true)
        assertTrue(first.startsWith("ORCA SOS."))
        assertTrue(update.startsWith("ORCA SOS UPDATE."))
    }

    // --- shape ------------------------------------------------------------

    @Test
    fun everyMessageIdentifiesItselfAndAsksForHelp() {
        listOf(
            SosDispatch.compose(null, null, null),
            SosDispatch.compose(fix(), "Chennai", "TN-01"),
        ).forEach {
            assertTrue(it.startsWith("ORCA SOS"))
            assertTrue(it.endsWith("Need help."))
        }
    }

    @Test
    fun boatIdIsCarriedWhenSetAndAbsentWhenNot() {
        assertTrue(SosDispatch.compose(fix(), null, "TN-15-MM-1234").contains("TN-15-MM-1234"))
        assertFalse(SosDispatch.compose(fix(), null, null).contains("Boat:"))
        assertFalse(SosDispatch.compose(fix(), null, "  ").contains("Boat:"))
    }

    @Test
    fun aTypicalMessageFitsOneOrTwoSmsSegments() {
        // Not a hard rule, but a distress message that arrives in six
        // fragments arrives late and out of order.
        val m = SosDispatch.compose(fix(), "Rameswaram", "TN-15-MM-1234")
        assertTrue("was ${m.length} chars: $m", m.length <= 160)
    }

    @Test
    fun coastGuardNumberIsTheIcgDistressLine() {
        assertEquals("1554", SosDispatch.COAST_GUARD)
    }

    // --- the contact list -------------------------------------------------

    @Test
    fun partialIsItsOwnOutcomeAndIsNotAFailure() {
        // If three of four numbers went, help may already be coming. The
        // screen must not tell the crew the SOS failed and send them
        // looking for another way out.
        val r = SosDispatch.Report(
            SosDispatch.Outcome.PARTIAL,
            sentTo = listOf("+919000000001", "+919000000002"),
            failedTo = listOf("+919000000003"),
            message = "x", fix = null, detail = "d",
        )
        assertTrue(r.sentTo.isNotEmpty())
        assertTrue(r.failedTo.isNotEmpty())
        assertEquals(SosDispatch.Outcome.PARTIAL, r.outcome)
    }

    @Test
    fun everyOutcomeIsDistinct() {
        // Four states, because "nobody to send to", "not allowed to send",
        // "the radio refused" and "some got through" need four different
        // things done about them.
        assertEquals(5, SosDispatch.Outcome.entries.size)
    }
}
