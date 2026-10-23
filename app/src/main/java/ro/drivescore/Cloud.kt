package ro.drivescore

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sincronizare cu Supabase (fără bibliotecă, doar HTTP).
 *  - cont cu email + parolă; ID-ul unic al fiecăruia = id-ul contului Supabase
 *  - intri în comunitate cu un cod de invitație
 *  - se trimit ofertele/cursele cu locație; se descarcă zonele agregate ale celorlalți
 */
object Cloud {
    const val BASE_URL = "https://odbwfbjznpwvqygcxfqq.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_KSP4DRasJwVdiisAQJBd_Q_CvCO8khc"   // sb_publishable_... (cheia publică)

    private const val MIN_GAP_MS = 60_000L
    private const val PAGE = 1000
    private const val HISTORY_DAYS = 60L

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastRun = 0L

    private class Http(val code: Int, val body: String)

    fun configured(): Boolean = PUBLISHABLE_KEY.startsWith("sb_publishable_") || PUBLISHABLE_KEY.startsWith("eyJ")
    fun joined(c: Context): Boolean = Store.raw(c, "cloud_member") == "1"
    fun name(c: Context): String = Store.raw(c, "cloud_name") ?: ""
    fun role(c: Context): String = Store.raw(c, "cloud_role") ?: "member"
    fun uid(c: Context): String = Store.raw(c, "cloud_uid") ?: ""
    fun status(c: Context): String = Store.raw(c, "cloud_status") ?: ""

    // ---------- HTTP ----------
    private fun call(method: String, path: String, body: String?, token: String?, prefer: String? = null): Http {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("apikey", PUBLISHABLE_KEY)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Http(code, text)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- cont (email + parolă; ID-ul unic = id-ul contului Supabase) ----------
    fun loggedIn(c: Context): Boolean =
        !(Store.raw(c, "cloud_refresh") ?: "").isEmpty() && !(Store.raw(c, "cloud_email") ?: "").isEmpty()
    fun email(c: Context): String = Store.raw(c, "cloud_email") ?: ""

    private fun saveSession(c: Context, j: JSONObject): Boolean {
        val at = j.optString("access_token", "")
        if (at.isEmpty()) return false
        Store.setRaw(c, "cloud_access", at)
        Store.setRaw(c, "cloud_refresh", j.optString("refresh_token", Store.raw(c, "cloud_refresh") ?: ""))
        Store.setRaw(c, "cloud_exp", (System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L).toString())
        val id = j.optJSONObject("user")?.optString("id") ?: ""
        if (id.isNotEmpty()) {
            if (id != (Store.raw(c, "cloud_uid") ?: "")) Store.setRaw(c, "cloud_synced_ts", "")  // alt cont pe același telefon
            Store.setRaw(c, "cloud_uid", id)
        }
        return true
    }

    private fun token(c: Context): String? {
        if (!loggedIn(c)) return null
        val exp = Store.raw(c, "cloud_exp")?.toLongOrNull() ?: 0L
        val acc = Store.raw(c, "cloud_access")
        if (!acc.isNullOrEmpty() && System.currentTimeMillis() < exp - 60_000) return acc
        val ref = Store.raw(c, "cloud_refresh") ?: return null
        val r = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", ref).toString(), null)
        if (r.code !in 200..299) return null
        return if (saveSession(c, JSONObject(r.body))) Store.raw(c, "cloud_access") else null
    }

    private fun authError(r: Http): String = when {
        r.body.contains("invalid_credentials") -> "Email sau parolă greșită."
        r.body.contains("email_not_confirmed") -> "Confirmă mai întâi emailul (ți-am trimis un mesaj)."
        r.body.contains("user_already_exists") -> "Există deja un cont cu acest email. Apasă „Intru”."
        r.body.contains("weak_password") -> "Parola e prea slabă (minim 6 caractere)."
        r.body.contains("over_email_send_rate_limit") -> "Prea multe emailuri trimise. Încearcă peste o oră."
        r.body.contains("validation_failed") || r.body.contains("email_address_invalid") -> "Email invalid."
        else -> "Eroare ${r.code}."
    }

    /** Creează cont (create=true) sau intră în cont existent. Dacă e deja membru pe contul ăsta, îl recunoaște. */
    fun signIn(ctx: Context, email: String, password: String, create: Boolean, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val body = JSONObject().put("email", email.trim()).put("password", password).toString()
                val r = if (create) call("POST", "/auth/v1/signup", body, null)
                else call("POST", "/auth/v1/token?grant_type=password", body, null)
                if (r.code !in 200..299) {
                    msg = authError(r)
                } else {
                    val j = JSONObject(r.body)
                    Store.setRaw(c, "cloud_email", email.trim())
                    if (!saveSession(c, j)) {
                        Store.setRaw(c, "cloud_email", "")
                        msg = "Cont creat. Ți-am trimis un email de confirmare — apasă linkul, apoi revino și apasă „Intru”."
                    } else {
                        ok = true
                        msg = if (create) "Cont creat." else "Intrat în cont."
                        restoreMembership(c)
                    }
                }
            } catch (e: Exception) {
                msg = "Fără internet."
            }
            main.post { done(ok, msg) }
        }
    }

    /** Dacă contul e deja membru (ex. telefon nou), refac starea locală. */
    private fun restoreMembership(c: Context) {
        val tok = token(c) ?: return
        val uid = Store.raw(c, "cloud_uid") ?: return
        val r = call("GET", "/rest/v1/members?select=name,role&user_id=eq.$uid", null, tok)
        if (r.code !in 200..299) return
        val a = JSONArray(r.body)
        if (a.length() > 0) {
            Store.setRaw(c, "cloud_member", "1")
            Store.setRaw(c, "cloud_name", a.getJSONObject(0).optString("name", ""))
            Store.setRaw(c, "cloud_role", a.getJSONObject(0).optString("role", "member"))
        } else {
            Store.setRaw(c, "cloud_member", "")
            // fără cod de invitație: intri automat în comunitate, cu numele din email
            val nick = (Store.raw(c, "pending_nick") ?: "").trim()
            Store.setRaw(c, "pending_nick", "")
            val nm = nick.ifEmpty { (Store.raw(c, "cloud_email") ?: "").substringBefore('@') }.take(40).ifEmpty { "Utilizator" }
            val jr = call("POST", "/rest/v1/rpc/join_community", JSONObject().put("p_name", nm).toString(), tok)
            if (jr.code in 200..299) {
                Store.setRaw(c, "cloud_member", "1")
                Store.setRaw(c, "cloud_name", nm)
                Store.setRaw(c, "cloud_role", jr.body.trim().trim('"'))
            }
        }
    }

    /** Ieșire din cont pe acest telefon (datele din cloud rămân). */
    fun logout(c: Context) {
        for (k in listOf("cloud_access", "cloud_refresh", "cloud_exp", "cloud_email", "cloud_member", "cloud_name",
            "cloud_role", "cloud_synced_ts", "community", "cloud_status")) Store.setRaw(c, k, "")
    }

    // ---------- intrare / ieșire ----------
    fun join(ctx: Context, name: String, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val tok = token(c)
                if (tok == null) {
                    msg = "Nu ești conectat la cont. Intră mai întâi cu email și parolă."
                } else {
                    val body = JSONObject().put("p_name", name.trim()).toString()
                    val r = call("POST", "/rest/v1/rpc/join_community", body, tok)
                    if (r.code in 200..299) {
                        Store.setRaw(c, "cloud_member", "1")
                        Store.setRaw(c, "cloud_name", name.trim())
                        Store.setRaw(c, "cloud_role", r.body.trim().trim('"'))
                        ok = true
                        msg = "Ai intrat în comunitate."
                    } else {
                        msg = "Eroare ${r.code}."
                    }
                }
            } catch (e: Exception) {
                msg = "Fără internet."
            }
            main.post { done(ok, msg) }
        }
    }

    fun leave(ctx: Context, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val tok = token(c)
                val r = if (tok != null) call("POST", "/rest/v1/rpc/delete_my_data", "{}", tok) else null
                if (r != null && r.code in 200..299) {
                    for (k in listOf("cloud_member", "cloud_name", "cloud_role", "cloud_synced_ts", "community", "cloud_status")) {
                        Store.setRaw(c, k, "")
                    }
                    ok = true
                    msg = "Datele tale din cloud au fost șterse."
                } else {
                    msg = "Nu am putut șterge acum. Încearcă din nou cu internet."
                }
            } catch (e: Exception) {
                msg = "Fără internet."
            }
            main.post { done(ok, msg) }
        }
    }

    // ---------- sincronizare ----------
    fun syncAsync(ctx: Context, force: Boolean = false, done: ((String) -> Unit)? = null) {
        val c = ctx.applicationContext
        if (!configured() || !joined(c)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < MIN_GAP_MS) return
        lastRun = now
        exec.execute {
            val msg = try {
                doSync(c)
            } catch (e: Exception) {
                "Fără internet — încerc mai târziu."
            }
            Store.setRaw(c, "cloud_status", msg)
            if (done != null) main.post { done(msg) }
        }
    }

    private fun pending(c: Context, since: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val nul = JSONObject.NULL
        for (e in Store.geoEvents(c)) {
            if (e.kind != 0 || e.ts <= since) continue
            out.add(
                JSONObject().put("ts", e.ts).put("kind", 0).put("lat", e.lat).put("lon", e.lon)
                    .put("net_per_km", if (e.netPerKm.isNaN()) nul else e.netPerKm)
                    .put("price", nul).put("pickup_km", nul).put("trip_km", nul).put("net", nul)
            )
        }
        for (r in Store.history(c)) {
            if (r.ts <= since || r.lat.isNaN() || r.lon.isNaN()) continue
            out.add(
                JSONObject().put("ts", r.ts).put("kind", 1).put("lat", r.lat).put("lon", r.lon)
                    .put("net_per_km", r.netPerKm).put("price", r.price)
                    .put("pickup_km", r.pickupKm).put("trip_km", r.tripKm).put("net", r.net)
            )
        }
        out.sortBy { it.getLong("ts") }
        return out
    }

    private fun doSync(c: Context): String {
        val tok = token(c) ?: return "Nu mă pot conecta la cont."
        var since = Store.raw(c, "cloud_synced_ts")?.toLongOrNull() ?: 0L

        // 1) trimit ce e nou
        val list = pending(c, since)
        var sent = 0
        var i = 0
        while (i < list.size) {
            val chunk = list.subList(i, minOf(i + 500, list.size))
            val arr = JSONArray()
            for (o in chunk) arr.put(o)
            val r = call(
                "POST", "/rest/v1/events?on_conflict=user_id,ts,kind", arr.toString(), tok,
                "resolution=ignore-duplicates,return=minimal"
            )
            if (r.code == 401 || r.code == 403) return "Acces refuzat (cont șters sau neînscris)."
            if (r.code !in 200..299) return "Eroare la trimitere (${r.code})."
            since = chunk.last().getLong("ts")
            Store.setRaw(c, "cloud_synced_ts", since.toString())
            sent += chunk.size
            i += 500
        }

        // 2) descarc zonele agregate ale celorlalți
        val from = System.currentTimeMillis() - HISTORY_DAYS * 24L * 3600L * 1000L
        val lines = ArrayList<String>()
        var off = 0
        while (true) {
            val r = call(
                "POST", "/rest/v1/rpc/community_cells?limit=$PAGE&offset=$off",
                JSONObject().put("p_since", from).toString(), tok
            )
            if (r.code !in 200..299) return "Trimise $sent, dar nu am putut descărca zonele (${r.code})."
            val a = JSONArray(r.body)
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                lines.add(
                    o.getDouble("clat").toString() + "," + o.getDouble("clon") + "," + o.getInt("hr") + "," +
                        o.getInt("kind") + "," + o.getInt("n") + "," + o.optDouble("avg_npk", Double.NaN)
                )
            }
            if (a.length() < PAGE) break
            off += PAGE
            if (off >= 20000) break
        }
        Store.setRaw(c, "community", lines.joinToString("\n"))
        val hhmm = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        return "Sincronizat $hhmm · trimise $sent · ${lines.size} celule de la ceilalți"
    }

    /** Zonele agregate ale celorlalți, ca evenimente cu pondere (ora de azi, locația celulei). */
    fun communityEvents(c: Context): List<GeoEvent> {
        val s = Store.raw(c, "community") ?: return emptyList()
        if (s.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        val out = ArrayList<GeoEvent>()
        for (line in s.split("\n")) {
            val p = line.split(",")
            if (p.size < 6) continue
            val lat = p[0].toDoubleOrNull() ?: continue
            val lon = p[1].toDoubleOrNull() ?: continue
            val hr = p[2].toIntOrNull() ?: continue
            val kind = p[3].toIntOrNull() ?: continue
            val n = p[4].toIntOrNull() ?: continue
            val avg = p[5].toDoubleOrNull() ?: Double.NaN
            cal.set(Calendar.HOUR_OF_DAY, hr)
            cal.set(Calendar.MINUTE, 30)
            out.add(GeoEvent(cal.timeInMillis, lat, lon, kind, avg, n))
        }
        return out
    }
}
