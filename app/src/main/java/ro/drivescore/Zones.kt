package ro.drivescore

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Un punct cu locație: kind 0 = ofertă văzută, 1 = cursă acceptată. */
/** weight = câte evenimente reprezintă (1 pentru ale tale, n pentru o celulă agregată de la ceilalți). */
data class GeoEvent(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val kind: Int,
    val netPerKm: Double,
    val weight: Int = 1,
    val own: Boolean = false
)

/** O zonă (celulă ~500 m) cu scorul ei. */
data class Zone(
    val lat: Double,
    val lon: Double,
    val score: Double,
    val offers: Int,
    val rides: Int,
    val avgNetPerKm: Double,
    val conf: Int = 0,                  // 0 = puține date, 1 = medii, 2 = multe
    val offersPerHour: Double = -1.0,   // din datele tale + timpul petrecut acolo (-1 = necunoscut)
    val goodShare: Double = -1.0        // ponderea curselor „bune” (net/km peste pragul verde), -1 = necunoscut
)

object Zones {
    const val CELL_DEG = 0.0045      // ~500 m pe latitudine
    const val LON_STEP = 0.0064      // ~500 m pe longitudine la latitudinea României
    private const val MIN_TIME_MASS = 10.0
    private const val SPREAD_SIDE = 0.2   // cât se „împrăștie” o ofertă în celulele vecine (pe laturi)
    private const val SPREAD_DIAG = 0.05  // și pe diagonală: cu cât mai mic, cu atât zonele rămân separate

    class Analysis(
        val zones: List<Zone>,
        val centerLat: Double,
        val centerLon: Double,
        val usedTimeFilter: Boolean,
        val lonStep: Double,
        val globalNpk: Double = 0.0
    )

    private class Acc {
        var so = 0.0; var sr = 0.0       // cerere netezită (pondere timp + vecini)
        var sn = 0.0; var sw = 0.0       // net/km ponderat
        var good = 0.0; var gw = 0.0
        var rawO = 0; var rawR = 0       // numere reale, doar din celulă
        var ownO = 0                     // ofertele tale în ora/tipul de zi curent
    }

    fun hourDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 24 - d)
    }

    private fun weekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    fun confText(z: Zone): String = when (z.conf) { 2 -> "multe date"; 1 -> "date medii"; else -> "puține date" }

    /**
     * Scor = cerere (oferte + 3×curse, cu vecini și timp) × calitate (net/km, trasă spre medie când sunt puține date)
     *        × factor de expunere (oferte/oră din timpul petrecut efectiv acolo) × încredere.
     * Timpul: ora ±1 (apoi ±2, ±3 mai slab), același tip de zi (lucrătoare/weekend), date recente cântăresc mai mult.
     */
    fun analyze(all: List<GeoEvent>, now: Long, dwell: Map<String, Double> = emptyMap(), goodNpk: Double = 0.0): Analysis {
        if (all.isEmpty()) return Analysis(emptyList(), 0.0, 0.0, false, LON_STEP)
        val wTotal = all.sumOf { it.weight }.coerceAtLeast(1)
        val cLat = all.sumOf { it.lat * it.weight } / wTotal
        val cLon = all.sumOf { it.lon * it.weight } / wTotal

        var gs = 0.0; var gw = 0.0
        for (e in all) if (!e.netPerKm.isNaN() && e.netPerKm > 0) { gs += e.netPerKm * e.weight; gw += e.weight }
        val g = if (gw > 0) gs / gw else 1.5

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val nowH = cal.get(Calendar.HOUR_OF_DAY)
        val nowWk = weekend(cal)

        val cells = HashMap<String, Acc>()
        var timeMass = 0.0
        for (e in all) {
            cal.timeInMillis = e.ts
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val wk = weekend(cal)
            val ageDays = max(0.0, (now - e.ts) / 86_400_000.0)
            val rec = max(0.05, 0.5.pow(ageDays / 30.0))
            val hd = hourDiff(h, nowH)
            val hw = when { hd <= 1 -> 1.0; hd == 2 -> 0.5; hd == 3 -> 0.25; else -> 0.12 }
            val dtw = if (wk == nowWk) 1.0 else 0.35
            val w = e.weight * rec * hw * dtw
            if (hd <= 1 && wk == nowWk) timeMass += w
            val i = floor(e.lat / CELL_DEG).toInt()
            val j = floor(e.lon / LON_STEP).toInt()
            val npk = e.netPerKm
            for (di in -1..1) for (dj in -1..1) {
                val k = if (di == 0 && dj == 0) 1.0 else if (di == 0 || dj == 0) SPREAD_SIDE else SPREAD_DIAG
                val a = cells.getOrPut("${i + di}:${j + dj}") { Acc() }
                if (e.kind == 1) a.sr += w * k else a.so += w * k
                if (!npk.isNaN() && npk > 0) {
                    a.sn += npk * w * k; a.sw += w * k
                    if (goodNpk > 0) { a.gw += w * k; if (npk >= goodNpk) a.good += w * k }
                }
                if (di == 0 && dj == 0) {
                    if (e.kind == 1) a.rawR += e.weight else a.rawO += e.weight
                    if (e.own && e.kind == 0 && hd <= 1 && wk == nowWk) a.ownO += 1
                }
            }
        }

        // timpul petrecut (ore) în fiecare celulă, în aceeași oră ±1 și același tip de zi
        val dwellH = HashMap<String, Double>()
        for ((key, sec) in dwell) {
            val p = key.split(":")
            if (p.size < 4) continue
            val h = p[2].toIntOrNull() ?: continue
            if (hourDiff(h, nowH) <= 1 && (p[3] == "1") == nowWk) {
                val ck = p[0] + ":" + p[1]
                dwellH[ck] = (dwellH[ck] ?: 0.0) + sec / 3600.0
            }
        }
        var totO = 0.0; var totD = 0.0
        for ((k, a) in cells) {
            val d = dwellH[k] ?: 0.0
            if (d >= 0.3 && a.rawO + a.rawR > 0) { totO += a.ownO; totD += d }
        }
        val gOph = if (totD > 0 && totO > 0) totO / totD else -1.0

        val zones = ArrayList<Zone>()
        for ((k, a) in cells) {
            val raw = a.rawO + a.rawR
            if (raw < 2) continue
            val avg = (a.sn + 3.0 * g) / (a.sw + 3.0)          // mediu trasă spre medie: puține date nu înseamnă extreme
            val quality = (avg / g).coerceIn(0.5, 2.0)
            val d = dwellH[k] ?: 0.0
            val oph = if (d >= 0.3) a.ownO / d else -1.0
            val rate = if (oph >= 0 && gOph > 0) (oph / gOph).coerceIn(0.6, 1.8) else 1.0
            val conf = if (raw >= 15) 2 else if (raw >= 6) 1 else 0
            val score = (a.so + 3.0 * a.sr) * quality * rate * (0.5 + 0.25 * conf)
            val p = k.split(":")
            val i = p[0].toInt()
            val j = p[1].toInt()
            zones.add(
                Zone(
                    (i + 0.5) * CELL_DEG, (j + 0.5) * LON_STEP, score, a.rawO, a.rawR,
                    if (a.sw > 0) a.sn / a.sw else 0.0, conf, oph,
                    if (a.gw > 0) a.good / a.gw else -1.0
                )
            )
        }
        zones.sortByDescending { it.score }
        return Analysis(zones, cLat, cLon, timeMass >= MIN_TIME_MASS, LON_STEP, g)
    }

    /** Elimină zonele lipite de una deja aleasă (mai bună), ca „Centru” și vecinul lui să nu apară ca două zone aproape identice. */
    fun distinct(zones: List<Zone>, minKm: Double = 0.8): List<Zone> {
        val out = ArrayList<Zone>()
        for (z in zones) {
            if (out.none { Geo.distKm(it.lat, it.lon, z.lat, z.lon) < minKm }) out.add(z)
        }
        return out
    }

    /** Nume real al zonei (cartier/localitate) dacă l-am aflat, altfel „Centru” sau direcția. */
    fun label(z: Zone, an: Analysis): String {
        Names.get(z.lat, z.lon)?.let { return it }
        val d = Geo.distKm(an.centerLat, an.centerLon, z.lat, z.lon)
        return if (d < 1.2) "Centru" else Geo.direction(an.centerLat, an.centerLon, z.lat, z.lon)
    }
}
