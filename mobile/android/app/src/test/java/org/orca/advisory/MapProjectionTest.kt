package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The chart's lat/lon -> pixel maths.
 *
 * A projection bug here does not crash and does not look broken. It draws
 * a plausible search box in the wrong piece of sea, or mirrors the coast
 * north-south in a way nobody notices on open water. That is the same
 * class of silent error as a flipped wind convention, so it gets the same
 * treatment: closed-form tests, expected values worked out by hand.
 */
class MapProjectionTest {

    private val width = 1000f
    private val height = 500f

    // --- the axes point the right way ------------------------------------

    @Test
    fun `the centre of the window lands in the centre of the canvas`() {
        val x = MapProjection.xFor(80.0, 80.0, 1.0, width)
        val y = MapProjection.yFor(10.0, 10.0, 1.0, height)
        assertEquals(width / 2, x, 0.01f)
        assertEquals(height / 2, y, 0.01f)
    }

    @Test
    fun `north is UP, because screen y grows downward and latitude does not`() {
        // The single most likely sign error in the whole file.
        val north = MapProjection.yFor(11.0, 10.0, 2.0, height)
        val south = MapProjection.yFor(9.0, 10.0, 2.0, height)
        assertTrue("a northern point must be higher on screen", north < south)
    }

    @Test
    fun `east is RIGHT`() {
        val east = MapProjection.xFor(81.0, 80.0, 2.0, width)
        val west = MapProjection.xFor(79.0, 80.0, 2.0, width)
        assertTrue(east > west)
    }

    @Test
    fun `the window edges land exactly on the canvas edges`() {
        assertEquals(0f, MapProjection.xFor(79.0, 80.0, 1.0, width), 0.01f)
        assertEquals(width, MapProjection.xFor(81.0, 80.0, 1.0, width), 0.01f)
        assertEquals(0f, MapProjection.yFor(11.0, 10.0, 1.0, height), 0.01f)
        assertEquals(height, MapProjection.yFor(9.0, 10.0, 1.0, height), 0.01f)
    }

    // --- the cos(lat) correction -----------------------------------------

    @Test
    fun `longitude is stretched by one over cos of latitude`() {
        // At 10 N, cos(10) = 0.9848. For a square canvas (aspect 1) and a
        // half-height of 1 degree, half-width must be 1 / 0.9848 = 1.0154.
        val half = MapProjection.halfLon(halfLat = 1.0, centreLat = 10.0, aspect = 1.0)
        assertEquals(1.0 / Math.cos(Math.toRadians(10.0)), half, 1e-9)
        assertEquals(1.0154, half, 1e-4)
    }

    @Test
    fun `a wider canvas shows proportionally more longitude`() {
        val square = MapProjection.halfLon(1.0, 10.0, aspect = 1.0)
        val wide = MapProjection.halfLon(1.0, 10.0, aspect = 2.0)
        assertEquals(2 * square, wide, 1e-9)
    }

    @Test
    fun `the correction grows toward the pole and is near unity at the equator`() {
        assertEquals(1.0, MapProjection.halfLon(1.0, 0.0, 1.0), 1e-9)
        assertTrue(MapProjection.halfLon(1.0, 60.0, 1.0) > MapProjection.halfLon(1.0, 10.0, 1.0))
    }

    @Test
    fun `a square of sea comes out square on a square canvas`() {
        // The property that actually matters: a drift box 20 km on a side
        // must not be drawn as a rectangle. At 10 N, 0.1 deg of latitude
        // is ~11.1 km; the matching longitude span is 0.1 / cos(10).
        val centreLat = 10.0
        val halfLat = 0.5
        val halfLon = MapProjection.halfLon(halfLat, centreLat, aspect = 1.0)
        val side = 500f

        val dLat = 0.1
        val dLon = dLat / Math.cos(Math.toRadians(centreLat))

        val hPx = abs(MapProjection.yFor(centreLat + dLat, centreLat, halfLat, side) -
                      MapProjection.yFor(centreLat, centreLat, halfLat, side))
        val wPx = abs(MapProjection.xFor(80.0 + dLon, 80.0, halfLon, side) -
                      MapProjection.xFor(80.0, 80.0, halfLon, side))
        assertEquals("equal ground distances must draw equal pixel lengths", hPx, wPx, 0.5f)
    }

    // --- culling ----------------------------------------------------------

    @Test
    fun `a point inside the window is visible`() {
        assertTrue(MapProjection.visible(10.0, 80.0, 10.0, 80.0, 1.0, 1.0, 0.0))
    }

    @Test
    fun `a point outside the window is culled`() {
        assertFalse(MapProjection.visible(20.0, 80.0, 10.0, 80.0, 1.0, 1.0, 0.0))
        assertFalse(MapProjection.visible(10.0, 90.0, 10.0, 80.0, 1.0, 1.0, 0.0))
    }

    @Test
    fun `the margin keeps edge cells from popping in and out`() {
        // A grid cell whose centre is just outside must still be drawn, or
        // the coast visibly frays along the edge of the canvas.
        assertFalse(MapProjection.visible(11.1, 80.0, 10.0, 80.0, 1.0, 1.0, margin = 0.0))
        assertTrue(MapProjection.visible(11.1, 80.0, 10.0, 80.0, 1.0, 1.0, margin = 0.2))
    }

    // --- seabed bands ------------------------------------------------------

    @Test
    fun `zero and above is land`() {
        assertEquals(MapProjection.LAND, MapProjection.band(0))
        assertEquals(MapProjection.LAND, MapProjection.band(140))
    }

    @Test
    fun `the shelf band ends at sixty metres and the shelf edge at two hundred`() {
        assertEquals(MapProjection.SHELF, MapProjection.band(-1))
        assertEquals(MapProjection.SHELF, MapProjection.band(-59))
        assertEquals(MapProjection.COASTAL, MapProjection.band(-60))
        assertEquals(MapProjection.COASTAL, MapProjection.band(-199))
        assertEquals(MapProjection.DEEP, MapProjection.band(-200))
        assertEquals(MapProjection.DEEP, MapProjection.band(-3000))
    }

    @Test
    fun `every band is distinct, so the chart cannot render flat`() {
        val bands = listOf(10, -30, -120, -2000).map { MapProjection.band(it) }
        assertEquals(4, bands.toSet().size)
    }

    // --- a real position ----------------------------------------------------

    @Test
    fun `Chennai and Rameswaram land where they should relative to each other`() {
        // Chennai 13.13 N 80.30 E; Rameswaram 9.29 N 79.31 E.
        // Rameswaram is south and west, so: lower on screen, further left.
        val cLat = 11.0; val cLon = 80.0
        val halfLat = 4.0
        val halfLon = MapProjection.halfLon(halfLat, cLat, aspect = 2.0)

        val chY = MapProjection.yFor(13.1251, cLat, halfLat, height)
        val raY = MapProjection.yFor(9.2876, cLat, halfLat, height)
        val chX = MapProjection.xFor(80.2955, cLon, halfLon, width)
        val raX = MapProjection.xFor(79.3129, cLon, halfLon, width)

        assertTrue("Rameswaram is south of Chennai", raY > chY)
        assertTrue("Rameswaram is west of Chennai", raX < chX)
    }
}
