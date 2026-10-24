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
    val pay: Int = -1,         // -1 necunoscut, 0 card, 1 numerar
    val pu: Boolean = false    // true = locația e cea de la pasager (nu de la acceptare)
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
    /** Cât așteptăm locația de la pasager; după aceea folosim locul acceptării. */
    const val PICKUP_WAIT_MS = 45 * 60 * 1000L

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- setări ----------
    fun raw(c: Context, key: String): String? = prefs(c).getString(key, null)

    fun setRaw(c: Context, key: String, value: String) {
        prefs(c).edit().putString(key, value).apply()
    }

    fun autoCard(c: Context): Boolean = raw(c, "auto_card") != "0"
    fun cardOnBottom(c: Context): Boolean = raw(c, "card_pos") == "bottom"

    /** Valorile numerice ale cheilor care încep cu prefixul (ex. kilometrii pe zile). */
    fun sumPrefix(c: Context, prefix: String): Double {
        var t = 0.0
        for ((k, v) in prefs(c).all) {
            if (k.startsWith(prefix)) t += (v as? String)?.toDoubleOrNull() ?: 0.0
        }
        return t
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
    fun history(c: Context): List<Ride> {
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
                        o.optInt("py", -1),
                        o.optBoolean("pu", false)
                    )
                )
            }
        } catch (e: Exception) {
            // istoric corupt: îl ignorăm
        }
        return out
    }

    private fun save(c: Context, rides: List<Ride>) {
        val arr = JSONArray()
        for (r in rides.take(MAX_HISTORY)) {
            arr.put(
                JSONObject()
                    .put("ts", r.ts).put("price", r.price).put("pk", r.pickupKm).put("tk", r.tripKm)
                    .put("net", r.net).put("npk", r.netPerKm).put("lv", r.level.ordinal).put("py", r.pay).put("pu", r.pu)
                    .also { j ->
                        if (!r.lat.isNaN() && !r.lon.isNaN()) j.put("la", r.lat).put("lo", r.lon)
                    }
            )
        }
        prefs(c).edit().putString(K_HISTORY, arr.toString()).apply()
    }

    /** Salvează cursa și întoarce ts-ul ei. */
    fun addRide(c: Context, o: Offer, r: Result, lat: Double? = null, lon: Double? = null): Long {
        val ts = System.currentTimeMillis()
        val list = ArrayList<Ride>()
        list.add(
            Ride(
                ts, o.price, o.pickupKm, o.tripKm, r.net, r.netPerKm, r.level,
                lat ?: Double.NaN, lon ?: Double.NaN, o.pay.ordinal
            )
        )
        list.addAll(history(c))
        save(c, list)
        return ts
    }

    /** Pune locația de la pasager pe cursa cu ts-ul dat. */
    fun setRidePickup(c: Context, ts: Long, lat: Double, lon: Double) {
        save(c, history(c).map { if (it.ts == ts) it.copy(lat = lat, lon = lon, pu = true) else it })
    }

    /** Cursă cu locația de preluare gata de folosit (confirmată sau suficient de veche). */
    fun pickupReady(r: Ride, now: Long): Boolean =
        !r.lat.isNaN() && !r.lon.isNaN() && (r.pu || now - r.ts >= PICKUP_WAIT_MS)

    fun deleteRide(c: Context, ts: Long) {
        save(c, history(c).filter { it.ts != ts })
    }

    fun clearHistory(c: Context) {
        prefs(c).edit().remove(K_HISTORY).apply()
    }

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
                out.add(GeoEvent(ts, la, lo, 0, npk))
            }
        }
        val nowMs = System.currentTimeMillis()
        for (r in history(c)) {
            if (!pickupReady(r, nowMs)) continue
            out.add(GeoEvent(r.ts, r.lat, r.lon, 1, r.netPerKm))
        }
        return out
    }

    // ---------- diagnostic: ultimul text citat din aplicația de ride-hailing ----------
    fun setDump(c: Context, text: String) {
        prefs(c).edit().putString(K_DUMP, text).apply()
    }

    fun dump(c: Context): String = prefs(c).getString(K_DUMP, null) ?: ""
}
