package ro.drivescore

import java.util.Locale

/** Verdictul cursei: verde / galben / roșu. */
enum class Level { GOOD, OK, BAD }

/** Setările șoferului (costuri + praguri). */
data class Config(
    val commissionPct: Double = 0.0,    // comision platformă, % (0 = prețul din ofertă e deja net)
    val consumption: Double = 7.0,      // l sau kWh / 100 km
    val energyPrice: Double = 10.0,      // lei / l sau lei / kWh
    val maintPerKm: Double = 0.20,      // uzură, anvelope, service (lei/km)
    val fixedMonthly: Double = 0.0,     // asigurare, taxe, rate etc. (lei/lună)
    val kmPerWeek: Double = 0.0,        // km parcurși pe săptămână
    val greenNetPerKm: Double = 2.5,    // de la cât net/km e VERDE
    val redNetPerKm: Double = 1.8,      // sub cât net/km e ROȘU
    val shortKm: Double = 3.0           // curse până la atâția km (total) sunt mereu bune
) {
    val energyPerKm: Double get() = consumption / 100.0 * energyPrice
    val fixedPerKm: Double get() = if (kmPerWeek > 0) fixedMonthly / (kmPerWeek * 52.0 / 12.0) else 0.0
    val costPerKm: Double get() = energyPerKm + maintPerKm + fixedPerKm
}

/** O ofertă citită de pe ecranul aplicației de ride-hailing. */
data class Offer(
    val price: Double,        // preț brut (lei)
    val pickupKm: Double,     // km până la pasager
    val tripKm: Double,       // km cu pasagerul
    val pickupMin: Int,
    val tripMin: Int,
    val pickupKnown: Boolean, // false dacă aplicația a arătat o singură distanță
    val cash: Boolean = false // true dacă oferta arată „Numerar”; altfel e card
) {
    val totalKm: Double get() = pickupKm + tripKm
    val totalMin: Int get() = pickupMin + tripMin
    fun key(): String = String.format(Locale.US, "%.2f|%.2f|%.2f", price, pickupKm, tripKm)
}

data class Result(
    val level: Level,
    val net: Double,          // câștig net pe cursă (lei)
    val netPerKm: Double,     // net / km total (lei/km)
    val grossPerKm: Double,
    val commission: Double,   // lei
    val cost: Double,         // cost mașină pe cursă (lei)
    val netPerHour: Double?,  // lei/oră net, dacă se știu minutele
    val short: Boolean
)

object Scorer {
    fun score(o: Offer, c: Config): Result {
        val km = o.totalKm
        val commission = o.price * c.commissionPct / 100.0
        val cost = km * c.costPerKm
        val net = o.price - commission - cost
        val netPerKm = if (km > 0) net / km else 0.0
        val grossPerKm = if (km > 0) o.price / km else 0.0
        val perHour = if (o.totalMin > 0) net / (o.totalMin / 60.0) else null
        val short = km > 0 && km <= c.shortKm
        val level = when {
            short -> if (net > 0) Level.GOOD else Level.OK   // cursele scurte sunt bune oricum
            netPerKm >= c.greenNetPerKm -> Level.GOOD
            netPerKm >= c.redNetPerKm -> Level.OK
            else -> Level.BAD
        }
        return Result(level, net, netPerKm, grossPerKm, commission, cost, perHour, short)
    }
}

object OfferParser {
    private const val NB = """(?<![\d.,])"""
    private val priceRe = Regex(NB + """(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:lei|ron)(?![\p{L}/])""", RegexOption.IGNORE_CASE)
    private val distRe = Regex(NB + """(\d{1,3}(?:[.,]\d{1,2})?)\s*(km|m)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private val minRe = Regex(NB + """(\d{1,3})\s*min(?![\p{L}])""", RegexOption.IGNORE_CASE)

    /** Butoanele unei oferte: Acceptă / Respinge. Fără ele nu considerăm ecranul o ofertă. */
    val acceptRe = Regex("""(?<![\p{L}])(?:accept(?:ă|a)?|potrivire|potrive[sș]te)(?![\p{L}])""", RegexOption.IGNORE_CASE)
    val rejectRe = Regex("""(?<![\p{L}])(?:respinge|refuz[ăa]?|decline|reject|skip)(?![\p{L}])""", RegexOption.IGNORE_CASE)

    /** Semne că s-a trecut într-o cursă activă (folosit ca plasă de siguranță pentru „acceptat"). */
    val activeRideRe = Regex(
        """anulează cursa|anuleaza cursa|cancel ride|cancel trip|ai ajuns|am ajuns|ajuns la pasager|începe cursa|incepe cursa|pornește cursa|navighează|navigheaza|ridică pasagerul|ridica pasagerul|drumul spre pasager|i['’]ve arrived|i have arrived|start trip|start ride|navigate|contactează pasagerul|contacteaza pasagerul|sună pasagerul|trimite mesaj""",
        RegexOption.IGNORE_CASE
    )

    private val cashRe = Regex("""(?<![\p{L}])(?:numerar|cash)(?![\p{L}])""", RegexOption.IGNORE_CASE)

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    private val cancelRe = Regex("""pasagerul a anulat|pasager(ul)? a anulat|a anulat cursa|cursa (a fost )?anulat[ăa]|cursa ta a fost anulat[ăa]|anulat de pasager|passenger (has )?cancel|ride (was )?cancel|trip (was )?cancel""", RegexOption.IGNORE_CASE)
    fun hasCancelled(texts: List<String>) = texts.any { cancelRe.containsMatchIn(it) }
    private val cancelClickRe = Regex("""(?<![\p{L}])anulează(?![\p{L}])|(?<![\p{L}])anuleaza(?![\p{L}])|cancel (ride|trip)""", RegexOption.IGNORE_CASE)
    fun hasCancelClick(texts: List<String>) = texts.any { cancelClickRe.containsMatchIn(it) }
    // „Acceptare automată” (cursele cu card) și „Acceptă următoarea cursă” (cursă în cursă)
    private val autoRe = Regex("""accept(?:are|at[ăa]|[ăa])?\s+automat[ăa]?|se accept[ăa] automat|auto-?accept|accepted automatically""", RegexOption.IGNORE_CASE)
    private val nextRe = Regex("""urm[ăa]toarea curs[ăa]|next (ride|trip)""", RegexOption.IGNORE_CASE)
    fun hasAuto(texts: List<String>) = texts.any { autoRe.containsMatchIn(it) }
    fun hasNext(texts: List<String>) = texts.any { nextRe.containsMatchIn(it) }
    fun hasReject(texts: List<String>) = texts.any { rejectRe.containsMatchIn(it) }
    fun hasAccept(texts: List<String>) = texts.any { acceptRe.containsMatchIn(it) }
    fun hasActiveRide(texts: List<String>) = texts.any { activeRideRe.containsMatchIn(it) }

    fun parse(texts: List<String>): Offer? {
        if (!texts.any { acceptRe.containsMatchIn(it) || rejectRe.containsMatchIn(it) }) return null
        var price: Double? = null
        val km = ArrayList<Double>()
        val mins = ArrayList<Int>()
        for (t in texts) {
            if (price == null) priceRe.find(t)?.let { price = num(it.groupValues[1]) }
            for (m in distRe.findAll(t)) {
                val v = num(m.groupValues[1]) ?: continue
                km.add(if (m.groupValues[2].equals("m", true)) v / 1000.0 else v)
            }
            for (m in minRe.findAll(t)) m.groupValues[1].toIntOrNull()?.let { mins.add(it) }
        }
        val p = price ?: return null
        if (km.isEmpty() || p <= 0.0 || p > 2000.0) return null
        val pickup = if (km.size >= 2) km[0] else 0.0
        val trip = if (km.size >= 2) km[1] else km[0]
        if (pickup + trip <= 0.0 || pickup + trip > 400.0) return null
        val pMin = if (mins.size >= 2) mins[0] else 0
        val tMin = if (mins.size >= 2) mins[1] else if (mins.size == 1) mins[0] else 0
        return Offer(p, pickup, trip, pMin, tMin, km.size >= 2, texts.any { cashRe.containsMatchIn(it) })
    }
}

object Fmt {
    private val ro = Locale.forLanguageTag("ro-RO")
    fun d1(v: Double): String = String.format(ro, "%.1f", v)
    fun d2(v: Double): String = String.format(ro, "%.2f", v)
}
