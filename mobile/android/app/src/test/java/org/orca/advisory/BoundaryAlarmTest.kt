package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the boundary alarm speaks, and — mostly — when it does not.
 *
 * The tests that matter here are the SILENT ones. An alarm that fires
 * correctly and also fires forty times a day is an alarm that gets muted,
 * and a muted alarm is the same as no alarm. Every case below is a real
 * situation off the Palk Bay coast.
 */
class BoundaryAlarmTest {

    private val bands = BoundaryAlarm.Bands(urgentKm = 2.0, warningKm = 5.0, advisoryKm = 10.0)
    private val minute = 60_000L

    /** Fixes at one-minute spacing, oldest first, from the given distances. */
    private fun track(vararg distancesKm: Double): List<BoundaryAlarm.Fix> =
        distancesKm.mapIndexed { i, d -> BoundaryAlarm.Fix(i * minute, d) }

    // --- the band itself -------------------------------------------------

    @Test
    fun `bands come from the server and split where it says`() {
        assertEquals("urgent", BoundaryAlarm.bandFor(1.5, bands))
        assertEquals("warning", BoundaryAlarm.bandFor(4.0, bands))
        assertEquals("advisory", BoundaryAlarm.bandFor(8.0, bands))
        assertEquals("clear", BoundaryAlarm.bandFor(20.0, bands))
    }

    @Test
    fun `a band boundary is inclusive on the near side`() {
        // Exactly 2.0 km must be urgent, not warning. Off-by-one here is a
        // band the crew never gets.
        assertEquals("urgent", BoundaryAlarm.bandFor(2.0, bands))
        assertEquals("warning", BoundaryAlarm.bandFor(5.0, bands))
    }

    // --- closing speed ----------------------------------------------------

    @Test
    fun `closing speed is positive when the gap shrinks`() {
        // 8 -> 6 km over 2 minutes is 60 km/h. Fast, but the arithmetic is
        // what is under test.
        val c = BoundaryAlarm.closingKmh(track(8.0, 7.0, 6.0), 2 * minute)
        assertEquals(60.0, c!!, 0.1)
    }

    @Test
    fun `closing speed is negative when the boat is leaving`() {
        assertTrue(BoundaryAlarm.closingKmh(track(6.0, 7.0, 8.0), 2 * minute)!! < 0)
    }

    @Test
    fun `one fix says nothing about a heading, so closing speed is unknown`() {
        // Guessing a heading from a single point would invent the input the
        // whole suppression rule turns on.
        assertNull(BoundaryAlarm.closingKmh(track(4.0), 0))
        assertNull(BoundaryAlarm.closingKmh(emptyList(), 0))
    }

    @Test
    fun `stale fixes are not evidence of where the boat is going now`() {
        val old = listOf(
            BoundaryAlarm.Fix(0, 9.0),
            BoundaryAlarm.Fix(minute, 8.0),
        )
        // An hour later, both fixes are outside the window.
        assertNull(BoundaryAlarm.closingKmh(old, 60 * minute))
    }

    // --- the silences that keep the alarm trusted -------------------------

    @Test
    fun `a boat fishing PARALLEL to the line is not warned`() {
        // The exact case both reviews named as what kills adoption:
        // working a net at a steady 4.5 km, inside the warning band,
        // never going to cross.
        val d = BoundaryAlarm.decide(track(4.5, 4.5, 4.5, 4.5), bands, lastAnnouncedBand = null)
        assertEquals("warning", d.band)
        assertFalse("must stay silent while holding station", d.announce)
        assertTrue(d.why.contains("holding station"))
    }

    @Test
    fun `a boat leaving the boundary is not warned`() {
        val d = BoundaryAlarm.decide(track(3.0, 4.0, 4.5), bands, lastAnnouncedBand = null)
        assertFalse(d.announce)
        assertTrue(d.why.contains("opening"))
    }

    @Test
    fun `GPS noise at a band edge cannot chatter the alarm`() {
        // A single fix that dips into the band is not enough to speak.
        val d = BoundaryAlarm.decide(track(5.4, 4.9), bands, lastAnnouncedBand = null)
        assertEquals("warning", d.band)
        assertFalse(d.announce)
        assertTrue(d.why.contains("second fix"))
    }

    @Test
    fun `one fix inside a band never speaks, because closing is unknowable`() {
        val d = BoundaryAlarm.decide(track(4.0), bands, lastAnnouncedBand = null)
        assertFalse(d.announce)
    }

    // --- the alarms that must fire ----------------------------------------

    @Test
    fun `a boat closing on the line IS warned`() {
        val d = BoundaryAlarm.decide(track(6.0, 5.0, 4.0), bands, lastAnnouncedBand = null)
        assertEquals("warning", d.band)
        assertTrue(d.announce)
        assertTrue(d.closingKmh!! > 0)
        assertTrue(d.minutesToBoundary!! > 0)
    }

    @Test
    fun `the urgent band speaks whatever the heading`() {
        // At 2 km the reason you are there stops mattering. Even holding
        // station, even leaving, this one fires.
        val holding = BoundaryAlarm.decide(track(1.5, 1.5, 1.5), bands, lastAnnouncedBand = null)
        assertTrue(holding.announce)
        val leaving = BoundaryAlarm.decide(track(0.5, 1.0, 1.5), bands, lastAnnouncedBand = null)
        assertTrue(leaving.announce)
    }

    @Test
    fun `the urgent band does not repeat once it has been announced`() {
        val d = BoundaryAlarm.decide(track(1.5, 1.4, 1.3), bands, lastAnnouncedBand = "urgent")
        assertEquals("urgent", d.band)
        assertFalse(d.announce)
    }

    @Test
    fun `a fast approach re-speaks inside a band it already announced`() {
        // 4 km closing at 20 km/h is 12 minutes to the line. That is new
        // information even though the band has not changed.
        val fast = track(6.0, 5.0, 4.0).mapIndexed { i, f ->
            BoundaryAlarm.Fix(i * 6 * minute / 2, f.distanceKm)
        }
        val d = BoundaryAlarm.decide(
            fast, bands,
            lastAnnouncedBand = "warning",
            lastAnnouncedMinutes = 40.0,
        )
        assertTrue(d.minutesToBoundary!! <= BoundaryAlarm.URGENT_MINUTES)
        assertTrue("a fast approach is new information", d.announce)
    }

    @Test
    fun `it does not re-speak twice for the same fast approach`() {
        val fast = track(6.0, 5.0, 4.0).mapIndexed { i, f ->
            BoundaryAlarm.Fix(i * 6 * minute / 2, f.distanceKm)
        }
        val d = BoundaryAlarm.decide(
            fast, bands,
            lastAnnouncedBand = "warning",
            lastAnnouncedMinutes = 12.0,
        )
        assertFalse(d.announce)
    }

    @Test
    fun `well clear of the boundary nothing fires and nothing is claimed`() {
        val d = BoundaryAlarm.decide(track(30.0, 29.0, 28.0), bands, lastAnnouncedBand = null)
        assertEquals("clear", d.band)
        assertFalse(d.announce)
    }

    @Test
    fun `no position at all is a stated state, not a silent clear`() {
        val d = BoundaryAlarm.decide(emptyList(), bands, lastAnnouncedBand = null)
        assertFalse(d.announce)
        assertTrue(d.why.contains("No position"))
    }

    // --- what actually gets said -------------------------------------------

    @Test
    fun `the spoken warning carries minutes, which is the actionable number`() {
        val d = BoundaryAlarm.decide(track(6.0, 5.0, 4.0), bands, lastAnnouncedBand = null)
        val en = BoundaryAlarm.message(d, 4.0, tamil = false)
        assertTrue(en.contains("minutes"))
        assertTrue(en.contains("Turn west"))
    }

    @Test
    fun `every band has Tamil that leads with the action`() {
        for (dist in listOf(1.0, 4.0, 8.0)) {
            val d = BoundaryAlarm.decide(track(dist + 2, dist + 1, dist), bands, lastAnnouncedBand = null)
            val ta = BoundaryAlarm.message(d, dist, tamil = true)
            assertTrue("band ${d.band} produced '$ta'", ta.isNotBlank() && ta[0].code > 0x0B80)
        }
    }

    @Test
    fun `a message with no time estimate simply omits it rather than saying zero`() {
        val d = BoundaryAlarm.decide(track(4.5, 4.5, 4.5), bands, lastAnnouncedBand = null)
        assertNull(d.minutesToBoundary)
        val en = BoundaryAlarm.message(d, 4.5, tamil = false)
        assertFalse(en.contains("0 minutes"))
    }
}
