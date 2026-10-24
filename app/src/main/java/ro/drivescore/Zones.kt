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
    val weight: Int = 1,
    val dowKnown: Boolean = true   // false = ziua săptămânii nu e cunoscută (date vechi de pe server)
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
    const val CELL_DEG = 0.005           // ~550 m, aliniat cu rotunjirea de pe server
    private const val MIN_TIME_EVENTS = 6

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

        val lonStep = CELL_DEG   // aceeași grilă ca pe server (0,005° și pe longitudine)
        val cells = HashMap<String, Acc>()
        for (e in ev) {
            val i = Math.round(e.lat / CELL_DEG).toInt()
            val j = Math.round(e.lon / lonStep).toInt()
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
                    i * CELL_DEG, j * lonStep,
                    a.o + 3.0 * a.r, a.o, a.r,
                    if (a.ln > 0) a.nsum / a.ln else 0.0
                )
            )
        }
        zones.sortByDescending { it.score }
        return Analysis(zones, cLat, cLon, useTime, lonStep)
    }

    /** Când se preiau curse într-o zonă: pe ore (0-23) și pe zile (0 = luni ... 6 = duminică). */
    class Timing(val hours: IntArray, val days: IntArray, val total: Int, val daysTotal: Int)

    fun timing(all: List<GeoEvent>, z: Zone): Timing {
        val zi = Math.round(z.lat / CELL_DEG)
        val zj = Math.round(z.lon / CELL_DEG)
        val hours = IntArray(24)
        val days = IntArray(7)
        var total = 0
        var daysTotal = 0
        val cal = Calendar.getInstance()
        for (e in all) {
            if (Math.round(e.lat / CELL_DEG) != zi || Math.round(e.lon / CELL_DEG) != zj) continue
            cal.timeInMillis = e.ts
            hours[cal.get(Calendar.HOUR_OF_DAY)] += e.weight
            total += e.weight
            if (e.dowKnown) {
                days[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += e.weight
                daysTotal += e.weight
            }
        }
        return Timing(hours, days, total, daysTotal)
    }

    /** Nume scurt pentru zonă: „Centru” sau direcția față de centrul datelor. */
    fun label(z: Zone, an: Analysis): String {
        val d = Geo.distKm(an.centerLat, an.centerLon, z.lat, z.lon)
        return if (d < 1.2) "Centru" else Geo.direction(an.centerLat, an.centerLon, z.lat, z.lon)
    }
}
