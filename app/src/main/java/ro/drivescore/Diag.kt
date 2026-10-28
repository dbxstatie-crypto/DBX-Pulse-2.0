package ro.drivescore

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raport de diagnostic într-un fișier .txt, ușor de trimis (WhatsApp, email etc.). Nu conține parole, email sau locații. */
object Diag {
    private val fmt get() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    fun file(c: Context): File { val d = File(c.cacheDir, "diag"); d.mkdirs(); return File(d, "dbx-diagnostic.txt") }

    fun build(c: Context): String {
        val sb = StringBuilder()
        fun h(t: String) { sb.append("\n=== ").append(t).append(" ===\n") }
        sb.append("DBX Pulse — raport de diagnostic\n")
        sb.append("Generat: ").append(fmt.format(Date())).append('\n')
        sb.append("Versiune: ").append(Updater.currentName(c)).append(" (cod ").append(Updater.currentCode(c)).append(")\n")
        sb.append("Telefon: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n')

        h("STARE")
        val acc = try { (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(c.packageName) } catch (e: Exception) { false }
        sb.append("Accesibilitate activă: ").append(if (acc) "da" else "NU").append('\n')
        sb.append("Permisiune locație: ").append(if (Geo.hasPermission(c)) "da" else "NU").append('\n')
        sb.append("Tura pornită: ").append(if (Store.shiftOn(c)) "da" else "nu").append('\n')
        sb.append("Cont: ").append(if (Cloud.loggedIn(c)) "conectat, rol " + Cloud.role(c) else "neconectat").append('\n')
        sb.append("Km în gol azi / total: ").append(Fmt.d2(Store.emptyToday(c))).append(" / ").append(Fmt.d2(Store.emptyTotal(c))).append('\n')
        sb.append("Km măsurați azi (tura pornită): ").append(Fmt.d2(Store.trackedToday(c))).append('\n')
        sb.append("Km rămași din cursa acceptată: ").append(Fmt.d2(Store.rideRemaining(c))).append('\n')

        h("SETĂRI")
        val g = Store.config(c)
        sb.append("comision ").append(g.commissionPct).append("% · consum ").append(g.consumption).append(" · preț energie ").append(g.energyPrice)
            .append(" · uzură ").append(g.maintPerKm).append(" lei/km · fixe ").append(g.fixedMonthly).append(" lei/lună · km/săpt ").append(g.kmPerWeek).append('\n')
        sb.append("verde ≥ ").append(g.greenNetPerKm).append(" · roșu < ").append(g.redNetPerKm).append(" · cursă scurtă ≤ ").append(g.shortKm).append(" km\n")

        h("ULTIMELE 15 CURSE SALVATE")
        val rides = Store.historyAll(c).take(15)
        if (rides.isEmpty()) sb.append("(niciuna)\n")
        for (r in rides) {
            sb.append(fmt.format(Date(r.ts))).append(" · ").append(Fmt.d2(r.price)).append(" lei · ").append(Fmt.d1(r.pickupKm)).append("+").append(Fmt.d1(r.tripKm))
                .append(" km · net ").append(Fmt.d2(r.net)).append(" · ").append(Fmt.d2(r.netPerKm)).append(" lei/km · ").append(r.level.name)
                .append(" · ").append(when (r.pay) { 1 -> "CASH"; 0 -> "CARD"; else -> "?" })
                .append(when (r.canceled) { 1 -> " · ANULATĂ DE PASAGER"; 2 -> " · ANULATĂ DE ȘOFER"; else -> "" }).append('\n')
        }

        h("JURNAL EVENIMENTE (cele mai noi jos)")
        val ev = Store.evlog(c)
        sb.append(if (ev.isEmpty()) "(gol)\n" else ev + "\n")

        h("ULTIMUL ECRAN CITIT DIN APLICAȚIA DE RIDESHARING")
        val d = Store.dump(c)
        sb.append(if (d.isEmpty()) "(nimic)\n" else d + "\n")

        val crash = Store.raw(c, "last_crash") ?: ""
        if (crash.isNotEmpty()) { h("ULTIMA EROARE A APLICAȚIEI"); sb.append(crash).append('\n') }
        return sb.toString()
    }

    fun write(c: Context): File {
        val f = file(c)
        f.writeText(build(c), Charsets.UTF_8)
        return f
    }

    /** Creează fișierul și deschide meniul de trimitere (WhatsApp, email, Drive...). */
    fun share(ctx: Context) {
        write(ctx)
        val uri = Uri.parse("content://ro.drivescore.update/dbx-diagnostic.txt")
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.putExtra(Intent.EXTRA_SUBJECT, "DBX Pulse — diagnostic")
        i.clipData = ClipData.newRawUri("diagnostic", uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Trimite diagnosticul"))
    }
}
