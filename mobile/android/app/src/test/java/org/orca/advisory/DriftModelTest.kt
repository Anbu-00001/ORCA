package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Leeway drift model on the phone.
 *
 * This is the one number in ORCA that gets read out loud to a rescue
 * service. A sign error in a direction convention crashes nothing and
 * quietly sends a search boat 180 degrees the wrong way, so most of these
 * tests are about directions being the right way round.
 *
 * Expected values are computed from Allen & Plourde's published
 * coefficients by hand, not captured from a previous run of this code.
 * They are the same assertions tests/test_drift.py makes against
 * orca/drift.py, which is what keeps the two implementations honest.
 */
class DriftModelTest {

    private fun forecast(
        windKmh: Double? = 20.0,
        windFromDeg: Double? = 0.0,
        currentKmh: Double? = 0.0,
        currentTowardDeg: Double? = 0.0,
        hours: Double = 6.0,
    ) = DriftModel.forecast(13.0, 80.3, windKmh, windFromDeg, currentKmh, currentTowardDeg, hours)

    // --- direction conventions, the part most likely to be backwards ----

    @Test
    fun `wind from the north drives the hull south`() {
        // windDirectionDeg is meteorological: the direction it blows FROM.
        val r = forecast(windFromDeg = 0.0)
        assertTrue(r.ok)
        assertEquals(180.0, r.bearingDeg, 0.5)
        assertEquals("S", DriftModel.compass(r.bearingDeg))
        assertTrue("must actually move south", r.centreLat < 13.0)
    }

    @Test
    fun `current direction is where it flows TO, not where it comes from`() {
        // Open-Meteo documents ocean_current_direction as the heading the
        // current is going towards -- the opposite convention to wind.
        val r = forecast(windKmh = 0.0, currentKmh = 2.0, currentTowardDeg = 90.0)
        assertEquals(90.0, r.bearingDeg, 0.5)
        assertTrue("must actually move east", r.centreLon > 80.3)
    }

    @Test
    fun `with no wind the hull moves at exactly the current speed`() {
        // 2 km/h for 6 h is 12 km. Anything else means leeway is leaking
        // in where it should be zero.
        val r = forecast(windKmh = 0.0, currentKmh = 2.0, currentTowardDeg = 90.0)
        assertEquals(12.0, r.distanceKm, 0.05)
    }

    // --- the published coefficients, applied as published ---------------

    @Test
    fun `downwind leeway is 2 point 47 percent of the wind speed`() {
        // Hand-computed: 20 km/h = 5.5556 m/s; 2.47% is 0.13722 m/s;
        // over 21600 s that is 2.964 km. No current, so that is all of it.
        val expected = 0.0247 * (20.0 / 3.6) * 21600 / 1000.0
        assertEquals(expected, forecast().distanceKm, 0.01)
    }

    @Test
    fun `distance is linear in time because the field is held fixed`() {
        // Not a modelling claim -- a statement of the limitation.
        val six = forecast(windFromDeg = 45.0, currentKmh = 1.0, currentTowardDeg = 200.0, hours = 6.0)
        val twelve = forecast(windFromDeg = 45.0, currentKmh = 1.0, currentTowardDeg = 200.0, hours = 12.0)
        assertEquals(2 * six.distanceKm, twelve.distanceKm, 0.001)
        assertEquals(six.bearingDeg, twelve.bearingDeg, 0.001)
    }

    @Test
    fun `the long horizon admits it is a sketch`() {
        assertTrue(forecast(hours = 6.0).confidenceNote.contains("reasonable"))
        assertTrue(forecast(hours = 24.0).confidenceNote.contains("not a forecast"))
    }

    // --- the uncertainty box --------------------------------------------

    @Test
    fun `the box has four corners and encloses the centre`() {
        val r = forecast(windKmh = 25.0, windFromDeg = 90.0, currentKmh = 1.0, currentTowardDeg = 180.0)
        assertEquals(4, r.box.size)
        val lats = r.box.map { it.first }
        val lons = r.box.map { it.second }
        assertTrue(r.centreLat in lats.min()..lats.max())
        assertTrue(r.centreLon in lons.min()..lons.max())
    }

    @Test
    fun `a stronger wind widens the search area, not just moves it`() {
        // Both the slope and its standard deviation multiply the wind
        // speed, so uncertainty has to grow with it.
        fun spread(wind: Double): Double {
            val r = forecast(windKmh = wind)
            val lats = r.box.map { it.first }
            val lons = r.box.map { it.second }
            return (lats.max() - lats.min()) + (lons.max() - lons.min())
        }
        assertTrue(spread(40.0) > spread(10.0))
    }

    @Test
    fun `a calm sea still leaves a box, because certainty is not on offer`() {
        val r = forecast(windKmh = 0.0)
        val lats = r.box.map { it.first }
        assertTrue(lats.max() - lats.min() > 0.0)
    }

    @Test
    fun `the hull never drifts upwind`() {
        // Without the floor on the -1 sigma bound, a light wind with a
        // large sigma would put a corner of the box UPWIND. No hull does
        // that.
        val r = forecast(windKmh = 5.0, windFromDeg = 0.0, hours = 12.0)
        assertTrue(r.box.all { it.first <= 13.0 + 1e-9 })
    }

    // --- refusing rather than guessing -----------------------------------

    @Test
    fun `a missing wind direction is refused, never defaulted`() {
        val r = forecast(windFromDeg = null)
        assertFalse(r.ok)
        assertTrue(r.missing.contains("wind direction"))
        assertTrue(r.reason!!.contains("guess"))
    }

    @Test
    fun `every missing input is named so the screen can say which`() {
        val r = DriftModel.forecast(13.0, 80.3, null, null, null, null, 6.0)
        assertEquals(
            listOf("wind speed", "wind direction", "current speed", "current direction"),
            r.missing,
        )
    }

    @Test
    fun `a refusal carries no numbers at all`() {
        // A box of zeroes rendered next to "cannot compute" is exactly the
        // kind of thing a tired crew reads as a position.
        val r = forecast(currentTowardDeg = null)
        assertTrue(r.box.isEmpty())
        assertEquals(0.0, r.distanceKm, 0.0)
    }

    // --- what gets said out loud ------------------------------------------

    @Test
    fun `compass names round to the nearest of sixteen points`() {
        assertEquals("N", DriftModel.compass(0.0))
        assertEquals("E", DriftModel.compass(90.0))
        assertEquals("S", DriftModel.compass(180.0))
        assertEquals("W", DriftModel.compass(270.0))
        assertEquals("N", DriftModel.compass(359.9))
        assertEquals("NNE", DriftModel.compass(22.5))
    }

    @Test
    fun `every compass point has a Tamil name`() {
        // No bearing may fall through to an English word in the spoken
        // alert, which is the whole reason this map exists.
        for (deg in 0 until 360 step 5) {
            val ta = DriftModel.compassTamil(deg.toDouble())
            assertTrue("bearing $deg produced '$ta'", ta.isNotBlank())
            assertTrue("bearing $deg is not Tamil", ta[0].code > 0x0B80)
        }
    }

    @Test
    fun `the coefficients name their source`() {
        // Empirical numbers that are not ORCA's. Edit them, edit the cite.
        assertTrue(DriftModel.SOURCE.contains("CG-D-08-99"))
        assertTrue(DriftModel.SOURCE.contains("Allen & Plourde"))
        assertEquals(2.47, DriftModel.DWL_SLOPE, 0.0)
        assertEquals(2.76, DriftModel.CWL_SLOPE, 0.0)
    }
}
