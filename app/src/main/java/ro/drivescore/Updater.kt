package ro.drivescore

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Actualizare din aplicație: citește rândul din tabelul app_release (Supabase), descarcă APK-ul și deschide instalarea. */
object Updater {
    class Info(val code: Int, val name: String, val url: String, val minCode: Int, val notes: String)

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var checking = false

    @Suppress("DEPRECATION")
    fun currentCode(c: Context): Int = try { c.packageManager.getPackageInfo(c.packageName, 0).versionCode } catch (e: Exception) { 0 }

    @Suppress("DEPRECATION")
    fun currentName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Ultima versiune cunoscută (salvată local la ultima verificare). */
    fun known(c: Context): Info? {
        val code = Store.raw(c, "upd_code")?.toIntOrNull() ?: return null
        return Info(code, Store.raw(c, "upd_name") ?: "", Store.raw(c, "upd_url") ?: "",
            Store.raw(c, "upd_min")?.toIntOrNull() ?: 0, Store.raw(c, "upd_notes") ?: "")
    }

    fun available(c: Context): Info? = known(c)?.takeIf { it.code > currentCode(c) && it.url.isNotEmpty() }
    fun mandatory(c: Context): Boolean { val i = known(c) ?: return false; return i.minCode > currentCode(c) && i.url.isNotEmpty() }

    /** Verifică la server (cel mult o dată la 5 minute, dacă nu e forțat). */
    fun check(ctx: Context, force: Boolean, done: () -> Unit) {
        val c = ctx.applicationContext
        val last = Store.raw(c, "upd_check_ts")?.toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - last < 5 * 60000L) return
        if (checking) return
        checking = true
        exec.execute {
            try {
                val conn = URL(Cloud.BASE_URL + "/rest/v1/app_release?select=*&id=eq.1").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 20000
                conn.setRequestProperty("apikey", Cloud.PUBLISHABLE_KEY)
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                val a = JSONArray(body)
                if (a.length() > 0) {
                    val o = a.getJSONObject(0)
                    Store.setRaw(c, "upd_code", o.optInt("version_code", 0).toString())
                    Store.setRaw(c, "upd_name", o.optString("version_name", ""))
                    Store.setRaw(c, "upd_url", o.optString("apk_url", ""))
                    Store.setRaw(c, "upd_min", o.optInt("min_version_code", 0).toString())
                    Store.setRaw(c, "upd_notes", o.optString("notes", ""))
                    Store.setRaw(c, "upd_check_ts", System.currentTimeMillis().toString())
                }
            } catch (e: Exception) { }
            checking = false
            main.post { done() }
        }
    }

    fun apkFile(c: Context): File { val d = File(c.cacheDir, "update"); d.mkdirs(); return File(d, "dbx-pulse.apk") }

    /** Descarcă APK-ul; progress = procent 0..100 (sau -1 dacă nu se știe mărimea). */
    fun download(ctx: Context, url: String, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val f = apkFile(c)
                val tmp = File(f.parentFile, "dbx-pulse.part")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) {
                    msg = "Eroare la descărcare (${conn.responseCode})."
                } else {
                    val total = conn.contentLengthLong
                    var got = 0L
                    var lastPct = -2
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val pct = if (total > 0) (got * 100 / total).toInt() else -1
                                if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                            }
                        }
                    }
                    conn.disconnect()
                    if (got < 100_000L) { msg = "Fișier descărcat incomplet. Încearcă din nou." }
                    else { f.delete(); tmp.renameTo(f); ok = true; msg = "ok" }
                }
            } catch (e: Exception) { msg = "Fără internet sau descărcare întreruptă." }
            main.post { done(ok, msg) }
        }
    }

    /** Deschide instalatorul Android. Dacă lipsește permisiunea „instalează aplicații necunoscute”, deschide setarea. Returnează false dacă a trimis la setări. */
    fun install(ctx: Context): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return false
        }
        val uri = Uri.parse("content://ro.drivescore.update/dbx-pulse.apk")
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}

/** Dă altor aplicații acces la fișiere create de noi: APK-ul descărcat (instalare) și raportul de diagnostic (trimitere). */
class UpdateProvider : ContentProvider() {
    private fun target(uri: Uri): File {
        val c = context!!
        return if (uri.lastPathSegment == "dbx-diagnostic.txt") Diag.file(c) else Updater.apkFile(c)
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = target(uri)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = target(u)
        val cur = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cur.addRow(arrayOf<Any>(f.name, f.length()))
        return cur
    }
    override fun getType(u: Uri): String =
        if (u.lastPathSegment == "dbx-diagnostic.txt") "text/plain" else "application/vnd.android.package-archive"
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
