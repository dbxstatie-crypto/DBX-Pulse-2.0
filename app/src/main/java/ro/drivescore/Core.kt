package ro.drivescore

import java.util.Locale

/** Verdictul cursei: verde / galben / roșu. */
enum class Level { GOOD, OK, BAD }

/** Setările șoferului (costuri + praguri). */
data class Config(
    val commissionPct: Double = 20.0,   // comision platformă, %
    val consumption: Double = 7.0,      // l sau kWh / 100 km
    val energyPrice: Double = 7.5,      // lei / l sau lei / kWh
    val maintPerKm: Double = 0.20,      // uzură, anvelope, service (lei/km)
    val fixedMonthly: Double = 0.0,     // asigurare, taxe, rate etc. (lei/lună)
    val kmPerWeek: Double = 1200.0,     // km parcurși pe săptămână
    val greenNetPerKm: Double = 2.6,    // de la cât net/km e VERDE
    val redNetPerKm: Double = 2.0,      // sub cât net/km e ROȘU
    val shortKm: Double = 3.0           // curse până la atâția km (total) sunt mereu bune
) {
    val energyPerKm: Double get() = consumption / 100.0 * energyPrice
    val fixedPerKm: Double get() = if (kmPerWeek > 0) fixedMonthly / (kmPerWeek * 52.0 / 12.0) else 0.0
    val costPerKm: Double get() = energyPerKm + maintPerKm + fixedPerKm
}

/** Metoda de plată: în Bolt, la card nu apare nimic, la numerar apare „Numerar”. */
enum class Pay { CARD, CASH }

/** O ofertă citită de pe ecranul aplicației de ride-hailing. */
data class Offer(
    val price: Double,        // preț brut (lei)
    val pickupKm: Double,     // km până la pasager
    val tripKm: Double,       // km cu pasagerul
    val pickupMin: Int,
    val tripMin: Int,
    val pickupKnown: Boolean, // false dacă aplicația a arătat o singură distanță
    val pay: Pay = Pay.CARD,
    val buttons: Boolean = true // false = ecran fără Acceptă/Respinge (cursă acceptată automat)
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
    val acceptRe = Regex("""(?<![\p{L}])accept(?:ă|a)?(?![\p{L}])""", RegexOption.IGNORE_CASE)
    val rejectRe = Regex("""(?<![\p{L}])(?:respinge|refuz[ăa]?|decline|reject|skip)(?![\p{L}])""", RegexOption.IGNORE_CASE)

    private val cashRe = Regex("""(?<![\p{L}])(?:numerar|cash)(?![\p{L}])""", RegexOption.IGNORE_CASE)

    /** Semne că s-a trecut într-o cursă activă (folosit ca plasă de siguranță pentru „acceptat"). */
    val activeRideRe = Regex(
        """anulează cursa|anuleaza cursa|cancel ride|cancel trip|ai ajuns|am ajuns|i['’]ve arrived|i have arrived""",
        RegexOption.IGNORE_CASE
    )

    /** Butoane apăsate când ești la pasager: „Am ajuns”, „Începe cursa” etc. */
    private val arrivalRe = Regex(
        """am ajuns|ai ajuns|începe|incepe|pornește|porneste|i['’]ve arrived|i have arrived|start (?:ride|trip)|begin""",
        RegexOption.IGNORE_CASE
    )
    fun hasArrival(texts: List<String>) = texts.any { arrivalRe.containsMatchIn(it) }

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    fun hasAccept(texts: List<String>) = texts.any { acceptRe.containsMatchIn(it) }
    fun hasReject(texts: List<String>) = texts.any { rejectRe.containsMatchIn(it) }
    fun hasActiveRide(texts: List<String>) = texts.any { activeRideRe.containsMatchIn(it) }

    fun parse(texts: List<String>): Offer? {
        val buttons = texts.any { acceptRe.containsMatchIn(it) || rejectRe.containsMatchIn(it) }
        // fără butoane = cursă card acceptată automat; o citim doar dacă nu e un ecran de cursă activă
        if (!buttons && hasActiveRide(texts)) return null
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
        if (!buttons && (km.size < 2 || mins.size < 2)) return null   // fără butoane cerem toate datele
        if (km.isEmpty() || p <= 0.0 || p > 2000.0) return null
        val pickup = if (km.size >= 2) km[0] else 0.0
        val trip = if (km.size >= 2) km[1] else km[0]
        if (pickup + trip <= 0.0 || pickup + trip > 400.0) return null
        val pMin = if (mins.size >= 2) mins[0] else 0
        val tMin = if (mins.size >= 2) mins[1] else if (mins.size == 1) mins[0] else 0
        val pay = if (texts.any { cashRe.containsMatchIn(it) }) Pay.CASH else Pay.CARD
        return Offer(p, pickup, trip, pMin, tMin, km.size >= 2, pay, buttons)
    }
}

object Fmt {
    private val ro = Locale.forLanguageTag("ro-RO")
    fun d1(v: Double): String = String.format(ro, "%.1f", v)
    fun d2(v: Double): String = String.format(ro, "%.2f", v)
}
