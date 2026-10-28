package ro.drivescore

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** O cursă acceptată, salvată în istoric. */
data class Ride(
    val ts: Long,
    val price: Double,
    val pickupKm: Double,
    val tripKm: Double,
    val net: Double,
    val netPerKm: Double,
    val level: Level,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val pay: Int = -1,           // 0 = card, 1 = cash, -1 = necunoscut (curse vechi)
    val canceled: Int = 0        // 0 = valabilă, 1 = anulată de pasager, 2 = anulată de tine (rămâne în listă, nu se numără)
) {
    val totalKm: Double get() = pickupKm + tripKm
}

object Store {
    private const val FILE = "drivescore"
    private const val K_HISTORY = "history"
    private const val K_DUMP = "dump"
    private const val K_OFFERS = "offers"
    private const val MAX_HISTORY = 1000
    private const val MAX_OFFERS = 4000

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- setări ----------
    fun raw(c: Context, key: String): String? = prefs(c).getString(key, null)

    fun setRaw(c: Context, key: String, value: String) {
        prefs(c).edit().putString(key, value).apply()
    }

    fun config(c: Context): Config {
        val d = Config()
        fun g(k: String, def: Double): Double =
            prefs(c).getString(k, null)?.replace(',', '.')?.toDoubleOrNull() ?: def
        return Config(
            commissionPct = g("comm", d.commissionPct),
            consumption = g("cons", d.consumption),
            energyPrice = g("eprice", d.energyPrice),
            maintPerKm = g("maint", d.maintPerKm),
            fixedMonthly = g("fix", d.fixedMonthly),
            kmPerWeek = g("kmw", d.kmPerWeek),
            greenNetPerKm = g("green", d.greenNetPerKm),
            redNetPerKm = g("red", d.redNetPerKm),
            shortKm = g("short", d.shortKm)
        )
    }

    // ---------- istoric ----------
    /** Doar cursele care se numără (fără cele anulate). */
    fun history(c: Context): List<Ride> = historyAll(c).filter { it.canceled == 0 }

    /** Toate cursele, inclusiv cele marcate „anulate”. */
    fun historyAll(c: Context): List<Ride> {
        val s = prefs(c).getString(K_HISTORY, null) ?: return emptyList()
        val out = ArrayList<Ride>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Ride(
                        o.getLong("ts"), o.getDouble("price"), o.getDouble("pk"), o.getDouble("tk"),
                        o.getDouble("net"), o.getDouble("npk"),
                        Level.values()[o.getInt("lv").coerceIn(0, 2)],
                        if (o.has("la")) o.getDouble("la") else Double.NaN,
                        if (o.has("lo")) o.getDouble("lo") else Double.NaN,
                        if (o.has("pay")) o.getInt("pay") else -1,
                        if (o.has("cx")) o.getInt("cx") else 0
                    )
                )
            }
        } catch (e: Exception) {
            // istoric corupt: îl ignorăm
        }
        return out
    }

    private fun fd(d: Double): Double = if (d.isNaN() || d.isInfinite()) 0.0 else d

    private fun save(c: Context, rides: List<Ride>) {
        val arr = JSONArray()
        for (r in rides.take(MAX_HISTORY)) {
            arr.put(
                JSONObject()
                    .put("ts", r.ts).put("price", fd(r.price)).put("pk", fd(r.pickupKm)).put("tk", fd(r.tripKm))
                    .put("net", fd(r.net)).put("npk", fd(r.netPerKm)).put("lv", r.level.ordinal)
                    .also { j ->
                        if (!r.lat.isNaN() && !r.lon.isNaN()) j.put("la", r.lat).put("lo", r.lon)
                        if (r.pay >= 0) j.put("pay", r.pay)
                        if (r.canceled > 0) j.put("cx", r.canceled)
                    }
            )
        }
        prefs(c).edit().putString(K_HISTORY, arr.toString()).apply()
    }

    fun addRide(c: Context, o: Offer, r: Result, lat: Double? = null, lon: Double? = null): Long {
        val ts = System.currentTimeMillis()
        val list = ArrayList<Ride>()
        list.add(
            Ride(
                ts, o.price, o.pickupKm, o.tripKm, r.net, r.netPerKm, r.level,
                lat ?: Double.NaN, lon ?: Double.NaN, if (o.cash) 1 else 0
            )
        )
        list.addAll(historyAll(c))
        save(c, list)
        return ts
    }

    /** Scoate cea mai recentă cursă (ex. pasagerul a anulat). */
    fun deleteLatestRide(c: Context): Boolean {
        val h = historyAll(c)
        if (h.isEmpty()) return false
        save(c, h.drop(1))
        return true
    }

    /** Ștergere definitivă (doar când o ceri tu). */
    fun deleteRide(c: Context, ts: Long) {
        save(c, historyAll(c).filter { it.ts != ts })
    }

    /** Marchează cursa ca anulată: rămâne în listă, dar nu se mai numără. by: 1 = pasager, 2 = tu. */
    fun cancelRide(c: Context, ts: Long, by: Int) {
        save(c, historyAll(c).map { if (it.ts == ts) it.copy(canceled = by) else it })
    }

    /** Refă o cursă marcată anulată (o numără din nou). */
    fun restoreRide(c: Context, ts: Long) {
        save(c, historyAll(c).map { if (it.ts == ts) it.copy(canceled = 0) else it })
    }

    fun clearHistory(c: Context) {
        prefs(c).edit().remove(K_HISTORY).putString("emp_total", "0").putString("ride_rem", "0").apply()
    }

    // ---------- km parcurși cu Bolt deschis (GPS), pentru „km în gol” ----------
    private fun day(): Long = java.time.LocalDate.now().toEpochDay()
    private fun dbl(c: Context, k: String): Double = raw(c, k)?.toDoubleOrNull() ?: 0.0
    fun addTrackedKm(c: Context, km: Double) {
        setRaw(c, "trk_" + day(), (dbl(c, "trk_" + day()) + km).toString())
        setRaw(c, "trk_total", (dbl(c, "trk_total") + km).toString())
    }
    // ---------- tips ----------
    fun addTips(c: Context, amt: Double) {
        setRaw(c, "tips_" + day(), (dbl(c, "tips_" + day()) + amt).toString())
        setRaw(c, "tips_total", (dbl(c, "tips_total") + amt).toString())
    }
    fun tipsToday(c: Context): Double = dbl(c, "tips_" + day())
    fun tipsTotal(c: Context): Double = dbl(c, "tips_total")
    fun resetTipsToday(c: Context) {
        setRaw(c, "tips_total", maxOf(0.0, tipsTotal(c) - tipsToday(c)).toString())
        setRaw(c, "tips_" + day(), "0")
    }

    // ---------- km în gol (măsurați direct cu GPS, doar când nu ești într-o cursă) ----------
    fun shiftOn(c: Context): Boolean = raw(c, "shift_on") != "0"
    fun setShift(c: Context, on: Boolean) = setRaw(c, "shift_on", if (on) "1" else "0")
    /** Cursă acceptată: următorii km (cât a anunțat Bolt) nu se numără ca „în gol”. */
    fun startRide(c: Context, km: Double) = setRaw(c, "ride_rem", maxOf(0.0, dbl(c, "ride_rem") + km).toString())
    fun addDriveKm(c: Context, km: Double) {
        addTrackedKm(c, km)   // toți km măsurați cu tura pornită (în gol + în cursă), ca să se vadă ce se numără
        val rem = dbl(c, "ride_rem")
        if (rem > 0.0) { setRaw(c, "ride_rem", maxOf(0.0, rem - km).toString()); return }
        setRaw(c, "emp_" + day(), (dbl(c, "emp_" + day()) + km).toString())
        setRaw(c, "emp_total", (dbl(c, "emp_total") + km).toString())
    }
    fun rideRemaining(c: Context): Double = dbl(c, "ride_rem")

    // ---------- timp petrecut pe celule (pentru „oferte pe oră”) ----------
    private var dwell: HashMap<String, Double>? = null
    private var dwellFlushAt = 0L

    @Synchronized
    private fun dwellMap(c: Context): HashMap<String, Double> {
        dwell?.let { return it }
        val m = HashMap<String, Double>()
        for (line in (raw(c, "dwell") ?: "").split("\n")) {
            val p = line.split("=")
            if (p.size == 2) p[1].toDoubleOrNull()?.let { m[p[0]] = it }
        }
        dwell = m
        return m
    }

    @Synchronized
    fun addDwell(c: Context, lat: Double, lon: Double, sec: Double) {
        val m = dwellMap(c)
        val cal = java.util.Calendar.getInstance()
        val d = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val wk = d == java.util.Calendar.SATURDAY || d == java.util.Calendar.SUNDAY
        val k = Math.floor(lat / Zones.CELL_DEG).toInt().toString() + ":" + Math.floor(lon / Zones.LON_STEP).toInt() + ":" +
            cal.get(java.util.Calendar.HOUR_OF_DAY) + ":" + (if (wk) 1 else 0)
        m[k] = (m[k] ?: 0.0) + sec
        val now = System.currentTimeMillis()
        if (now - dwellFlushAt > 60000L) {
            dwellFlushAt = now
            setRaw(c, "dwell", m.entries.joinToString("\n") { it.key + "=" + String.format(java.util.Locale.US, "%.0f", it.value) })
        }
    }

    @Synchronized
    fun dwellSnapshot(c: Context): Map<String, Double> = HashMap(dwellMap(c))

    fun emptyToday(c: Context): Double = dbl(c, "emp_" + day())
    fun emptyTotal(c: Context): Double = dbl(c, "emp_total")

    fun trackedToday(c: Context): Double = dbl(c, "trk_" + day())
    fun trackedTotal(c: Context): Double = dbl(c, "trk_total")

    // ---------- oferte văzute, cu locație (pentru zone) ----------
    /** Linie: ts,lat,lon,net/km. Se păstrează ultimele MAX_OFFERS. */
    fun addOffer(c: Context, lat: Double, lon: Double, netPerKm: Double) {
        val line = "${System.currentTimeMillis()},$lat,$lon,$netPerKm"
        val old = prefs(c).getString(K_OFFERS, "") ?: ""
        val lines = ArrayList<String>(if (old.isEmpty()) emptyList() else old.split("\n"))
        lines.add(line)
        val kept = if (lines.size > MAX_OFFERS) lines.subList(lines.size - MAX_OFFERS, lines.size) else lines
        prefs(c).edit().putString(K_OFFERS, kept.joinToString("\n")).apply()
    }

    fun clearOffers(c: Context) {
        prefs(c).edit().remove(K_OFFERS).apply()
    }

    /** Toate punctele cu locație: ofertele văzute (kind 0) + cursele acceptate (kind 1). */
    fun geoEvents(c: Context): List<GeoEvent> {
        val out = ArrayList<GeoEvent>()
        val s = prefs(c).getString(K_OFFERS, "") ?: ""
        if (s.isNotEmpty()) {
            for (line in s.split("\n")) {
                val p = line.split(",")
                if (p.size < 4) continue
                val ts = p[0].toLongOrNull() ?: continue
                val la = p[1].toDoubleOrNull() ?: continue
                val lo = p[2].toDoubleOrNull() ?: continue
                val npk = p[3].toDoubleOrNull() ?: Double.NaN
                out.add(GeoEvent(ts, la, lo, 0, npk, 1, true))
            }
        }
        for (r in history(c)) {
            if (r.lat.isNaN() || r.lon.isNaN()) continue
            out.add(GeoEvent(r.ts, r.lat, r.lon, 1, r.netPerKm, 1, true))
        }
        return out
    }

    // ---------- diagnostic: ultimul text citat din aplicația de ride-hailing ----------
    fun setDump(c: Context, text: String) {
        prefs(c).edit().putString(K_DUMP, text).apply()
    }

    fun dump(c: Context): String = prefs(c).getString(K_DUMP, null) ?: ""

    // ---------- jurnal de evenimente (ultimele 60), pentru diagnostic ----------
    @Synchronized
    fun logEv(c: Context, msg: String) {
        try {
            val t = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val old = (prefs(c).getString("evlog", null) ?: "").split("\n").filter { it.isNotBlank() }
            val line = t + " " + msg.replace("\n", " ").take(500)
            prefs(c).edit().putString("evlog", (old + line).takeLast(60).joinToString("\n")).apply()
        } catch (e: Exception) { }
    }
    fun evlog(c: Context): String = prefs(c).getString("evlog", null) ?: ""
}
