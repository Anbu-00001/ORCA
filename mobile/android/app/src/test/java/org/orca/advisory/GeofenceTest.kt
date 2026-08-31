package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four fences, and the rules for when they are allowed to shout.
 *
 * The repeat-and-acknowledge rules get the most attention here. An alarm
 * that cannot be silenced ends up in a locker, and an alarm that stays
 * silent after the situation worsens is the reason the crew stopped
 * trusting it.
 */
class GeofenceTest {

    private val minute = 60_000L

    private fun hazard(
        kind: Geofence.Kind = Geofence.Kind.IMBL,
        band: Geofence.Band = Geofence.Band.WARNING,
        km: Double = 4.0,
    ) = Geofence.Hazard(kind, band, km)

    // --- bands --------------------------------------------------------------

    @Test
    fun `bands split where the server says and are inclusive on the near side`() {
        assertEquals(Geofence.Band.URGENT, Geofence.bandForDistance(2.0, 2.0, 5.0, 10.0))
        assertEquals(Geofence.Band.WARNING, Geofence.bandForDistance(5.0, 2.0, 5.0, 10.0))
        assertEquals(Geofence.Band.ADVISORY, Geofence.bandForDistance(9.9, 2.0, 5.0, 10.0))
        assertEquals(Geofence.Band.CLEAR, Geofence.bandForDistance(30.0, 2.0, 5.0, 10.0))
    }

    @Test
    fun `being inside a fence outranks merely being near one`() {
        assertTrue(Geofence.severity(Geofence.Band.INSIDE) < Geofence.severity(Geofence.Band.URGENT))
        assertTrue(Geofence.severity(Geofence.Band.URGENT) < Geofence.severity(Geofence.Band.WARNING))
    }

    @Test
    fun `the worst hazard is the one reported, not the first`() {
        val worst = Geofence.worst(
            listOf(
                hazard(Geofence.Kind.COVERAGE, Geofence.Band.ADVISORY),
                hazard(Geofence.Kind.PARK, Geofence.Band.INSIDE),
                hazard(Geofence.Kind.IMBL, Geofence.Band.WARNING),
            ),
        )
        assertEquals(Geofence.Kind.PARK, worst!!.kind)
    }

    @Test
    fun `all clear reports nothing rather than a reassuring hazard`() {
        assertNull(Geofence.worst(listOf(hazard(band = Geofence.Band.CLEAR))))
        assertNull(Geofence.worst(emptyList()))
    }

    // --- speaking, repeating, acknowledging ---------------------------------

    @Test
    fun `a clear fence never speaks`() {
        val d = Geofence.decide(hazard(band = Geofence.Band.CLEAR), emptyMap(), 0)
        assertFalse(d.announce)
    }

    @Test
    fun `a fence never warned about before speaks at once`() {
        assertTrue(Geofence.decide(hazard(), emptyMap(), 0).announce)
    }

    @Test
    fun `an acknowledged fence stays quiet for fifteen minutes`() {
        val acks = mapOf(Geofence.Kind.IMBL to Geofence.Ack(Geofence.Band.WARNING, 0))
        assertFalse(Geofence.decide(hazard(), acks, 5 * minute).announce)
        assertFalse(Geofence.decide(hazard(), acks, 14 * minute).announce)
    }

    @Test
    fun `it speaks again after fifteen minutes, because a course is long`() {
        val acks = mapOf(Geofence.Kind.IMBL to Geofence.Ack(Geofence.Band.WARNING, 0))
        assertTrue(Geofence.decide(hazard(), acks, 15 * minute).announce)
        assertTrue(Geofence.decide(hazard(), acks, 40 * minute).announce)
    }

    @Test
    fun `a worsening fence speaks immediately, acknowledged or not`() {
        // The rule that matters most: an acknowledgement covers the
        // situation the crew SAW. Closing from 4 km to 1 km is a
        // different situation and must not inherit the silence.
        val acks = mapOf(Geofence.Kind.IMBL to Geofence.Ack(Geofence.Band.WARNING, 0))
        val closer = hazard(band = Geofence.Band.URGENT, km = 1.5)
        val d = Geofence.decide(closer, acks, 1 * minute)
        assertTrue(d.announce)
        assertTrue(d.why.contains("worsened"))
    }

    @Test
    fun `an improving fence does NOT re-speak`() {
        // Moving from urgent back out to warning is good news, and good
        // news does not need an alarm.
        val acks = mapOf(Geofence.Kind.IMBL to Geofence.Ack(Geofence.Band.URGENT, 0))
        assertFalse(Geofence.decide(hazard(band = Geofence.Band.WARNING), acks, 2 * minute).announce)
    }

    @Test
    fun `acknowledging one fence does not silence another`() {
        // Silencing the boundary must not silence an incoming storm.
        val acks = mapOf(Geofence.Kind.IMBL to Geofence.Ack(Geofence.Band.WARNING, 0))
        assertTrue(Geofence.decide(hazard(Geofence.Kind.STORM), acks, 1 * minute).announce)
        assertTrue(Geofence.decide(hazard(Geofence.Kind.PARK), acks, 1 * minute).announce)
    }

    // --- geometry ------------------------------------------------------------

    @Test
    fun `a point inside the marine park is inside`() {
        assertTrue(Geofence.pointInPolygon(9.20, 79.17, Geofence.MARINE_PARK))
    }

    @Test
    fun `a point outside the marine park is outside`() {
        assertFalse(Geofence.pointInPolygon(9.30, 79.17, Geofence.MARINE_PARK))
        assertFalse(Geofence.pointInPolygon(9.20, 79.30, Geofence.MARINE_PARK))
    }

    @Test
    fun `Rameswaram and Mandapam are both outside the park box`() {
        // They are real ORCA zones. If either fell inside, every trip from
        // those harbours would open with a prohibited-area alarm.
        assertFalse(Geofence.pointInPolygon(9.2876, 79.3129, Geofence.MARINE_PARK))  // Rameswaram
        assertFalse(Geofence.pointInPolygon(9.2772, 79.1250, Geofence.MARINE_PARK))  // Mandapam
    }

    @Test
    fun `distance to a line is zero on the line and grows away from it`() {
        val seg = listOf(listOf(9.0 to 79.0, 10.0 to 79.0))
        assertEquals(0.0, Geofence.distanceToLineKm(9.5, 79.0, seg)!!, 0.01)
        val near = Geofence.distanceToLineKm(9.5, 79.1, seg)!!
        val far = Geofence.distanceToLineKm(9.5, 79.5, seg)!!
        assertTrue(far > near && near > 0)
    }

    @Test
    fun `distance to a line clamps to the segment ends`() {
        // A point well north of a short segment must measure to its END,
        // not to the infinite line it lies on -- otherwise a boat far past
        // the end of the treaty line reads as being on it.
        val seg = listOf(listOf(9.0 to 79.0, 9.1 to 79.0))
        val d = Geofence.distanceToLineKm(10.0, 79.0, seg)!!
        assertEquals(0.9 * 111.32, d, 1.0)
    }

    @Test
    fun `no segments means no distance, not zero`() {
        // Zero would read as "you are exactly on the boundary".
        assertNull(Geofence.distanceToLineKm(9.5, 79.0, emptyList()))
    }

    // --- coverage -------------------------------------------------------------

    @Test
    fun `the coverage edge is the measured fifteen kilometres`() {
        assertEquals(15.0, Geofence.COVERAGE_EDGE_KM, 0.0)
        // Which is about 8 NM -- the number the screen actually shows.
        assertEquals(8.1, Units.kmToNm(Geofence.COVERAGE_EDGE_KM), 0.1)
    }

    @Test
    fun `the repeat interval is the fifteen minutes the UI promises`() {
        assertEquals(15 * 60 * 1000L, Geofence.REPEAT_MS)
    }
}
