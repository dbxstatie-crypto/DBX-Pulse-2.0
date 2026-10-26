package ro.drivescore

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

/**
 * Zone rotunde, colorate după „căldură” (galben → portocaliu → roșu).
 * Zonele fierbinți pulsează (inel care se extinde); cu cât mai fierbinți, cu atât mai repede.
 */
class HotZoneOverlay(
    private val zones: List<Zone>,
    private val top: Double,
    private val me: Pair<Double, Double>?,
    private val onTap: (Zone) -> Unit
) : Overlay() {

    private class Drawn(val z: Zone, val x: Float, val y: Float, val r: Float)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val drawn = ArrayList<Drawn>()

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun heat(n: Float): Int {
        val y = Color.parseColor("#FACC15")
        val o = Color.parseColor("#F97316")
        val r = Color.parseColor("#EF4444")
        return if (n < 0.5f) lerp(y, o, n * 2f) else lerp(o, r, (n - 0.5f) * 2f)
    }

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = osmv.projection
        val d = osmv.resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        drawn.clear()
        var animate = false

        for (z in zones.sortedBy { it.score }) {   // cele mai fierbinți se desenează ultimele
            val norm = if (top > 0) (z.score / top).coerceIn(0.0, 1.0).toFloat() else 0f
            val center = proj.toPixels(GeoPoint(z.lat, z.lon), Point())
            val meters = 220.0 + 380.0 * norm
            val edge = proj.toPixels(GeoPoint(z.lat + meters / 111320.0, z.lon), Point())
            val r = abs(center.y - edge.y).toFloat().coerceAtLeast(7f * d)
            val cx = center.x.toFloat()
            val cy = center.y.toFloat()
            val col = heat(norm)

            fill.color = col
            fill.alpha = (50 + 100 * norm).toInt()
            c.drawCircle(cx, cy, r, fill)
            ring.color = col
            ring.alpha = 210
            ring.strokeWidth = 1.6f * d
            c.drawCircle(cx, cy, r, ring)

            if (norm >= 0.55f) {
                animate = true
                val period = (2600 - 1500 * norm).toLong()
                val waves = if (norm >= 0.85f) 2 else 1
                for (w in 0 until waves) {
                    val phase = (((now + w * period / 2) % period).toFloat()) / period
                    ring.color = col
                    ring.alpha = (190 * (1f - phase)).toInt()
                    ring.strokeWidth = 2.4f * d
                    c.drawCircle(cx, cy, r * (1f + 0.8f * phase), ring)
                }
            }
            drawn.add(Drawn(z, cx, cy, r))
        }

        if (me != null) {
            val p = proj.toPixels(GeoPoint(me.first, me.second), Point())
            fill.color = Color.parseColor("#3B82F6")
            fill.alpha = 60
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 14f * d, fill)
            fill.color = Color.WHITE
            fill.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 7f * d, fill)
            fill.color = Color.parseColor("#3B82F6")
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 5f * d, fill)
        }

        if (animate) osmv.postInvalidateDelayed(40)   // ~25 fps, doar cât există zone fierbinți
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        for (i in drawn.indices.reversed()) {
            val z = drawn[i]
            val dx = e.x - z.x
            val dy = e.y - z.y
            if (dx * dx + dy * dy <= z.r * z.r) {
                onTap(z.z)
                return true
            }
        }
        return false
    }
}
