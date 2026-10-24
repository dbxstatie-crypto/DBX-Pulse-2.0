package ro.drivescore

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Citește ecranul Bolt Driver, calculează câștigul net / km și arată cardul.
 *  - cardul NU primește atingeri: butoanele Bolt (Respinge / Acceptă) rămân apăsabile
 *  - cursele card (acceptate automat) se salvează singure și cardul rămâne vizibil câteva secunde
 *  - cât timp tura e pornită, se numără kilometrii (GPS) pentru „km în gol”
 */
class OfferService : AccessibilityService() {

    companion object {
        private val PACKAGES = setOf("ee.mtakso.driver")
        private const val MISS_GRACE_MS = 1500L
        private const val ACTIVE_RIDE_WINDOW_MS = 8000L
        private const val OFFER_DEDUP_MS = 30000L
        private const val RIDE_DEDUP_MS = 10 * 60 * 1000L   // aceeași cursă nu se salvează de două ori
        private const val AUTO_HOLD_MS = 7000L              // cât rămâne cardul la cursele acceptate automat
        private const val AUTO_LOG_DELAY_MS = 2500L         // timp să poți respinge înainte de salvare
        private const val SHIFT_IDLE_MS = 60 * 60 * 1000L   // tura se oprește după 1h fără activitate în Bolt
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

    private var missScheduled = false
    private var scanQueued = false
    private var loggedKey: String? = null
    private var loggedAt = 0L
    private var lastDump = ""
    private var lastOfferKey: String? = null
    private var lastOfferAt = 0L

    // cursă card acceptată automat
    private var autoOffer: Offer? = null
    private var autoResult: Result? = null
    private var autoKey: String? = null
    private var autoKeyAt = 0L
    private var holdUntil = 0L

    // cursa salvată care așteaptă locația de la pasager
    private var awaitTs = 0L
    private var awaitUntil = 0L

    // oferta respinsă de tine
    private var declinedKey: String? = null
    private var declinedAt = 0L

    // km în tură
    private var tracking = false
    private var lm: LocationManager? = null
    private var lastLoc: Location? = null

    private val hideRunnable = Runnable {
        missScheduled = false
        offerGone()
    }

    private val autoLogRunnable = Runnable {
        val o = autoOffer
        val r = autoResult
        autoOffer = null
        autoResult = null
        if (o != null) logRide(o, r)
    }

    private val ticker = object : Runnable {
        override fun run() {
            scan()
            val waiting = pendingOffer != null && System.currentTimeMillis() - pendingAt < ACTIVE_RIDE_WINDOW_MS
            if (shown || waiting) {
                handler.postDelayed(this, 1000)
            } else {
                pendingOffer = null
                pendingResult = null
            }
        }
    }

    private val trackCheck = object : Runnable {
        override fun run() {
            Odometer.checkIdle(this@OfferService, SHIFT_IDLE_MS)
            syncTracking()
            handler.postDelayed(this, 120_000L)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLoc(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.removeCallbacks(trackCheck)
        handler.post(trackCheck)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg != null && PACKAGES.contains(pkg)) Odometer.touch(this)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg != null && PACKAGES.contains(pkg)) {
            val t = ArrayList<String>()
            event.text?.forEach { t.add(it.toString()) }
            event.contentDescription?.let { t.add(it.toString()) }
            val src = event.source
            if (src != null) walk(src, t, 38) // doar butonul apăsat și 2 nivele sub el
            if (OfferParser.hasAccept(t)) acceptNow()
            else if (OfferParser.hasReject(t)) declineNow()
            else if (awaitTs != 0L && OfferParser.hasArrival(t)) confirmPickup()
        }
        queueScan()
    }

    override fun onInterrupt() {
        hideCard()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopTracking()
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

    // ---------- km în tură (GPS) ----------
    private fun syncTracking() {
        val want = Odometer.on(this) && Geo.hasPermission(this)
        if (want && !tracking) startTracking() else if (!want && tracking) stopTracking()
    }

    @Suppress("MissingPermission")
    private fun startTracking() {
        try {
            lm = getSystemService(LOCATION_SERVICE) as? LocationManager
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 5f, locListener, Looper.getMainLooper())
            lastLoc = null
            tracking = lm != null
        } catch (e: Exception) {
            tracking = false
        }
    }

    private fun stopTracking() {
        try {
            lm?.removeUpdates(locListener)
        } catch (e: Exception) {
            // deja oprit
        }
        tracking = false
        lastLoc = null
    }

    private fun onLoc(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 50f) return
        val p = lastLoc
        lastLoc = l
        if (p == null) return
        val dt = (l.time - p.time) / 1000.0
        val d = p.distanceTo(l).toDouble()
        if (dt <= 0.0 || d < 4.0 || d > 3000.0) return
        if (d / dt > 60.0) return   // salt GPS (peste ~215 km/h)
        Odometer.add(this, d / 1000.0)
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

    private fun collectTexts(): List<String> {
        val out = ArrayList<String>()
        var found = false
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) {
                    val r = w.root ?: continue
                    if (PACKAGES.contains(r.packageName?.toString())) {
                        walk(r, out, 0)
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            // ignorăm; încercăm fereastra activă
        }
        if (!found) {
            val r = rootInActiveWindow
            if (r != null && PACKAGES.contains(r.packageName?.toString())) walk(r, out, 0)
        }
        return out
    }

    private fun scan() {
        val texts = collectTexts()
        if (texts.isEmpty()) {
            onMiss(texts)
            return
        }
        val o = OfferParser.parse(texts)
        if (o != null) {
            handler.removeCallbacks(hideRunnable)
            missScheduled = false
            showOrUpdate(o)
            saveDump(texts)
        } else {
            // păstrăm și ecranele cu prețuri pe care nu le-am recunoscut, pentru diagnostic
            if (texts.any { it.contains("lei", true) }) saveDump(texts)
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
        if (po != null && System.currentTimeMillis() - pendingAt < ACTIVE_RIDE_WINDOW_MS &&
            OfferParser.hasActiveRide(texts)
        ) {
            logRide(po, pendingResult)
            pendingOffer = null
            pendingResult = null
            return
        }
        if (shown && !missScheduled) {
            missScheduled = true
            // la cursele acceptate automat cardul rămâne până apuci să vezi prețul
            val wait = maxOf(MISS_GRACE_MS, holdUntil - System.currentTimeMillis())
            handler.postDelayed(hideRunnable, wait)
        }
    }

    // ---------- card ----------
    private fun showOrUpdate(o: Offer) {
        val nowMs = System.currentTimeMillis()
        val okey = o.key()
        // oferta pe care ai respins-o nu mai reapare cât se închide ecranul
        if (okey == declinedKey && nowMs - declinedAt < 20000L) return

        val r = Scorer.score(o, Store.config(this))
        offer = o
        result = r

        // o ofertă văzută = ești online: pornesc tura singură
        if (!Odometer.on(this)) {
            Odometer.setOn(this, true)
            syncTracking()
        }

        // notăm de unde vine oferta (o singură dată pe ofertă) — doar local
        if (okey != lastOfferKey || nowMs - lastOfferAt > OFFER_DEDUP_MS) {
            Geo.last(this)?.let { Store.addOffer(this, it.first, it.second, r.netPerKm) }
            lastOfferKey = okey
            lastOfferAt = nowMs
        }

        // cursă card: Bolt o acceptă automat, deci o salvăm și ținem cardul vizibil
        if (o.pay == Pay.CARD && Store.autoCard(this) && (okey != autoKey || nowMs - autoKeyAt > OFFER_DEDUP_MS)) {
            autoKey = okey
            autoKeyAt = nowMs
            autoOffer = o
            autoResult = r
            holdUntil = nowMs + AUTO_HOLD_MS
            handler.removeCallbacks(autoLogRunnable)
            handler.postDelayed(autoLogRunnable, AUTO_LOG_DELAY_MS)
        }

        var c = card
        if (c == null) {
            c = OverlayCard(this)
            card = c
        }
        c.bind(o, r)
        if (!shown) {
            val lp = WindowManager.LayoutParams(
                resources.displayMetrics.widthPixels - dp(24),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // NOT_TOUCHABLE: atingerile trec prin card către Bolt, ca să poți apăsa Respinge / Acceptă
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            if (Store.cardOnBottom(this)) {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = dp(24)
            } else {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = dp(44)
            }
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
        hideCard()
        if (offer != null) {
            pendingOffer = offer
            pendingResult = result
            pendingAt = System.currentTimeMillis()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
        offer = null
        result = null
    }

    private fun acceptNow() {
        val o = offer ?: pendingOffer
        val r = result ?: pendingResult
        if (o != null) logRide(o, r)
        handler.removeCallbacks(hideRunnable)
        missScheduled = false
        hideCard()
        offer = null
        result = null
        pendingOffer = null
        pendingResult = null
    }

    /** Ai apăsat „Respinge": cardul dispare și cursa NU se salvează. */
    private fun declineNow() {
        handler.removeCallbacks(autoLogRunnable)
        handler.removeCallbacks(hideRunnable)
        autoOffer = null
        autoResult = null
        missScheduled = false
        holdUntil = 0L
        declinedKey = (offer ?: pendingOffer)?.key() ?: lastOfferKey
        declinedAt = System.currentTimeMillis()
        hideCard()
        offer = null
        result = null
        pendingOffer = null
        pendingResult = null
    }

    /** Ai apăsat „Am ajuns” / „Începe cursa”: ești la pasager, deci aici e locul de preluare. */
    private fun confirmPickup() {
        val ts = awaitTs
        awaitTs = 0L
        if (ts == 0L || System.currentTimeMillis() > awaitUntil) return
        val loc = Geo.last(this) ?: return
        Store.setRidePickup(this, ts, loc.first, loc.second)
        Cloud.syncAsync(this, true)
    }

    private fun logRide(o: Offer, r0: Result?) {
        val now = System.currentTimeMillis()
        val key = o.key()
        if (key == declinedKey && now - declinedAt < 20000L) return
        if (key == loggedKey && now - loggedAt < RIDE_DEDUP_MS) return
        val r = r0 ?: Scorer.score(o, Store.config(this))
        val loc = Geo.last(this)
        val ts = Store.addRide(this, o, r, loc?.first, loc?.second)
        if (loc != null && o.pickupKnown && o.pickupKm < 0.3) {
            // pasagerul e lângă tine: locația de acum e deja cea de preluare
            Store.setRidePickup(this, ts, loc.first, loc.second)
        } else {
            awaitTs = ts
            awaitUntil = now + Store.PICKUP_WAIT_MS
        }
        Cloud.syncAsync(this)
        loggedKey = key
        loggedAt = now
        Toast.makeText(this, "Cursă salvată în istoric", Toast.LENGTH_SHORT).show()
    }
}
