package org.orca.advisory

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The seabed drawn as a real chart rather than as coloured blocks.
 *
 * <h3>WHY THE OLD MAP LOOKED LIKE A TOY</h3>
 * It painted one flat rectangle per sounding, in one of four colours. The
 * ETOPO grid is spaced 0.0667 degrees apart, which is about 7.4 km, so on a
 * phone screen every rectangle was a large hard-edged square — the map was
 * literally a mosaic of 4,760 tiles in four shades. Nothing about a real
 * sea chart looks like that.
 *
 * <p>Three things fix it, in order of how much they matter:
 *
 * <ol>
 *  <li><b>Interpolation.</b> Depth between two soundings is estimated
 *      bilinearly instead of jumping at a cell edge. The grid is unchanged
 *      — this is how the SAME measurements are drawn, not new data.
 *  <li><b>A continuous colour ramp</b> instead of four buckets, on a
 *      logarithmic depth scale, because the eye and the seabed are both
 *      logarithmic: the difference between 5 m and 20 m matters enormously
 *      to a trawler and the difference between 2,000 m and 2,700 m does not.
 *  <li><b>A rendered raster</b>, built once per viewport and blitted, so
 *      per-pixel work happens off the draw path and the chart still pans at
 *      sixty frames a second on a budget handset.
 * </ol>
 *
 * <h3>THE HONESTY RULE STILL HOLDS</h3>
 * Interpolating BETWEEN measured soundings is standard cartographic
 * practice and is not fabrication — every printed chart does it, and the
 * source grid is unmodified. But it must never be presented as more
 * precise than it is: the chart caption states the true grid spacing, and
 * ORCA never derives a navigational depth for a specific point from it.
 * The soundings are context for where a crew is, not a promise about what
 * is under the keel.
 */
object ChartRaster {

    /**
     * The colour of "ORCA has no sounding here".
     *
     * Deliberately outside the depth ramp entirely -- grey, not blue -- so
     * it can never be mistaken for a measured depth. The old map painted
     * these pixels the same navy as the abyssal plain, which told a crew
     * the water was deep in places nothing had ever measured.
     */
    val UNSURVEYED_COLOR = Color(0xFF3A4A52)
    private const val UNSURVEYED = 0xFF3A4A52.toInt()

    /**
     * Depth colour, on the convention every printed chart uses: white-blue
     * for the shallows a hull can touch, deepening to near-black offshore.
     *
     * <p>Logarithmic in depth. A linear ramp spends most of its range on
     * the abyssal plain, where nothing a fisherman does changes, and leaves
     * the 0-50 m band — which is the entire working world of an inshore
     * boat — as one indistinguishable colour.
     */
    fun seaColour(depthM: Double): Color {
        val d = depthM.coerceIn(0.0, 3000.0)
        // 0 m -> 0.0, 3000 m -> 1.0, with most resolution in the shallows.
        val t = (ln(1.0 + d) / ln(3001.0)).coerceIn(0.0, 1.0)
        // Pale shelf blue to deep ocean navy.
        val r = lerp(0xC9, 0x05, t)
        val g = lerp(0xE6, 0x1E, t)
        val b = lerp(0xF2, 0x33, t)
        return Color(r, g, b)
    }

    /**
     * Land colour by height, so the coastal plain and the Western Ghats do
     * not read as the same flat slab.
     */
    fun landColour(elevM: Double): Color {
        val e = elevM.coerceIn(0.0, 2200.0)
        val t = (e / 2200.0).coerceIn(0.0, 1.0)
        val r = lerp(0xDD, 0x8A, t)
        val g = lerp(0xD9, 0x6B, t)
        val b = lerp(0xC4, 0x50, t)
        return Color(r, g, b)
    }

    private fun lerp(a: Int, b: Int, t: Double): Int =
        (a + (b - a) * t).roundToInt().coerceIn(0, 255)

    /**
     * Depth contours a mariner actually looks for.
     *
     * The 20 m and 50 m lines bracket most inshore trawling, 200 m is the
     * shelf edge, and 1000 m says "you are off soundings". These are the
     * lines a printed chart of this coast prints.
     */
    val CONTOURS_M = intArrayOf(20, 50, 200, 1000)

    /**
     * A regular lat/lon lookup built from the flat point list.
     *
     * The cached grid arrives as three parallel arrays of points in no
     * guaranteed order, which is fine for drawing squares and useless for
     * interpolating. This indexes them into a rectangular lattice once.
     */
    class Lattice(
        val lat0: Double,
        val lon0: Double,
        val step: Double,
        val rows: Int,
        val cols: Int,
        val elev: FloatArray,
    ) {
        /** Elevation at a grid node, or NaN if that node was never sampled. */
        fun node(r: Int, c: Int): Float =
            if (r in 0 until rows && c in 0 until cols) elev[r * cols + c] else Float.NaN

        /**
         * Bilinearly interpolated elevation, or NaN outside the grid.
         *
         * Returns NaN rather than a plausible number when any corner is
         * missing. A hole in the data must stay visibly a hole.
         */
        fun at(lat: Double, lon: Double): Float {
            val fr = (lat - lat0) / step
            val fc = (lon - lon0) / step
            val r = kotlin.math.floor(fr).toInt()
            val c = kotlin.math.floor(fc).toInt()
            val tr = (fr - r).toFloat()
            val tc = (fc - c).toFloat()
            val v00 = node(r, c); val v01 = node(r, c + 1)
            val v10 = node(r + 1, c); val v11 = node(r + 1, c + 1)
            if (v00.isNaN() || v01.isNaN() || v10.isNaN() || v11.isNaN()) return Float.NaN
            val top = v00 + (v01 - v00) * tc
            val bot = v10 + (v11 - v10) * tc
            return top + (bot - top) * tr
        }
    }

    /** Index the flat sounding list into a rectangular lattice. */
    fun lattice(lat: DoubleArray, lon: DoubleArray, elevM: IntArray, step: Double): Lattice? {
        if (lat.isEmpty() || step <= 0) return null
        val minLat = lat.min(); val minLon = lon.min()
        val rows = ((lat.max() - minLat) / step).roundToInt() + 1
        val cols = ((lon.max() - minLon) / step).roundToInt() + 1
        if (rows <= 1 || cols <= 1 || rows.toLong() * cols > 2_000_000L) return null
        val grid = FloatArray(rows * cols) { Float.NaN }
        for (i in lat.indices) {
            val r = ((lat[i] - minLat) / step).roundToInt()
            val c = ((lon[i] - minLon) / step).roundToInt()
            if (r in 0 until rows && c in 0 until cols) grid[r * cols + c] = elevM[i].toFloat()
        }
        return Lattice(minLat, minLon, step, rows, cols, grid)
    }

    /**
     * Render the seabed for one viewport.
     *
     * Deliberately rendered SMALL (a few hundred pixels) and then scaled up
     * by the Canvas. Depth is a smooth field, so the hardware's bilinear
     * upscale is exactly the right interpolation and costs nothing, while
     * rendering at full screen resolution would be tens of times the work
     * for no visible gain.
     *
     * @return null if there is nothing to draw, so the caller can say so
     *   rather than showing an empty blue rectangle that looks like sea.
     */
    fun render(
        lattice: Lattice,
        centreLat: Double,
        centreLon: Double,
        halfLat: Double,
        halfLon: Double,
        widthPx: Int,
        heightPx: Int,
    ): ImageBitmap? {
        // Half resolution, not a third: the land/sea edge is the one place
        // the eye judges a chart, and a third was visibly soft there.
        val w = widthPx.coerceIn(16, 640)
        val h = heightPx.coerceIn(16, 640)
        val pixels = IntArray(w * h)
        var drawn = 0
        for (y in 0 until h) {
            // Screen y grows downward, latitude grows upward.
            val lat = centreLat + halfLat - (y + 0.5) / h * (halfLat * 2)
            for (x in 0 until w) {
                val lon = centreLon - halfLon + (x + 0.5) / w * (halfLon * 2)
                val e = lattice.at(lat, lon)
                val colour = if (e.isNaN()) {
                    // OUTSIDE THE SURVEYED GRID. This must not be painted in
                    // any shade of the depth ramp: a flat navy here reads as
                    // "very deep water", which is a claim ORCA has no data
                    // for. A desaturated slate says "not surveyed" instead,
                    // and the key names it.
                    UNSURVEYED
                } else {
                    drawn++
                    val c = if (e >= 0) landColour(e.toDouble()) else seaColour(-e.toDouble())
                    (0xFF shl 24) or
                        ((c.red * 255).toInt() shl 16) or
                        ((c.green * 255).toInt() shl 8) or
                        (c.blue * 255).toInt()
                }
                pixels[y * w + x] = colour
            }
        }
        if (drawn == 0) return null
        return android.graphics.Bitmap
            .createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }

    /**
     * A sensible graticule interval for the span on screen.
     *
     * Real charts label round fractions of a degree, never "every 0.137
     * degrees". This picks from the set a chart would actually use so the
     * labels read as 10' and 30' rather than as arbitrary decimals.
     */
    fun graticuleStep(spanDeg: Double): Double {
        val candidates = doubleArrayOf(
            5.0, 2.0, 1.0, 0.5, 1.0 / 6.0, 0.1, 1.0 / 12.0, 1.0 / 60.0,
        )
        // Aim for roughly four labelled lines across the view.
        val target = spanDeg / 4.0
        return candidates.minByOrNull { abs(it - target) } ?: 0.5
    }

    /**
     * Degrees and minutes, the way a chart margin prints them.
     *
     * "13 30.0'N", not "13.5". A crew reading a position off this and
     * saying it into a VHF needs the units the coastguard writes down.
     */
    fun formatLat(lat: Double): String {
        val hemi = if (lat >= 0) "N" else "S"
        val a = abs(lat); val d = a.toInt(); val m = (a - d) * 60.0
        return String.format("%02d %04.1f'%s", d, m, hemi)
    }

    fun formatLon(lon: Double): String {
        val hemi = if (lon >= 0) "E" else "W"
        val a = abs(lon); val d = a.toInt(); val m = (a - d) * 60.0
        return String.format("%03d %04.1f'%s", d, m, hemi)
    }

    /**
     * A round scale-bar length in nautical miles for the span on screen,
     * plus how wide it should be drawn as a fraction of the view.
     */
    fun scaleBar(spanNm: Double): Pair<Double, Double> {
        val nice = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0)
        val target = spanNm / 4.0
        val pick = nice.lastOrNull { it <= target } ?: nice.first()
        return pick to (pick / spanNm)
    }
}
