package ro.drivescore

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
        private const val ACTIVE_RIDE_WINDOW_MS = 8000L
        private const val DEDUP_MS = 30000L
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
                pendingOffer = null
                pendingResult = null
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg != null && PACKAGES.contains(pkg)) {
            val t = ArrayList<String>()
            event.text?.forEach { t.add(it.toString()) }
            event.contentDescription?.let { t.add(it.toString()) }
            val src = event.source
            if (src != null) walk(src, t, 38) // doar butonul apăsat și 2 nivele sub el
            if (OfferParser.hasAccept(t)) acceptNow()
        }
        queueScan()
    }

    override fun onInterrupt() {
        hideCard()
    }

    override fun onDestroy() {
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
        if (!shown) {
            val lp = WindowManager.LayoutParams(
                resources.displayMetrics.widthPixels - dp(24),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private fun logRide(o: Offer, r0: Result?) {
        val now = System.currentTimeMillis()
        val key = o.key()
        if (key == loggedKey && now - loggedAt < DEDUP_MS) return
        val r = r0 ?: Scorer.score(o, Store.config(this))
        val loc = Geo.last(this)
        Store.addRide(this, o, r, loc?.first, loc?.second)
        Cloud.syncAsync(this)
        loggedKey = key
        loggedAt = now
        Toast.makeText(this, "Cursă salvată în istoric", Toast.LENGTH_SHORT).show()
    }
}
