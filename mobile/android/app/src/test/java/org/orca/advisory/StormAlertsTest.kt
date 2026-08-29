package org.orca.advisory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Matching IMD's storm warnings to this boat.
 *
 * The property under test is not "does it find alerts" -- most days there
 * are none over Tamil Nadu, and that is the correct answer. It is that the
 * buckets stay apart: a warning that cannot be geolocated must never be
 * counted as covering you, a warning 700 km away must never render as
 * overhead, and "we did not check" must never render as "all clear".
 */
class StormAlertsTest {

    private val now: Instant = Instant.parse("2026-08-30T06:00:00Z")
    private val later = "2026-08-31T06:00:00+00:00"
    private val earlier = "2026-08-29T06:00:00+00:00"

    /** A square over the Bay of Bengal, offshore of Chennai. */
    private val square = "[[12.0,80.0],[14.0,80.0],[14.0,82.0],[12.0,82.0],[12.0,80.0]]"

    private fun feedJson(
        vararg alerts: String,
        present: Boolean = true,
    ): JSONObject {
        if (!present) return JSONObject("""{"zones":[]}""")
        return JSONObject(
            """{"alerts":{"source":"India Meteorological Department (CAP v1.2 public feed)",
               "provenance":"https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml",
               "fetched_at":"2026-08-30T05:00:00+00:00",
               "alerts":[${alerts.joinToString(",")}]}}"""
        )
    }

    private fun alert(
        severity: String = "Severe",
        expires: String? = later,
        polygon: String? = square,
        event: String = "Cyclone",
    ): String {
        val exp = if (expires == null) "null" else "\"$expires\""
        val poly = polygon ?: "null"
        return """{"identifier":"urn:oid:test","event":"$event","headline":"test warning",
                   "description":"d","instruction":"i","severity":"$severity",
                   "urgency":"Expected","certainty":"Likely","area_desc":"TEST",
                   "expires":$exp,"sender_name":"IMD","web":null,
                   "provenance":"https://example.invalid/a.xml","signed":true,
                   "polygon":$poly}"""
    }

    // --- geometry -------------------------------------------------------

    @Test
    fun `a point inside the polygon is inside`() {
        val poly = listOf(12.0 to 80.0, 14.0 to 80.0, 14.0 to 82.0, 12.0 to 82.0)
        assertTrue(StormAlerts.pointInPolygon(13.0, 81.0, poly))
    }

    @Test
    fun `a point outside the polygon is outside`() {
        val poly = listOf(12.0 to 80.0, 14.0 to 80.0, 14.0 to 82.0, 12.0 to 82.0)
        assertFalse(StormAlerts.pointInPolygon(13.0, 79.0, poly))
        assertFalse(StormAlerts.pointInPolygon(20.0, 81.0, poly))
    }

    @Test
    fun `a degenerate polygon contains nothing rather than everything`() {
        // Returning true here would put every boat inside a malformed warning.
        assertFalse(StormAlerts.pointInPolygon(13.0, 81.0, listOf(12.0 to 80.0, 14.0 to 82.0)))
        assertFalse(StormAlerts.pointInPolygon(13.0, 81.0, emptyList()))
    }

    @Test
    fun `distance to a polygon is zero at its own vertex and grows away from it`() {
        val poly = listOf(12.0 to 80.0, 14.0 to 80.0, 14.0 to 82.0)
        assertEquals(0.0, StormAlerts.distanceToPolygonKm(12.0, 80.0, poly), 0.001)
        assertTrue(
            StormAlerts.distanceToPolygonKm(13.0, 70.0, poly) >
                StormAlerts.distanceToPolygonKm(13.0, 79.0, poly)
        )
    }

    // --- the three buckets ------------------------------------------------

    @Test
    fun `a warning over your head is covering`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert())), now)
        assertTrue(m.checked)
        assertEquals(1, m.covering.size)
        assertEquals(0.0, m.covering[0].distanceKm, 0.0)
        assertTrue(m.elsewhere.isEmpty())
    }

    @Test
    fun `a warning somewhere else is elsewhere, with a real distance`() {
        val m = StormAlerts.match(13.0, 70.0, StormAlerts.parseFeed(feedJson(alert())), now)
        assertTrue(m.covering.isEmpty())
        assertEquals(1, m.elsewhere.size)
        assertTrue(m.elsewhere[0].distanceKm > 500)
    }

    @Test
    fun `a warning with no polygon is never counted as covering you`() {
        // CAP allows an alert to name only a geocode. Containment cannot be
        // tested against that, so the app says so instead of assuming.
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert(polygon = null))), now)
        assertTrue(m.covering.isEmpty())
        assertTrue(m.elsewhere.isEmpty())
        assertEquals(1, m.ungeolocated.size)
    }

    @Test
    fun `an expired warning is dropped from every bucket`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert(expires = earlier))), now)
        assertTrue(m.covering.isEmpty())
        assertTrue(m.ungeolocated.isEmpty())
        assertTrue(m.elsewhere.isEmpty())
    }

    @Test
    fun `a warning with no expiry is kept, because absent is not expired`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert(expires = null))), now)
        assertEquals(1, m.covering.size)
    }

    @Test
    fun `an unparseable expiry keeps the warning rather than dropping it`() {
        // The one failure mode here that could kill someone: discarding a
        // live storm warning because its timestamp did not parse.
        assertFalse(StormAlerts.hasExpired("not-a-timestamp", now))
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert(expires = "garbage"))), now)
        assertEquals(1, m.covering.size)
    }

    @Test
    fun `elsewhere is sorted nearest first`() {
        val far = alert(polygon = "[[0.0,60.0],[1.0,60.0],[1.0,61.0],[0.0,60.0]]")
        val m = StormAlerts.match(13.0, 79.0, StormAlerts.parseFeed(feedJson(far, alert())), now)
        assertEquals(m.elsewhere.map { it.distanceKm }.sorted(), m.elsewhere.map { it.distanceKm })
    }

    @Test
    fun `covering is sorted most severe first`() {
        val m = StormAlerts.match(
            13.0, 81.0,
            StormAlerts.parseFeed(feedJson(alert(severity = "Minor"), alert(severity = "Extreme"))),
            now,
        )
        assertEquals("Extreme", m.covering[0].severity)
        assertEquals("Extreme", m.worstSeverity)
    }

    // --- not checked is not all clear -------------------------------------

    @Test
    fun `a bundle with no alerts block reports NOT CHECKED, not all clear`() {
        // The distinction the whole feature rests on.
        assertNull(StormAlerts.parseFeed(feedJson(present = false)))
        val m = StormAlerts.match(13.0, 81.0, null, now)
        assertFalse(m.checked)
        assertNotNull(m.reason)
        assertTrue(m.reason!!.contains("not the same"))
        assertTrue(m.covering.isEmpty())
    }

    @Test
    fun `an empty feed is checked, with nothing found`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson()), now)
        assertTrue(m.checked)
        assertNull(m.reason)
        assertTrue(m.covering.isEmpty())
    }

    @Test
    fun `a match carries the feed source and fetch time`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert())), now)
        assertTrue(m.source!!.contains("India Meteorological Department"))
        assertTrue(m.fetchedAt!!.isNotEmpty())
    }

    @Test
    fun `the signature flag survives parsing, because provenance is the point`() {
        val m = StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson(alert())), now)
        assertTrue(m.covering[0].signed)
        assertTrue(m.covering[0].provenance.startsWith("https://"))
    }

    // --- severity ----------------------------------------------------------

    @Test
    fun `severity ranks put the worst first and the unknown last`() {
        assertTrue(StormAlerts.severityRank("Extreme") < StormAlerts.severityRank("Severe"))
        assertTrue(StormAlerts.severityRank("Minor") < StormAlerts.severityRank("Weird"))
    }

    @Test
    fun `worst severity of nothing is null, not a reassuring word`() {
        assertNull(StormAlerts.match(13.0, 81.0, StormAlerts.parseFeed(feedJson()), now).worstSeverity)
    }

    @Test
    fun `every CAP severity has a Tamil name`() {
        for (s in listOf("Extreme", "Severe", "Moderate", "Minor", "Unknown", "Weird")) {
            val ta = StormAlerts.severityTamil(s)
            assertTrue("$s produced '$ta'", ta.isNotBlank() && ta[0].code > 0x0B80)
        }
    }

    @Test
    fun `garbage in the alerts array is skipped without losing the good ones`() {
        val feed = StormAlerts.parseFeed(feedJson("""{"broken":true}""", alert()))
        assertNotNull(feed)
        val m = StormAlerts.match(13.0, 81.0, feed, now)
        // The malformed entry has no polygon, so it lands in ungeolocated
        // rather than being silently dropped or crashing the parse.
        assertEquals(1, m.covering.size)
    }
}
