package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nautical units.
 *
 * The conversions are exact by definition, so these tests are less about
 * arithmetic than about the two things that would actually bite: a factor
 * applied the wrong way round (which makes every distance look half what
 * it is), and a wave height quietly converted into knots.
 */
class UnitsTest {

    @Test
    fun `a nautical mile is exactly 1852 metres`() {
        // Definition, not measurement. If this ever fails, someone has
        // "rounded" a constant that is not allowed to be rounded.
        assertEquals(1852.0, Units.METRES_PER_NM, 0.0)
        assertEquals(1.852, Units.KM_PER_NM, 0.0)
    }

    @Test
    fun `the conversion goes the right way round`() {
        // The error that would make a 10 NM boundary look 3 NM away.
        assertEquals(1.0, Units.kmToNm(1.852), 1e-9)
        assertTrue("a distance in NM is a SMALLER number than in km", Units.kmToNm(10.0) < 10.0)
        assertTrue("a speed in knots is a SMALLER number than in km/h", Units.kmhToKnots(10.0) < 10.0)
    }

    @Test
    fun `round trips are lossless`() {
        assertEquals(37.0, Units.nmToKm(Units.kmToNm(37.0)), 1e-9)
        assertEquals(12.5, Units.knotsToKmh(Units.kmhToKnots(12.5)), 1e-9)
    }

    @Test
    fun `the IMBL bands convert to the numbers a crew would say`() {
        // ORCA's bands are 2, 5 and 10 km. A crew hears these in miles.
        assertEquals(1.08, Units.kmToNm(2.0), 0.01)
        assertEquals(2.70, Units.kmToNm(5.0), 0.01)
        assertEquals(5.40, Units.kmToNm(10.0), 0.01)
    }

    @Test
    fun `close quarters are given in cables, not fractions of a mile`() {
        // 100 m is about half a cable; 0.05 NM is not a number anyone reads.
        assertTrue(Units.distance(0.1).endsWith("cables"))
        assertTrue(Units.distance(0.5).contains("NM"))
    }

    @Test
    fun `a long distance drops the decimal`() {
        assertEquals("27 NM", Units.distance(50.0))
        assertTrue(Units.distance(5.0).contains("."))
    }

    @Test
    fun `speeds read in knots`() {
        // 18.5 km/h is 10 knots.
        assertEquals("10 kn", Units.speed(18.52))
        assertTrue(Units.speed(9.0).contains("kn"))
    }

    // --- the one that protects the readings --------------------------------

    @Test
    fun `wind and current convert to knots`() {
        assertEquals("kn", Units.convertedValue("wind_speed_kmh", 18.52)!!.second)
        assertEquals("kn", Units.convertedValue("ocean_current_velocity_kmh", 3.7)!!.second)
        assertEquals("10", Units.convertedValue("wind_speed_kmh", 18.52)!!.first)
    }

    @Test
    fun `wave height stays in metres and is NOT converted`() {
        // Metres are universal at sea, including in every INCOIS and IMD
        // bulletin. Turning a 2 m swell into knots would be nonsense that
        // still rendered.
        val (v, u) = Units.convertedValue("wave_height_m", 2.0)!!
        assertEquals("m", u)
        assertEquals("2.0", v)
    }

    @Test
    fun `sea temperature stays in Celsius`() {
        assertEquals("°C", Units.convertedValue("sst_c", 30.2)!!.second)
    }

    @Test
    fun `an unknown variable is refused rather than guessed at`() {
        // Returning the raw number with an invented unit is how a
        // chlorophyll reading ends up labelled "kn".
        assertNull(Units.convertedValue("chlorophyll_mg_m3", 0.5))
        assertNull(Units.convertedValue("something_new", 1.0))
    }
}
