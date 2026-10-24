package ro.drivescore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** O cheltuială: alimentare (fuel = true) sau altceva (spălătorie, service, parcare...). */
data class Expense(
    val ts: Long,
    val fuel: Boolean,
    val amount: Double,   // lei
    val liters: Double,   // l sau kWh (doar la alimentări)
    val odo: Double,      // km la bordul mașinii (opțional, 0 = necunoscut)
    val note: String
)

object Expenses {
    private const val KEY = "expenses"
    private const val MAX = 1000

    /** Consum real și preț mediu, calculate din alimentări. */
    class FuelStats(val consumption: Double?, val pricePerUnit: Double?, val intervals: Int)

    fun all(c: Context): List<Expense> {
        val s = Store.raw(c, KEY)
        if (s.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Expense>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Expense(
                        o.getLong("ts"), o.optBoolean("f", false), o.optDouble("a", 0.0),
                        o.optDouble("l", 0.0), o.optDouble("o", 0.0), o.optString("n", "")
                    )
                )
            }
        } catch (e: Exception) {
            // date corupte: le ignorăm
        }
        return out.sortedByDescending { it.ts }
    }

    private fun save(c: Context, list: List<Expense>) {
        val a = JSONArray()
        for (e in list.take(MAX)) {
            a.put(
                JSONObject().put("ts", e.ts).put("f", e.fuel).put("a", e.amount)
                    .put("l", e.liters).put("o", e.odo).put("n", e.note)
            )
        }
        Store.setRaw(c, KEY, a.toString())
    }

    fun add(c: Context, e: Expense) {
        save(c, listOf(e) + all(c))
    }

    fun delete(c: Context, ts: Long) {
        save(c, all(c).filter { it.ts != ts })
    }

    /**
     * Prețul mediu = total lei / total litri pe ultimele alimentări.
     * Consumul = litrii alimentării noi / km parcurși de la alimentarea anterioară (metoda „plin la plin”),
     * deci presupune că faci plinul de fiecare dată.
     */
    fun fuelStats(c: Context): FuelStats {
        val fills = all(c).filter { it.fuel && it.liters > 0.0 }.sortedBy { it.ts }
        val recent = fills.takeLast(6)
        val litersSum = recent.sumOf { it.liters }
        val price = if (litersSum > 0.0) recent.sumOf { it.amount } / litersSum else null

        val withOdo = fills.filter { it.odo > 0.0 }
        var km = 0.0
        var l = 0.0
        var n = 0
        for (i in withOdo.size - 1 downTo 1) {
            val d = withOdo[i].odo - withOdo[i - 1].odo
            if (d < 20.0 || d > 3000.0) continue
            km += d
            l += withOdo[i].liters
            n++
            if (n >= 6) break
        }
        val cons = if (n > 0 && km > 0.0) l / km * 100.0 else null
        return FuelStats(cons, price, n)
    }
}
