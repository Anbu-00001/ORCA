package org.orca.advisory

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A map that works with the radio off.
 *
 * <p>WHY THIS EXISTS, and why it is not MapLibre. The web client draws a
 * real slippy map, and it is the better map — right up until the boat
 * leaves mobile coverage, at which point it is a grey rectangle. Every
 * tile server is a network call. There is no offline mode to fall back
 * to, because the tiles were never on the device.
 *
 * <p>This draws from geometry ORCA already ships and already cites:
 *
 * <ul>
 *  <li><b>Bathymetry</b> — 4,760 NOAA ETOPO 2022 soundings, in
 *      `assets/bathymetry.json`. Land, shelf and deep water are the real
 *      measured elevations, not a basemap picture of them.
 *  <li><b>The IMBL</b> — the four real India–Sri Lanka treaty segments
 *      from Marine Regions, shipped in `/bundle`.
 *  <li><b>Zones, CAP polygons, the drift box</b> — whatever the calling
 *      screen hands it.
 * </ul>
 *
 * <p>So the map is not a picture with data drawn over it. Every pixel is
 * a number with a source, which is the same standard the rest of ORCA
 * holds itself to (CLAUDE.md rule 3). Nothing here is decorative and
 * nothing is interpolated: where there is no sounding, nothing is drawn.
 *
 * <p>Deliberately NOT a general map widget. It has no tiles, no labels,
 * no rotation and no projection beyond equirectangular, because at this
 * scale and this latitude that is honest to well under a kilometre and
 * anything more is weight nobody asked for (CLAUDE.md rule 7).
 */

// --- what a screen can ask the map to draw ---------------------------------

/** A closed outline, e.g. an IMD warning polygon or the drift box. */
data class MapPolygon(
    val points: List<Pair<Double, Double>>,
    val stroke: Color,
    val fillAlpha: Float = 0.18f,
    val label: String? = null,
)

/** An open line, e.g. an IMBL segment. */
data class MapLine(
    val points: List<Pair<Double, Double>>,
    val stroke: Color,
    val dashed: Boolean = false,
)

/** A single position: a zone, the boat, a drift centre. */
data class MapMarker(
    val lat: Double,
    val lon: Double,
    val color: Color,
    val radiusDp: Float = 5f,
    val ring: Boolean = false,
)

/**
 * The lat/lon -> pixel maths, pulled out of the Composable so it can be
 * tested without a phone.
 *
 * <p>This is not fussiness. The drift box and the IMBL are both drawn
 * through here, and a projection that is subtly wrong does not crash or
 * look broken -- it draws a plausible box in the wrong piece of sea. That
 * is the same class of error as a flipped wind convention, and it gets
 * caught the same way: closed-form tests with hand-computed answers.
 *
 * <p>Equirectangular, with a cos(lat) correction on longitude. At 10 N
 * over a few degrees that is honest to well under a kilometre, which is
 * far finer than anything drawn on it. Anything more is weight nobody
 * asked for (CLAUDE.md rule 7).
 */
object MapProjection {

    /**
     * Half-width in longitude degrees for a given half-height in latitude.
     *
     * A degree of longitude is shorter than a degree of latitude by
     * cos(latitude). Without this the coast looks stretched and, worse,
     * the drift box is drawn the wrong SHAPE -- which is the one thing a
     * crew reads off it.
     */
    fun halfLon(halfLat: Double, centreLat: Double, aspect: Double): Double =
        halfLat * aspect / Math.cos(Math.toRadians(centreLat))

    /** Pixel x for a longitude, given the window and canvas width. */
    fun xFor(lon: Double, centreLon: Double, halfLon: Double, width: Float): Float =
        (((lon - (centreLon - halfLon)) / (halfLon * 2)) * width).toFloat()

    /**
     * Pixel y for a latitude. Screen y grows DOWNWARD and latitude grows
     * northward, so this inverts. Getting it backwards mirrors the chart
     * north-south and is not obvious by eye on open water.
     */
    fun yFor(lat: Double, centreLat: Double, halfLat: Double, height: Float): Float =
        (((centreLat + halfLat - lat) / (halfLat * 2)) * height).toFloat()

    /** True if a point is inside the drawn window, with a margin for cell size. */
    fun visible(
        lat: Double, lon: Double,
        centreLat: Double, centreLon: Double,
        halfLat: Double, halfLon: Double,
        margin: Double,
    ): Boolean =
        lat >= centreLat - halfLat - margin && lat <= centreLat + halfLat + margin &&
        lon >= centreLon - halfLon - margin && lon <= centreLon + halfLon + margin

    /** Seabed colour bands, as ARGB ints so this stays free of Compose. */
    const val LAND = 0
    const val SHELF = 1        // shallower than 60 m -- the water people fish
    const val COASTAL = 2      // 60 m to 200 m
    const val DEEP = 3         // past the shelf edge

    /**
     * Which band a sounding falls in. Thresholds are the real shelf
     * structure off Tamil Nadu, not arbitrary: the 60 m contour is roughly
     * where the trawl grounds end and the 200 m contour is the shelf edge.
     */
    fun band(elevationM: Int): Int = when {
        elevationM >= 0 -> LAND
        elevationM > -60 -> SHELF
        elevationM > -200 -> COASTAL
        else -> DEEP
    }
}

/**
 * The soundings, loaded once and cached for the process.
 *
 * Reading 81 KB of JSON on every recomposition would drop frames on a
 * mid-range phone, and this file never changes at runtime.
 */
object Bathymetry {
    private var cached: Grid? = null

    data class Grid(
        val lat: DoubleArray,
        val lon: DoubleArray,
        val elevM: IntArray,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val strideDeg: Double,
        val source: String,
    )

    fun load(context: Context): Grid? {
        cached?.let { return it }
        return try {
            val raw = context.assets.open("bathymetry.json")
                .bufferedReader().use { it.readText() }
            val o = JSONObject(raw)
            val la = o.getJSONArray("lat")
            val lo = o.getJSONArray("lon")
            val el = o.getJSONArray("elev_m")
            val n = minOf(la.length(), lo.length(), el.length())
            val bbox = o.getJSONObject("bbox")
            Grid(
                lat = DoubleArray(n) { la.getDouble(it) },
                lon = DoubleArray(n) { lo.getDouble(it) },
                elevM = IntArray(n) { el.getInt(it) },
                minLat = bbox.getDouble("min_lat"),
                maxLat = bbox.getDouble("max_lat"),
                minLon = bbox.getDouble("min_lon"),
                maxLon = bbox.getDouble("max_lon"),
                strideDeg = o.optDouble("stride_deg", 0.2),
                source = o.optString("source"),
            ).also { cached = it }
        } catch (e: Exception) {
            // No soundings is not a crash and not a blank blue rectangle:
            // the map draws the vector layers on an empty ground and the
            // caller's caption says the seabed is missing.
            Log.w("ORCA", "Bathymetry unavailable: ${e.message}")
            null
        }
    }
}

/**
 * @param focusLat/[focusLon] where to centre. Null centres on everything given.
 * @param spanDeg how much ocean to show, before pinch. Larger is wider.
 */
@Composable
fun OfflineMap(
    modifier: Modifier = Modifier,
    heightDp: Int = 260,
    focusLat: Double? = null,
    focusLon: Double? = null,
    spanDeg: Double = 2.2,
    polygons: List<MapPolygon> = emptyList(),
    lines: List<MapLine> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    seaColor: Color = Color(0xFF0E3A4F),
    landColor: Color = Color(0xFF223B33),
    shelfColor: Color = Color(0xFF14536B),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val grid = remember { Bathymetry.load(context) }

    // Everything the caller gave us, so an un-focused map frames it all.
    val all = remember(polygons, lines, markers) {
        buildList {
            polygons.forEach { addAll(it.points) }
            lines.forEach { addAll(it.points) }
            markers.forEach { add(it.lat to it.lon) }
        }
    }

    val cLat = focusLat ?: all.map { it.first }.average().takeIf { !it.isNaN() } ?: 10.5
    val cLon = focusLon ?: all.map { it.second }.average().takeIf { !it.isNaN() } ?: 79.0

    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            // MUST clip. Compose does NOT bound a Canvas's draw calls to its
            // own layout, and any marker or boundary segment that falls
            // outside the window is otherwise painted straight over the
            // controls below -- seen on hardware: the IMBL drawn across the
            // layer toggles and the zone key.
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(0.4f, 12f)
                    panX += pan.x
                    panY += pan.y
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            val halfLat = spanDeg / 2.0 / zoom
            val aspect = (size.width / size.height).toDouble()
            val halfLon = MapProjection.halfLon(halfLat, cLat, aspect)

            // Pan is in pixels; convert once.
            val lonPerPx = (halfLon * 2) / size.width
            val latPerPx = (halfLat * 2) / size.height
            val centreLat = cLat + panY * latPerPx
            val centreLon = cLon - panX * lonPerPx

            fun px(lat: Double, lon: Double): Offset = Offset(
                x = MapProjection.xFor(lon, centreLon, halfLon, size.width),
                y = MapProjection.yFor(lat, centreLat, halfLat, size.height),
            )

            drawRect(seaColor, size = size)

            // --- the seabed, as measured -------------------------------
            if (grid != null) {
                val cell = grid.strideDeg
                val w = (cell / (halfLon * 2) * size.width).toFloat() + 1.2f
                val h = (cell / (halfLat * 2) * size.height).toFloat() + 1.2f
                for (i in grid.elevM.indices) {
                    val la = grid.lat[i]
                    val lo = grid.lon[i]
                    if (!MapProjection.visible(la, lo, centreLat, centreLon, halfLat, halfLon, cell)) continue
                    val c = when (MapProjection.band(grid.elevM[i])) {
                        MapProjection.LAND -> landColor
                        MapProjection.SHELF -> shelfColor
                        MapProjection.COASTAL -> seaColor
                        else -> Color(0xFF0A2938)        // off the shelf edge
                    }
                    val p = px(la, lo)
                    drawRect(c, topLeft = Offset(p.x - w / 2, p.y - h / 2), size = Size(w, h))
                }
            }

            // --- polygons ------------------------------------------------
            polygons.forEach { poly ->
                if (poly.points.size < 3) return@forEach
                val path = Path().apply {
                    val f = px(poly.points[0].first, poly.points[0].second)
                    moveTo(f.x, f.y)
                    poly.points.drop(1).forEach { (la, lo) ->
                        val p = px(la, lo); lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, poly.stroke.copy(alpha = poly.fillAlpha))
                drawPath(path, poly.stroke, style = Stroke(width = 3f))
            }

            // --- lines ---------------------------------------------------
            lines.forEach { line ->
                if (line.points.size < 2) return@forEach
                for (i in 0 until line.points.size - 1) {
                    val a = px(line.points[i].first, line.points[i].second)
                    val b = px(line.points[i + 1].first, line.points[i + 1].second)
                    if (line.dashed) {
                        // Hand-stepped dashes: PathEffect on a per-frame
                        // Canvas is more allocation than this is worth.
                        val steps = 14
                        for (s in 0 until steps step 2) {
                            val t0 = s / steps.toFloat()
                            val t1 = (s + 1) / steps.toFloat()
                            drawLine(
                                line.stroke,
                                Offset(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0),
                                Offset(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1),
                                strokeWidth = 4f,
                            )
                        }
                    } else {
                        drawLine(line.stroke, a, b, strokeWidth = 4f)
                    }
                }
            }

            // --- markers -------------------------------------------------
            markers.forEach { m ->
                val p = px(m.lat, m.lon)
                val r = m.radiusDp * density
                if (m.ring) {
                    drawCircle(m.color.copy(alpha = 0.25f), r * 2.6f, p)
                    drawCircle(m.color, r, p)
                    drawCircle(Color.White, r * 0.38f, p)
                } else {
                    drawCircle(m.color, r, p)
                }
            }
        }
    }
}
