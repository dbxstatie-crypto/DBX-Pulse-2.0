package ro.drivescore

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.floor

/** Nume reale pentru zone (cartier / localitate), pentru orice oraș, din OpenStreetMap; se țin minte pe telefon. */
object Names {
    private val mem = HashMap<String, String>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var busy = false
    private var failures = 0

    fun init(c: Context) { app = c.applicationContext }

    private fun key(lat: Double, lon: Double) = floor(lat / Zones.CELL_DEG).toInt().toString() + ":" + floor(lon / Zones.LON_STEP).toInt()

    @Synchronized
    fun get(lat: Double, lon: Double): String? {
        val k = key(lat, lon)
        mem[k]?.let { return it }
        val c = app ?: return null
        val v = Store.raw(c, "nm_$k")
        if (!v.isNullOrEmpty()) { mem[k] = v; return v }
        return null
    }

    /** Caută în fundal numele zonelor care încă nu-l au (maxim 12, ~1 cerere/secundă); apoi cheamă done o singură dată. */
    fun prefetch(zones: List<Zone>, done: () -> Unit) {
        val c = app ?: return
        if (busy || failures >= 6) return
        val todo = zones.filter { get(it.lat, it.lon) == null }.take(12)
        if (todo.isEmpty()) return
        busy = true
        exec.execute {
            var got = 0
            try {
                for (z in todo) {
                    val n = lookup(z.lat, z.lon)
                    if (n != null) {
                        synchronized(this) { mem[key(z.lat, z.lon)] = n }
                        Store.setRaw(c, "nm_" + key(z.lat, z.lon), n)
                        got++
                        failures = 0
                    } else failures++
                    Thread.sleep(1100)
                }
            } catch (e: Exception) { failures++ }
            busy = false
            if (got > 0) main.post { done() }
        }
    }

    private fun lookup(lat: Double, lon: Double): String? {
        return try {
            val u = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&accept-language=ro&lat=$lat&lon=$lon")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "DBXPulse/2.3 (aplicatie sofer)")
            if (conn.responseCode !in 200..299) return null
            val a = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONObject("address") ?: return null
            for (f in listOf("suburb", "neighbourhood", "quarter", "city_district", "hamlet", "village", "town", "city", "municipality")) {
                val v = a.optString(f, "")
                if (v.isNotEmpty()) return v.take(40)
            }
            null
        } catch (e: Exception) { null }
    }
}
