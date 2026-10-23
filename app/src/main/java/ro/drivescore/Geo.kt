package ro.drivescore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Locația telefonului (ultima cunoscută) + calcule de distanță / direcție. */
object Geo {
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Cea mai recentă poziție cunoscută (gps/network/passive) sau null. */
    @Suppress("MissingPermission")
    fun last(c: Context): Pair<Double, Double>? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in listOf("gps", "network", "passive")) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val b = best ?: return null
        if (System.currentTimeMillis() - b.time > MAX_AGE_MS) return null
        return Pair(b.latitude, b.longitude)
    }

    fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Direcția de la punctul 1 spre punctul 2: Nord, Nord-Est, Est ... */
    fun direction(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val dy = Math.toRadians(lat2 - lat1)
        val dx = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        var deg = Math.toDegrees(atan2(dx, dy))
        if (deg < 0) deg += 360.0
        val names = listOf("Nord", "Nord-Est", "Est", "Sud-Est", "Sud", "Sud-Vest", "Vest", "Nord-Vest")
        return names[(((deg + 22.5) / 45.0).toInt()) % 8]
    }
}
