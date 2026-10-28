package ro.drivescore

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Citește ecranul Bolt Driver, calculează câștigul net / km și arată cardul.
 * Cardul dispare când oferta dispare, când ieși din Bolt sau când accepți cursa
 * (cursa acceptată intră în istoric).
 */
class OfferService : AccessibilityService() {

    companion object {
        private val PACKAGES = setOf("ee.mtakso.driver")
        private const val MISS_GRACE_MS = 1500L
        private const val ACTIVE_RIDE_WINDOW_MS = 45000L
        private const val DEDUP_MS = 30000L
        private const val STABLE_MS = 300L       // aceeași ofertă citită identic de 2 ori înainte să apară cardul
        private const val AUTO_MAX_MS = 9000L   // o ofertă acceptată automat dispare repede
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var card: OverlayCard? = null
    private var shown = false

    private var offer: Offer? = null
    private var result: Result? = null

    // oferta care tocmai a dispărut și poate fi o cursă acceptată
    private var pendingOffer: Offer? = null
    private var pendingResult: Result? = null
    private var pendingAt = 0L

    private var shownAt = 0L
    private class Logged(val ts: Long, val km: Double, val fp: Set<String>)
    private val recent = ArrayList<Logged>()      // cursele salvate în această sesiune (ca să știm pe care o scoatem la anulare)
    private var offerFp: Set<String> = emptySet() // „amprenta” ofertei afișate (adrese etc.), fără ce era deja pe ecran
    private var pendingFp: Set<String> = emptySet()
    private var baseline: Set<String> = emptySet() // textele ultimului ecran fără ofertă
    private var lastActiveSeenAt = 0L
    private var offerMidRide = false
    private var offerAuto = false
    private var pendingMid = false
    private var lastCancelAt = 0L
    private var cancelSeenAt = 0L
    private var cancelClickAt = 0L
    private var rejectedAt = 0L
    private var missScheduled = false
    private var scanQueued = false
    private var loggedKey: String? = null
    private var loggedAt = 0L
    private var lastDump = ""
    private var lastOfferKey: String? = null
    private var lastOfferAt = 0L
    private var stableKey: String? = null
    private var stableAt = 0L
    private var lastIncompleteLogAt = 0L

    private val hideRunnable = Runnable {
        missScheduled = false
        offerGone()
    }

    private val ticker = object : Runnable {
        override fun run() {
            scan()
            val waiting = pendingOffer != null && System.currentTimeMillis() - pendingAt < ACTIVE_RIDE_WINDOW_MS
            if (shown || waiting) {
                handler.postDelayed(this, 1000)
            } else {
                if (pendingOffer != null) Store.logEv(this@OfferService, "Oferta NU a devenit cursă (nu a apărut ecranul cursei active în 45 s)")
                pendingOffer = null
                pendingResult = null
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var boltSeenAt = 0L
    private var lastFix: Location? = null
    private val trackListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val prev = lastFix
            lastFix = l
            // numără km doar cât timp Bolt Driver e deschis (văzut în ultimele 3 minute)
            if (prev == null || !Store.shiftOn(this@OfferService) || System.currentTimeMillis() - boltSeenAt > 300000L || l.accuracy > 60f) return
            val dt = (l.time - prev.time) / 1000.0
            if (dt <= 0.0 || dt > 120.0) return
            val d = Geo.distKm(prev.latitude, prev.longitude, l.latitude, l.longitude)
            // timpul petrecut în celulă, cât nu ești într-o cursă: expunerea la oferte
            if (Store.rideRemaining(this@OfferService) <= 0.0) Store.addDwell(this@OfferService, l.latitude, l.longitude, minOf(dt, 30.0))
            if (d < 0.005 || d / (dt / 3600.0) > 200.0) return
            Store.addDriveKm(this@OfferService, d)
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private var tracking = false

    /** Pornește GPS-ul pentru km în gol. Se reîncearcă și mai târziu, dacă permisiunea de locație a fost dată după activarea serviciului. */
    @Suppress("MissingPermission")
    private fun ensureTracking() {
        if (tracking || !Geo.hasPermission(this)) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, trackListener, Looper.getMainLooper())
            tracking = true
        } catch (e: Exception) { }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureTracking()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg != null && PACKAGES.contains(pkg)) { boltSeenAt = System.currentTimeMillis(); ensureTracking() }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg != null && PACKAGES.contains(pkg)) {
            val t = ArrayList<String>()
            event.text?.forEach { t.add(it.toString()) }
            event.contentDescription?.let { t.add(it.toString()) }
            val src = event.source
            if (src != null) walk(src, t, 38) // doar butonul apăsat și 2 nivele sub el
            // orice apăsare în Bolt cât oferta e pe ecran (în afară de „Acceptă”) = ai făcut tu ceva, nu acceptare automată
            if (OfferParser.hasAccept(t)) acceptNow(OfferParser.hasNext(t))
            else if (shown || OfferParser.hasReject(t)) rejectedAt = System.currentTimeMillis()
            if (!shown && OfferParser.hasCancelClick(t)) { cancelClickAt = System.currentTimeMillis(); Store.logEv(this, "Ai apăsat un buton de anulare: " + t.take(3).joinToString(" | ")) }
        }
        queueScan()
    }

    override fun onInterrupt() {
        hideCard()
    }

    override fun onDestroy() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(trackListener) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        hideCard()
        super.onDestroy()
    }

    private fun queueScan() {
        if (scanQueued) return
        scanQueued = true
        handler.postDelayed({
            scanQueued = false
            scan()
        }, 250)
    }

    // ---------- citire ecran ----------
    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 40 || out.size > 600) return
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            out.add(t)
        } else {
            val d = n.contentDescription?.toString()?.trim()
            if (!d.isNullOrEmpty()) out.add(d)
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private var rejectRect: Rect? = null

    /** Poziția butonului „Respinge” din Bolt (ca să lăsăm o fereastră goală în card peste el). */
    private fun findReject(n: AccessibilityNodeInfo?, depth: Int): Rect? {
        if (n == null || depth > 40) return null
        val s = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
        if (s.isNotBlank() && OfferParser.hasReject(listOf(s))) {
            var t: AccessibilityNodeInfo? = n
            var k = 0
            while (t != null && !t.isClickable && k < 3) { t = t.parent; k++ }
            val r = Rect()
            (t ?: n).getBoundsInScreen(r)
            if (!r.isEmpty && r.width() < resources.displayMetrics.widthPixels) return r
        }
        for (i in 0 until n.childCount) findReject(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun collectTexts(): List<String> {
        val wins = ArrayList<List<String>>()
        var found = false
        rejectRect = null
        var offerRect: Rect? = null
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) {
                    val r = w.root ?: continue
                    if (PACKAGES.contains(r.packageName?.toString())) {
                        val one = ArrayList<String>()
                        walk(r, one, 0)
                        wins.add(one)
                        if (offerRect == null && (OfferParser.hasAccept(one) || OfferParser.hasReject(one))) offerRect = findReject(r, 0)
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            // ignorăm; încercăm fereastra activă
        }
        if (!found) {
            val r = rootInActiveWindow
            if (r != null && PACKAGES.contains(r.packageName?.toString())) {
                val one = ArrayList<String>()
                walk(r, one, 0)
                wins.add(one)
                offerRect = findReject(r, 0)
            }
        }
        rejectRect = offerRect
        // dacă o singură fereastră are butoanele Acceptă/Respinge, citim DOAR din ea: cifrele din celelalte ferestre (hartă, cursă activă) nu se amestecă
        val offerOnly = wins.filter { OfferParser.hasAccept(it) || OfferParser.hasReject(it) }
        if (offerOnly.size == 1) return offerOnly[0]
        val out = ArrayList<String>()
        for (w in wins) out.addAll(w)
        return out
    }

    private val numLike = Regex("""\d+([.,]\d+)?\s*(lei|ron|km|min|m)\b|^[\d\s.,:/%+-]+$""", RegexOption.IGNORE_CASE)

    /** Linii de text care identifică oferta (adrese etc.), fără butoane, prețuri și ce era deja pe ecran. */
    private fun fingerprint(texts: List<String>): Set<String> =
        texts.map { it.trim() }
            .filter { it.length in 10..90 && it.any { c -> c.isLetter() } && !numLike.containsMatchIn(it) }
            .filter { !OfferParser.hasAccept(listOf(it)) && !OfferParser.hasReject(listOf(it)) }
            .filter { it !in baseline }
            .take(6).toSet()

    /** Scoate din istoric o cursă salvată: cea a cărei amprentă apare pe ecran, altfel cea mai recentă. */
    private fun removeLogged(texts: List<String>, msg: String, by: Int) {
        val now = System.currentTimeMillis()
        recent.removeAll { now - it.ts > 90 * 60000L }
        // fără amprentă sigură scoatem doar o cursă salvată în ultimele 30 de minute, niciodată una veche
        val target = recent.lastOrNull { l -> l.fp.isNotEmpty() && texts.any { it in l.fp } }
            ?: recent.lastOrNull { now - it.ts < 30 * 60000L }
        if (target == null) { Store.logEv(this, "ANULARE: nicio cursă recentă de scos (" + msg + ")"); return }
        recent.remove(target)
        Store.logEv(this, "ANULARE: cursă marcată anulată (" + msg + "), " + Fmt.d1(target.km) + " km")
        Store.cancelRide(this, target.ts, by)
        Store.startRide(this, -target.km)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** „Pasagerul a anulat”: cursa nu se numără. */
    private fun handleCancel(texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastCancelAt < 90000L) return
        cancelClickAt = 0L
        lastCancelAt = now
        pendingOffer = null
        pendingResult = null
        removeLogged(texts, "Pasagerul a anulat — cursa e marcată „Anulată”", 1)
    }

    private fun scan() {
        val texts = collectTexts()
        if (texts.isNotEmpty() && OfferParser.hasActiveRide(texts)) lastActiveSeenAt = System.currentTimeMillis()
        if (texts.isNotEmpty() && OfferParser.hasCancelled(texts)) {
            // același ecran „Pasagerul a anulat” se tratează o singură dată, oricât timp rămâne pe ecran
            val nowC = System.currentTimeMillis()
            if (nowC - cancelSeenAt > 20000L) {
                Store.logEv(this, "TEXT DE ANULARE pe ecran: " + texts.filter { OfferParser.hasCancelled(listOf(it)) }.take(3).joinToString(" | "))
                handleCancel(texts)
            }
            cancelSeenAt = nowC
        }
        // ai apăsat tu „Anulează cursa” și ecranul cursei active a dispărut => cursa anulată de tine
        if (cancelClickAt > 0L) {
            val t = System.currentTimeMillis()
            if (t - cancelClickAt > 30000L) cancelClickAt = 0L
            else if (texts.isNotEmpty() && !OfferParser.hasActiveRide(texts) && OfferParser.parse(texts) == null) {
                cancelClickAt = 0L
                removeLogged(texts, "Cursa anulată de tine — marcată „Anulată”", 2)
            }
        }
        if (texts.isEmpty()) {
            onMiss(texts)
            return
        }
        val o = OfferParser.parse(texts)
        if (o != null && !shown) {
            val nowS = System.currentTimeMillis()
            val k = o.key() + "|" + o.cash
            if (k != stableKey) {
                stableKey = k
                stableAt = nowS
                handler.postDelayed({ scan() }, STABLE_MS + 60)
                return
            }
            if (nowS - stableAt < STABLE_MS) return
        }
        if (o == null) {
            stableKey = null
            val nowI = System.currentTimeMillis()
            if (!shown && OfferParser.hasAccept(texts) && nowI - lastIncompleteLogAt > 20000L) {
                lastIncompleteLogAt = nowI
                Store.logEv(this, "Citire INCOMPLETĂ ignorată: " + texts.joinToString(" | ").take(160))
            }
        }
        if (o != null) {
            offerFp = fingerprint(texts)
            if (OfferParser.hasAuto(texts)) offerAuto = true
            handler.removeCallbacks(hideRunnable)
            missScheduled = false
            showOrUpdate(o)
            saveDump(texts)
        } else {
            if (texts.isNotEmpty() && !shown) baseline = texts.map { it.trim() }.toSet()
            onMiss(texts)
        }
    }

    private fun saveDump(texts: List<String>) {
        val s = texts.joinToString(" | ").take(1800)
        if (s != lastDump) {
            lastDump = s
            Store.setDump(this, s)
        }
    }

    private fun onMiss(texts: List<String>) {
        // plasă de siguranță: dacă oferta a dispărut și apare un ecran de cursă activă => acceptată
        val po = pendingOffer
        // „cursă în cursă”: dacă erai deja într-o cursă, ecranul activ nu dovedește nimic nou;
        // atunci confirmăm doar dacă apare textul ofertei (adresele ei) pe ecran
        val seenOffer = pendingFp.isNotEmpty() && texts.any { it.trim() in pendingFp }
        if (po != null && System.currentTimeMillis() - pendingAt < ACTIVE_RIDE_WINDOW_MS &&
            (seenOffer || OfferParser.hasAuto(texts) || (!pendingMid && OfferParser.hasActiveRide(texts)))
        ) {
            logRide(po, pendingResult)
            pendingOffer = null
            pendingResult = null
            return
        }
        if (po != null && System.currentTimeMillis() - pendingAt < ACTIVE_RIDE_WINDOW_MS && texts.isNotEmpty()) {
            // diagnostic: ce text avea ecranul după ofertă (ca să putem recunoaște „cursă acceptată”)
            val d = "Ecran după ofertă (nerecunoscut ca cursă):\n" + texts.take(70).joinToString(" | ")
            if (d != lastDump) { lastDump = d; Store.setDump(this, d); Store.logEv(this, d.take(480)) }
        }
        if (shown && !missScheduled) {
            missScheduled = true
            handler.postDelayed(hideRunnable, MISS_GRACE_MS)
        }
    }

    // ---------- card ----------
    private fun showOrUpdate(o: Offer) {
        val r = Scorer.score(o, Store.config(this))
        offer = o
        result = r
        // notăm de unde vine oferta (o singură dată pe ofertă) — pentru harta de zone
        val okey = o.key()
        val nowMs = System.currentTimeMillis()
        if (okey != lastOfferKey || nowMs - lastOfferAt > DEDUP_MS) {
            Geo.last(this)?.let { Store.addOffer(this, it.first, it.second, r.netPerKm) }
            lastOfferKey = okey
            lastOfferAt = nowMs
        }
        var c = card
        if (c == null) {
            c = OverlayCard(this)
            c.view.setOnLongClickListener {
                // plasă de siguranță manuală: apăsare lungă = salvează în istoric
                acceptNow()
                true
            }
            card = c
        }
        c.bind(o, r)
        c.setCutout(rejectRect?.let { Rect(it).apply { inset(-dp(6), -dp(6)) } })
        if (!shown) {
            shownAt = System.currentTimeMillis()
            offerMidRide = shownAt - lastActiveSeenAt < 20000L
            Store.logEv(this, "OFERTĂ pe ecran: " + Fmt.d2(o.price) + " lei, " + Fmt.d1(o.pickupKm) + "+" + Fmt.d1(o.tripKm) + " km, " + Fmt.d2(r.netPerKm) + " lei/km" +
                (if (o.cash) " [cash]" else "") + (if (offerAuto) " [acceptare automată]" else "") + (if (offerMidRide) " [în cursă]" else ""))
            val lp = WindowManager.LayoutParams(
                resources.displayMetrics.widthPixels - dp(24),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // atingerile trec prin card în Bolt (poți apăsa „Refuză” sub el)
                    (if (Store.raw(this, "pass_touch") != "0") WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(44)
            try {
                wm?.addView(c.view, lp)
                shown = true
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 1000)
            } catch (e: Exception) {
                shown = false
            }
        }
    }

    private fun hideCard() {
        val c = card
        if (shown && c != null) {
            try {
                wm?.removeView(c.view)
            } catch (e: Exception) {
                // deja scos
            }
        }
        shown = false
    }

    /** Oferta a dispărut fără click pe „Acceptă": poate fi respinsă, expirată sau acceptată. */
    private fun offerGone() {
        val now = System.currentTimeMillis()
        hideCard()
        val auto = offerAuto
        offerAuto = false
        val o0 = offer
        if (auto && o0 != null && now - rejectedAt > 8000L) {
            // Bolt a scris „Acceptare automată” pe ofertă și tu n-ai respins-o: cursa e a ta
            logRide(o0, result, offerFp)
            handler.removeCallbacks(ticker)
            offer = null
            result = null
            return
        }
        if (offer != null && now - rejectedAt > 8000L) {
            pendingOffer = offer
            pendingResult = result
            pendingFp = offerFp
            pendingMid = offerMidRide
            pendingAt = System.currentTimeMillis()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
        offer = null
        result = null
    }

    private fun acceptNow(explicit: Boolean = false) {
        val o = offer ?: pendingOffer
        val r = result ?: pendingResult
        val auto = offerAuto
        offerAuto = false
        if (o != null && (explicit || auto)) {
            // „Acceptă următoarea cursă” (sau acceptare automată): e atribuită sigur, o salvăm imediat
            logRide(o, r, offerFp)
            handler.removeCallbacks(hideRunnable)
            missScheduled = false
            hideCard()
            offer = null
            result = null
            pendingOffer = null
            pendingResult = null
            return
        }
        // nu salvăm încă: așteptăm ecranul cursei active (adică Bolt ți-a atribuit cursa)
        if (o != null) {
            pendingOffer = o
            pendingResult = r
            pendingFp = offerFp
            pendingMid = offerMidRide
            pendingAt = System.currentTimeMillis()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
        handler.removeCallbacks(hideRunnable)
        missScheduled = false
        hideCard()
        offer = null
        result = null
    }

    private fun logRide(o: Offer, r0: Result?, fp: Set<String> = pendingFp) {
        val now = System.currentTimeMillis()
        val key = o.key()
        if (key == loggedKey && now - loggedAt < DEDUP_MS) return
        val r = r0 ?: Scorer.score(o, Store.config(this))
        val loc = Geo.last(this)
        val ts = Store.addRide(this, o, r, loc?.first, loc?.second)
        Store.logEv(this, "CURSĂ SALVATĂ: " + Fmt.d2(o.price) + " lei, " + Fmt.d1(o.totalKm) + " km" + (if (o.cash) " [cash]" else " [card]"))
        Store.startRide(this, o.totalKm)
        recent.add(Logged(ts, o.totalKm, fp))
        Cloud.syncAsync(this)
        loggedKey = key
        loggedAt = now
        Toast.makeText(this, "Cursă salvată în istoric", Toast.LENGTH_SHORT).show()
    }
}
