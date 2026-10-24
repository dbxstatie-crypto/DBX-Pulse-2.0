package ro.drivescore

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kilometri parcurși cât timp „tura” e pornită (GPS), separat de cursele din istoric.
 * Km în gol = km din tură − km ai curselor (până la pasager + cu pasagerul).
 */
object Odometer {
    private const val P = "odo_"
    @Volatile private var lastTouch = 0L

    private fun day(ts: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))

    fun on(c: Context): Boolean = Store.raw(c, "shift_on") == "1"

    fun setOn(c: Context, v: Boolean) {
        Store.setRaw(c, "shift_on", if (v) "1" else "0")
        if (v) Store.setRaw(c, "shift_last", System.currentTimeMillis().toString())
    }

    /** Apelat la fiecare eveniment din Bolt: arată că ești încă în tură. */
    fun touch(c: Context) {
        val n = System.currentTimeMillis()
        if (n - lastTouch < 30_000L) return
        lastTouch = n
        if (on(c)) Store.setRaw(c, "shift_last", n.toString())
    }

    /** Oprește tura singură dacă n-a mai fost activitate în Bolt de multă vreme. */
    fun checkIdle(c: Context, maxMs: Long) {
        if (!on(c)) return
        val last = Store.raw(c, "shift_last")?.toLongOrNull() ?: 0L
        if (last > 0L && System.currentTimeMillis() - last > maxMs) setOn(c, false)
    }

    fun add(c: Context, km: Double) {
        val k = P + day(System.currentTimeMillis())
        val cur = Store.raw(c, k)?.toDoubleOrNull() ?: 0.0
        Store.setRaw(c, k, (cur + km).toString())
    }

    fun today(c: Context): Double = Store.raw(c, P + day(System.currentTimeMillis()))?.toDoubleOrNull() ?: 0.0
    fun total(c: Context): Double = Store.sumPrefix(c, P)
}
