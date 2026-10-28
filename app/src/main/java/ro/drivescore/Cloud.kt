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
    /** false = oricine își face cont și intră direct; true = doar cu cod de invitație (vezi update_invitatii.sql). */
    const val REQUIRE_INVITE = true
    /** Pagina „Email confirmat” (confirmat.html), setată după ce o pui online; trebuie adăugată și în Supabase → Redirect URLs. */
    const val CONFIRM_URL = "https://dbxpulse.netlify.app/confirmat.html"
    const val BASE_URL = "https://odbwfbjznpwvqygcxfqq.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_KSP4DRasJwVdiisAQJBd_Q_CvCO8khc"   // sb_publishable_... (cheia publică)

    private const val MIN_GAP_MS = 60_000L
    private const val PAGE = 1000
    private const val HISTORY_DAYS = 60L

    private val exec = Executors.newSingleThreadExecutor { r -> Thread { try { r.run() } catch (e: Throwable) { } } }
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
                val r = if (create) call("POST", "/auth/v1/signup" + (if (CONFIRM_URL.isNotEmpty()) "?redirect_to=" + java.net.URLEncoder.encode(CONFIRM_URL, "UTF-8") else ""), body, null)
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
        val r = call("GET", "/rest/v1/members?select=name,role,unlimited,access_until,spins&user_id=eq.$uid", null, tok)
        if (r.code !in 200..299) return
        val a = JSONArray(r.body)
        if (a.length() > 0) {
            val o = a.getJSONObject(0)
            Store.setRaw(c, "cloud_member", "1")
            Store.setRaw(c, "cloud_name", o.optString("name", ""))
            Store.setRaw(c, "cloud_role", o.optString("role", "member"))
            Store.setRaw(c, "cloud_unl", if (o.optBoolean("unlimited", false)) "1" else "0")
            var until = 0L
            if (!o.isNull("access_until")) {
                try { until = java.time.OffsetDateTime.parse(o.getString("access_until")).toInstant().toEpochMilli() } catch (e: Exception) { }
            }
            Store.setRaw(c, "cloud_until", until.toString())
            Store.setRaw(c, "cloud_spins", o.optInt("spins", 0).toString())
        } else {
            Store.setRaw(c, "cloud_member", "")
            // cont nou cu cod de invitație dat la înregistrare
            val code = (Store.raw(c, "pending_code") ?: "").trim()
            val nick = (Store.raw(c, "pending_nick") ?: "").trim()
            if (!REQUIRE_INVITE) {
                Store.setRaw(c, "pending_code", ""); Store.setRaw(c, "pending_nick", "")
                val nm0 = nick.ifEmpty { (Store.raw(c, "cloud_email") ?: "").substringBefore('@') }.take(40).ifEmpty { "Utilizator" }
                val jr0 = call("POST", "/rest/v1/rpc/join_community", JSONObject().put("p_name", nm0).toString(), tok)
                if (jr0.code in 200..299) restoreMembership(c)
            } else if (code.isNotEmpty()) {
                Store.setRaw(c, "pending_code", ""); Store.setRaw(c, "pending_nick", "")
                val nm = nick.ifEmpty { (Store.raw(c, "cloud_email") ?: "").substringBefore('@') }.take(40).ifEmpty { "Utilizator" }
                val jr = call("POST", "/rest/v1/rpc/redeem_invite", JSONObject().put("p_code", code).put("p_name", nm).toString(), tok)
                if (jr.code in 200..299) restoreMembership(c)
                else Store.setRaw(c, "join_error", if (jr.body.contains("invalid invite")) "Cod invalid sau deja folosit." else "Eroare ${jr.code}.")
            }
        }
    }

    /** Are acces: admin, nelimitat sau neexpirat. Dacă încă nu știm (versiune veche), nu blocăm. */
    fun hasAccess(c: Context): Boolean {
        if (!REQUIRE_INVITE) return true
        if (role(c) == "admin") return true
        val unl = Store.raw(c, "cloud_unl")
        if (unl == null || unl.isEmpty() || unl == "1") return true
        val until = Store.raw(c, "cloud_until")?.toLongOrNull() ?: 0L
        return until > System.currentTimeMillis()
    }

    /** Reîmprospătează accesul (cel mult o dată la 10 minute). */
    fun refreshAccess(c: Context) {
        Store.setRaw(c, "mem_check_ts", System.currentTimeMillis().toString())
        try { restoreMembership(c) } catch (e: Exception) { }
    }

    fun refreshAccessAsync(ctx: Context, force: Boolean, done: () -> Unit) {
        val c = ctx.applicationContext
        val last = Store.raw(c, "mem_check_ts")?.toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - last < 10 * 60000L) return
        if (!loggedIn(c)) return
        // IMPORTANT: token() poate face cerere de rețea (reînnoirea sesiunii după ~1 oră), deci doar pe fir de fundal
        exec.execute {
            try { if (token(c) != null) refreshAccess(c) } catch (e: Throwable) { }
            main.post { done() }
        }
    }

    class PayReq(val userId: String, val name: String, val email: String, val ts: String)

    fun accessUntil(c: Context): Long = Store.raw(c, "cloud_until")?.toLongOrNull() ?: 0L
    fun isUnlimited(c: Context): Boolean {
        if (role(c) == "admin") return true
        val u = Store.raw(c, "cloud_unl")
        return u == null || u.isEmpty() || u == "1"
    }

    private fun rpc(ctx: Context, fn: String, body: JSONObject, done: (String?, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var res: String? = null
            var msg: String
            try {
                val tok = token(c)
                if (tok == null) msg = "Nu ești conectat."
                else {
                    val r = call("POST", "/rest/v1/rpc/$fn", body.toString(), tok)
                    if (r.code in 200..299) { res = r.body; msg = "ok" } else msg = "Eroare ${r.code}."
                }
            } catch (e: Exception) { msg = "Fără internet." }
            main.post { done(res, msg) }
        }
    }

    /** Schimbă numele afișat al contului (doar al tău; serverul verifică contul). */
    fun rename(ctx: Context, name: String, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        val n = name.trim().take(40)
        if (n.length < 2) { done(false, "Numele e prea scurt."); return }
        rpc(ctx, "rename_me", JSONObject().put("p_name", n)) { r, m ->
            if (r != null) { Store.setRaw(c, "cloud_name", n); done(true, "Nume schimbat.") } else done(false, m)
        }
    }

    /** Doar admin (serverul refuză pe oricine altcineva): șterge TOATE evenimentele de zone din comunitate. */
    fun adminClearEvents(ctx: Context, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val tok = token(c)
                if (tok == null) msg = "Nu ești conectat."
                else {
                    val r = call("DELETE", "/rest/v1/events?ts=gte.0", null, tok)
                    if (r.code in 200..299) { ok = true; msg = "Datele de zone din comunitate au fost șterse." } else msg = "Nu ai voie sau eroare ${r.code}."
                }
            } catch (e: Exception) { msg = "Fără internet." }
            main.post { done(ok, msg) }
        }
    }

    fun spins(c: Context): Int = Store.raw(c, "cloud_spins")?.toIntOrNull() ?: 0

    /** Roata: câte învârtiri ai, codul tău de prieten și prietenii aduși (totul vine de la server). */
    fun wheel(ctx: Context, done: (JSONObject?, String) -> Unit) {
        val c = ctx.applicationContext
        rpc(ctx, "my_wheel", JSONObject()) { r, m ->
            if (r == null) done(null, m)
            else try {
                val o = JSONObject(r)
                Store.setRaw(c, "cloud_spins", o.optInt("spins", 0).toString())
                done(o, "ok")
            } catch (e: Exception) { done(null, "Răspuns neașteptat.") }
        }
    }

    /** O învârtire: premiul îl alege serverul (nu telefonul). */
    fun spin(ctx: Context, done: (JSONObject?, String) -> Unit) {
        val c = ctx.applicationContext
        rpc(ctx, "spin", JSONObject()) { r, m ->
            if (r == null) done(null, m)
            else try {
                val o = JSONObject(r)
                Store.setRaw(c, "cloud_spins", o.optInt("spins_left", 0).toString())
                done(o, "ok")
            } catch (e: Exception) { done(null, "Răspuns neașteptat.") }
        }
    }

    /** Utilizatorul spune „Am plătit”: cererea ajunge la admin. */
    fun requestPayment(ctx: Context, done: (Boolean, String) -> Unit) =
        rpc(ctx, "request_payment", JSONObject()) { r, m -> done(r != null, m) }

    /** Doar admin: cererile de plată în așteptare. */
    fun listPayments(ctx: Context, done: (List<PayReq>?, String) -> Unit) =
        rpc(ctx, "list_payment_requests", JSONObject()) { r, m ->
            if (r == null) done(null, m)
            else {
                val out = ArrayList<PayReq>()
                try {
                    val a = JSONArray(r)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        out.add(PayReq(o.getString("user_id"), o.optString("name", ""), o.optString("email", ""), o.optString("created_at", "")))
                    }
                } catch (e: Exception) { }
                done(out, "ok")
            }
        }

    /** Doar admin: aprobă plata și prelungește accesul. */
    fun approvePayment(ctx: Context, userId: String, days: Int, done: (Boolean, String) -> Unit) =
        rpc(ctx, "approve_payment", JSONObject().put("p_user", userId).put("p_days", days)) { r, m -> done(r != null, m) }

    /** Doar admin: creează un cod de invitație (o singură folosire). */
    fun createInvite(ctx: Context, days: Int, unlimited: Boolean, done: (String?, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var code: String? = null
            var msg: String
            try {
                val tok = token(c)
                if (tok == null) msg = "Nu ești conectat."
                else {
                    val r = call("POST", "/rest/v1/rpc/create_invite", JSONObject().put("p_days", days).put("p_unlimited", unlimited).toString(), tok)
                    if (r.code in 200..299) { code = r.body.trim().trim('"'); msg = "ok" } else msg = "Eroare ${r.code}."
                }
            } catch (e: Exception) { msg = "Fără internet." }
            main.post { done(code, msg) }
        }
    }

    /** Ieșire din cont pe acest telefon (datele din cloud rămân). */
    fun logout(c: Context) {
        for (k in listOf("cloud_access", "cloud_refresh", "cloud_exp", "cloud_email", "cloud_member", "cloud_name",
            "cloud_role", "cloud_synced_ts", "community", "cloud_status", "cloud_unl", "cloud_until", "cloud_spins", "join_error")) Store.setRaw(c, k, "")
    }

    // ---------- intrare / ieșire ----------
    fun join(ctx: Context, name: String, code: String, done: (Boolean, String) -> Unit) {
        val c = ctx.applicationContext
        exec.execute {
            var ok = false
            var msg: String
            try {
                val tok = token(c)
                if (tok == null) {
                    msg = "Nu ești conectat la cont. Intră mai întâi cu email și parolă."
                } else {
                    val body = JSONObject().put("p_code", code.trim()).put("p_name", name.trim()).toString()
                    val r = call("POST", "/rest/v1/rpc/redeem_invite", body, tok)
                    if (r.code in 200..299) {
                        Store.setRaw(c, "cloud_member", "1")
                        Store.setRaw(c, "cloud_name", name.trim())
                        Store.setRaw(c, "cloud_role", r.body.trim().trim('"'))
                        ok = true
                        msg = "Ai intrat în comunitate."
                        refreshAccess(c)
                    } else {
                        msg = if (r.body.contains("invalid invite")) "Cod invalid sau deja folosit." else "Eroare ${r.code}."
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
                    for (k in listOf("cloud_member", "cloud_name", "cloud_role", "cloud_synced_ts", "community", "cloud_status", "cloud_unl", "cloud_until", "join_error")) {
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

    private fun fin(d: Double): Double = if (d.isNaN() || d.isInfinite()) 0.0 else d

    private fun pending(c: Context, since: Long): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val nul = JSONObject.NULL
        for (e in Store.geoEvents(c)) {
            if (e.kind != 0 || e.ts <= since) continue
            out.add(
                JSONObject().put("ts", e.ts).put("kind", 0).put("lat", e.lat).put("lon", e.lon)
                    .put("net_per_km", if (e.netPerKm.isNaN() || e.netPerKm.isInfinite()) nul else e.netPerKm)
                    .put("price", nul).put("pickup_km", nul).put("trip_km", nul).put("net", nul)
            )
        }
        for (r in Store.history(c)) {
            if (r.ts <= since || r.lat.isNaN() || r.lon.isNaN()) continue
            out.add(
                JSONObject().put("ts", r.ts).put("kind", 1).put("lat", r.lat).put("lon", r.lon)
                    .put("net_per_km", fin(r.netPerKm)).put("price", fin(r.price))
                    .put("pickup_km", fin(r.pickupKm)).put("trip_km", fin(r.tripKm)).put("net", fin(r.net))
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
