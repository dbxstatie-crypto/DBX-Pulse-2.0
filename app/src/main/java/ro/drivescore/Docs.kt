package ro.drivescore

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.util.Calendar

data class DocItem(val id: Long, val type: String, val expiry: Long, val photo: String, val lastNotif: Long)

/** Acte cu dată de expirare (rovinietă, ITP...) + remindere zilnice cu 3 zile înainte, până rezolvi. */
object Docs {
    const val WARN_DAYS = 3L
    val TYPES = arrayOf("Rovinietă", "ITP", "RCA", "CASCO", "Permis de conducere", "Atestat / autorizație", "Altele")

    fun today(): Long = LocalDate.now().toEpochDay()
    fun epochOf(y: Int, m0: Int, d: Int): Long = LocalDate.of(y, m0 + 1, d).toEpochDay()
    fun fmt(epoch: Long): String {
        val d = LocalDate.ofEpochDay(epoch)
        return String.format("%02d.%02d.%d", d.dayOfMonth, d.monthValue, d.year)
    }
    fun statusText(left: Long): String = when {
        left > 1 -> "expiră în $left zile"
        left == 1L -> "expiră mâine"
        left == 0L -> "expiră azi"
        left == -1L -> "a expirat ieri"
        else -> "a expirat de ${-left} zile"
    }

    fun load(c: Context): MutableList<DocItem> {
        val out = mutableListOf<DocItem>()
        try {
            val a = JSONArray(Store.raw(c, "docs") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(DocItem(o.getLong("id"), o.getString("type"), o.getLong("expiry"), o.optString("photo", ""), o.optLong("lastNotif", -1)))
            }
        } catch (e: Exception) { }
        return out.sortedBy { it.expiry }.toMutableList()
    }

    fun save(c: Context, l: List<DocItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("expiry", it.expiry).put("photo", it.photo).put("lastNotif", it.lastNotif)) }
        Store.setRaw(c, "docs", a.toString())
    }

    fun add(c: Context, type: String, expiry: Long): Long {
        val id = System.currentTimeMillis()
        val l = load(c); l.add(DocItem(id, type, expiry, "", -1)); save(c, l)
        return id
    }

    fun update(c: Context, id: Long, f: (DocItem) -> DocItem) = save(c, load(c).map { if (it.id == id) f(it) else it })

    fun delete(c: Context, id: Long) {
        load(c).firstOrNull { it.id == id }?.let { if (it.photo.isNotEmpty()) photoFile(c, it.photo).delete() }
        save(c, load(c).filter { it.id != id })
    }

    fun photoFile(c: Context, name: String): File = File(File(c.filesDir, "docs").apply { mkdirs() }, name)

    /** Trimite notificări pentru actele care expiră în ≤3 zile sau au expirat; maxim una pe zi pentru fiecare act. */
    fun check(c: Context) {
        val t = today()
        val l = load(c)
        var changed = false
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("docs", "Acte și valabilitate", NotificationManager.IMPORTANCE_HIGH))
        val out = l.map { d ->
            val left = d.expiry - t
            if (left > WARN_DAYS || d.lastNotif == t) d else {
                val open = PendingIntent.getActivity(
                    c, d.id.toInt(), Intent(c, MainActivity::class.java).putExtra("tab", 3),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(c, "docs")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(d.type + " – " + statusText(left))
                    .setContentText("Valabil până la " + fmt(d.expiry) + ". Apasă ca să rezolvi.")
                    .setContentIntent(open).setAutoCancel(true).build()
                nm.notify(d.id.toInt(), n)
                changed = true
                d.copy(lastNotif = t)
            }
        }
        if (changed) save(c, out)
    }

    /** Alarmă zilnică la 09:00 (aproximativ). Se reprogramează la pornirea aplicației și după repornirea telefonului. */
    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(c, 77, Intent(c, DocReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }
}

class DocReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Docs.check(c)
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Docs.schedule(c)
    }
}

/** Provider minimal ca aplicația de cameră să poată salva poza direct în folderul aplicației (fără bibliotecă). */
class PhotoProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains('/') || name.contains("..")) throw FileNotFoundException()
        return ParcelFileDescriptor.open(Docs.photoFile(context!!, name), ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
