package ro.drivescore

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Un punct cu locație: kind 0 = ofertă văzută, 1 = cursă acceptată. */
/** weight = câte evenimente reprezintă (1 pentru ale tale, n pentru o celulă agregată de la ceilalți). */
data class GeoEvent(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val kind: Int,
    val netPerKm: Double,
    val weight: Int = 1
)

/** O zonă (celulă ~1 km) cu scorul ei. */
data class Zone(
    val lat: Double,
    val lon: Double,
    val score: Double,
    val offers: Int,
    val rides: Int,
    val avgNetPerKm: Double
)

object Zones {
    const val CELL_DEG = 0.01            // ~1.1 km pe latitudine
    private const val MIN_TIME_EVENTS = 10

    class Analysis(
        val zones: List<Zone>,
        val centerLat: Double,
        val centerLon: Double,
        val usedTimeFilter: Boolean,
        val lonStep: Double
    )

    private class Acc { var o = 0; var r = 0; var nsum = 0.0; var ln = 0 }

    fun hourDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 24 - d)
    }

    /** Grupează punctele pe celule; folosește doar ora curentă ±1h dacă sunt destule date. */
    fun analyze(all: List<GeoEvent>, now: Long): Analysis {
        if (all.isEmpty()) return Analysis(emptyList(), 0.0, 0.0, false, CELL_DEG)
        val wTotal = all.sumOf { it.weight }.coerceAtLeast(1)
        val cLat = all.sumOf { it.lat * it.weight } / wTotal
        val cLon = all.sumOf { it.lon * it.weight } / wTotal

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val nowH = cal.get(Calendar.HOUR_OF_DAY)
        val near = ArrayList<GeoEvent>()
        for (e in all) {
            cal.timeInMillis = e.ts
            if (hourDiff(cal.get(Calendar.HOUR_OF_DAY), nowH) <= 1) near.add(e)
        }
        val useTime = near.sumOf { it.weight } >= MIN_TIME_EVENTS
        val ev: List<GeoEvent> = if (useTime) near else all

        val lonStep = CELL_DEG / max(cos(Math.toRadians(cLat)), 0.2)
        val cells = HashMap<String, Acc>()
        for (e in ev) {
            val i = Math.floor(e.lat / CELL_DEG).toInt()
            val j = Math.floor(e.lon / lonStep).toInt()
            val a = cells.getOrPut("$i:$j") { Acc() }
            if (e.kind == 1) a.r += e.weight else a.o += e.weight
            if (!e.netPerKm.isNaN()) { a.nsum += e.netPerKm * e.weight; a.ln += e.weight }
        }
        val zones = ArrayList<Zone>()
        for ((k, a) in cells) {
            val p = k.split(":")
            val i = p[0].toInt()
            val j = p[1].toInt()
            zones.add(
                Zone(
                    (i + 0.5) * CELL_DEG, (j + 0.5) * lonStep,
                    a.o + 3.0 * a.r, a.o, a.r,
                    if (a.ln > 0) a.nsum / a.ln else 0.0
                )
            )
        }
        zones.sortByDescending { it.score }
        return Analysis(zones, cLat, cLon, useTime, lonStep)
    }

    /** Nume scurt pentru zonă: „Centru” sau direcția față de centrul datelor. */
    fun label(z: Zone, an: Analysis): String {
        val d = Geo.distKm(an.centerLat, an.centerLon, z.lat, z.lon)
        return if (d < 1.2) "Centru" else Geo.direction(an.centerLat, an.centerLon, z.lat, z.lon)
    }
}
