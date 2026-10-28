package ro.drivescore

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import android.widget.ImageView
import java.time.LocalDate
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabZones: TextView
    private lateinit var tabsBar: LinearLayout
    private lateinit var tabDocs: TextView
    private var askedNotif = false
    private var splashDone = false
    private var showAllRides = false
    private var showGuide = false
    private lateinit var tabSettings: TextView
    private var zoneMap: MapView? = null
    private var tab = 0
    private var simRefresh: (() -> Unit)? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- helpers UI ----------
    private fun tv(text: String, size: Float, color: Int = Ui.TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat())
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("ro.drivescore") && s.contains("OfferService")
    }

    // ---------- ciclu de viață ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.apply(this)
        // harta OpenStreetMap: cache în memoria aplicației (nu cere permisiuni de stocare)
        val osm = Configuration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = packageName
        osm.osmdroidBasePath = File(cacheDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        // antet
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(20), dp(18), dp(20), dp(8))
        val logoRow = LinearLayout(this)
        logoRow.gravity = Gravity.CENTER_VERTICAL
        val l1 = tv("DBX ", 28f, Ui.TEXT, true); l1.setTypeface(l1.typeface, Typeface.BOLD_ITALIC)
        val l2 = tv("Pulse", 28f, Ui.BRAND, true); l2.setTypeface(l2.typeface, Typeface.BOLD_ITALIC)
        val l3 = ImageView(this); l3.setImageResource(R.drawable.ic_pulse)
        logoRow.addView(l1); logoRow.addView(l2)
        logoRow.addView(l3, LinearLayout.LayoutParams(dp(30), dp(26)))
        header.addView(logoRow)
        header.addView(tv("Statistici inteligente pentru șoferi ridesharing", 13f, Ui.TEXT))
        statusView = tv("", 13f, Ui.MUTED)
        statusView.setPadding(dp(12), dp(6), dp(12), dp(6))
        statusView.background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
        (statusView.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
        statusView.setOnClickListener {
            if (!serviceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        header.addView(statusView)
        root.addView(header)

        // taburi
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(8), dp(6), dp(8), dp(10))
        tabHistory = tabView("Acasă", R.drawable.ic_nav_home) { tab = 0; render() }
        tabZones = tabView("Zone", R.drawable.ic_nav_zone) { tab = 1; render() }
        tabDocs = tabView("Acte", R.drawable.ic_nav_docs) { tab = 3; render() }
        tabSettings = tabView("Setări", R.drawable.ic_nav_gear) { tab = 2; render() }
        val bolt = ImageView(this)
        bolt.setImageResource(R.drawable.ic_nav_bolt)
        bolt.setPadding(dp(14), dp(14), dp(14), dp(14))
        bolt.background = Ui.rounded(Ui.BRAND, dp(26).toFloat())
        bolt.setOnClickListener {
            if (!serviceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else Toast.makeText(this, "Activ – cardul apare automat în aplicația de ridesharing", Toast.LENGTH_SHORT).show()
        }
        val boltWrap = LinearLayout(this)
        boltWrap.gravity = Gravity.CENTER
        boltWrap.addView(bolt, LinearLayout.LayoutParams(dp(52), dp(52)))
        val w1 = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        tabs.addView(tabHistory, w1())
        tabs.addView(tabZones, w1())
        tabs.addView(boltWrap, w1())
        tabs.addView(tabDocs, w1())
        tabs.addView(tabSettings, w1())
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setBackgroundColor(Ui.SURFACE)
        tabsBar = tabs

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(4), dp(16), dp(24))
        val scroll = ScrollView(this)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tabs)

        setContentView(root)
        tab = intent.getIntExtra("tab", tab)
        Docs.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tab = intent.getIntExtra("tab", tab)
        render()
    }

    override fun onResume() {
        super.onResume()
        Cloud.refreshAccessAsync(this, false) { render() }
        Updater.check(this, false) { if (!isFinishing) render() }
        Docs.check(this)
        updateStatus()
        render()
        Cloud.syncAsync(this) { if (!isFinishing && tab == 1) render() }
    }

    override fun onPause() {
        super.onPause()
        zoneMap?.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun tabView(label: String, icon: Int, onClick: () -> Unit): TextView {
        val t = tv(label, 11f, Ui.MUTED, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(4), 0, dp(2))
        t.compoundDrawablePadding = dp(2)
        t.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleTab(t: TextView, active: Boolean) {
        val col = if (active) Ui.BRAND else Ui.MUTED
        t.setTextColor(col)
        t.compoundDrawables[1]?.mutate()?.setTint(col)
    }

    private fun updateStatus() {
        if (serviceEnabled()) {
            statusView.text = "●  Activ — cardul apare automat în aplicația de ridesharing"
            statusView.setTextColor(Ui.GREEN)
        } else {
            statusView.text = "●  Dezactivat — apasă aici și activează „DBX Pulse” la Accesibilitate"
            statusView.setTextColor(Ui.AMBER)
        }
    }

    private fun render() {
        simRefresh = null
        if (Updater.mandatory(this)) {
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            val mc = updateCard(true)
            if (mc != null) content.addView(mc)
            return
        }
        if (showGuide) {
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            renderGuide()
            return
        }
        if (Cloud.configured() && !Cloud.loggedIn(this)) {
            // fără cont: doar ecranul de autentificare, restul aplicației apare după login
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            if (!splashDone) renderSplash() else renderLogin()
            return
        }
        if (Cloud.REQUIRE_INVITE && Cloud.configured() && !Cloud.joined(this)) {
            // cont făcut, dar fără cod de invitație acceptat
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            renderJoin()
            return
        }
        if (Cloud.REQUIRE_INVITE && Cloud.configured() && Cloud.joined(this) && !Cloud.hasAccess(this)) {
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            renderExpired()
            return
        }
        maybeWelcome()
        tabsBar.visibility = View.VISIBLE
        styleTab(tabHistory, tab == 0)
        styleTab(tabZones, tab == 1)
        styleTab(tabDocs, tab == 3)
        styleTab(tabSettings, tab == 2)
        zoneMap?.onDetach()
        zoneMap = null
        content.removeAllViews()
        updateCard(false)?.let { content.addView(it) }
        when (tab) {
            0 -> renderHistory()
            1 -> renderZones()
            3 -> renderDocs()
            4 -> renderExpenses()
            5 -> renderContact()
            else -> renderSettings()
        }
    }

    // ---------- ACTUALIZARE ----------
    private fun updateCard(forced: Boolean): LinearLayout? {
        val info = Updater.known(this) ?: return null
        if (info.code <= Updater.currentCode(this) || info.url.isEmpty()) return null
        val c = card()
        c.addView(tv(if (forced) "Actualizare obligatorie" else "Versiune nouă disponibilă", 18f, Ui.TEXT, true))
        c.addView(tv("Ai versiunea " + Updater.currentName(this) + ". Noua versiune: " + info.name + "." +
            (if (info.notes.isNotEmpty()) "\n\n" + info.notes else "") +
            (if (forced) "\n\nTrebuie să actualizezi ca să continui." else ""), 13f, Ui.MUTED))
        val b = button("Actualizează acum") { }
        b.setOnClickListener {
            b.isEnabled = false
            b.text = "Se descarcă… 0%"
            Updater.download(this, info.url, { p -> b.text = if (p >= 0) "Se descarcă… $p%" else "Se descarcă…" }) { ok, msg ->
                b.isEnabled = true
                b.text = "Actualizează acum"
                if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                else if (!Updater.install(this)) Toast.makeText(this, "Permite „Instalare aplicații necunoscute” pentru DBX Pulse, apoi revino și apasă din nou.", Toast.LENGTH_LONG).show()
            }
        }
        c.addView(b)
        return c
    }

    // ---------- ISTORIC ----------
    private fun renderHistory() {
        val all = Store.historyAll(this)           // lista arată și cursele anulate
        val rides = all.filter { it.canceled == 0 } // AZI numără doar cursele valabile
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val today = rides.filter { it.ts >= dayStart }

        content.addView(welcomeBanner())
        accessCard()?.let { content.addView(it) }
        accountCard()?.let { content.addView(it) }
        content.addView(summaryCard("AZI", today))
        content.addView(tipsCard())
        content.addView(emptyKmCard(today, rides))
        content.addView(quickActions())

        if (all.isEmpty()) {
            val c = card()
            c.addView(tv("Nicio cursă salvată încă.", 15f, Ui.TEXT, true))
            val hint = tv(
                "Când apeși „Acceptă” în aplicația de ridesharing, cursa apare aici și cardul dispare. " +
                    "Dacă nu se salvează singură, ține apăsat pe card ca s-o salvezi manual.",
                13f, Ui.MUTED
            )
            hint.setPadding(0, dp(6), 0, 0)
            c.addView(hint)
            content.addView(c)
            return
        }

        val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale.forLanguageTag("ro-RO"))
        val hd = LinearLayout(this)
        hd.gravity = Gravity.CENTER_VERTICAL
        hd.setPadding(0, dp(4), 0, dp(8))
        hd.addView(tv("Ultimele curse", 19f, Ui.TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (all.size > 4) {
            val allBtn = tv(if (showAllRides) "Mai puține" else "Vezi toate →", 14f, Ui.BRAND, true)
            allBtn.setOnClickListener { showAllRides = !showAllRides; render() }
            hd.addView(allBtn)
        }
        content.addView(hd)
        for (r in all.take(if (showAllRides) 200 else 4)) {
            val cx = r.canceled > 0
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // cartonaș: fundal, contur în culoarea verdictului, spațiu între cartonașe
            val edge = ((if (cx) Ui.MUTED else Ui.levelColor(r.level)) and 0x00FFFFFF) or 0x99000000.toInt()
            if (cx) row.alpha = 0.6f
            row.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat(), edge, dp(1))
            row.setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            row.layoutParams = lp

            val dot = View(this)
            dot.background = Ui.rounded(if (cx) Ui.MUTED else Ui.levelColor(r.level), dp(8).toFloat())
            row.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, 0, 0)
            if (cx) col.addView(tv(if (r.canceled == 1) "ANULATĂ DE PASAGER · nu se numără" else "ANULATĂ DE TINE · nu se numără", 12f, Ui.AMBER, true))
            col.addView(tv(Fmt.d2(r.netPerKm) + " lei/km net  ·  " + Fmt.d1(r.totalKm) + " km", 16f, Ui.TEXT, true))
            col.addView(
                tv(
                    fmt.format(Date(r.ts)) + "  ·  net " + Fmt.d2(r.net) + " lei din " + Fmt.d2(r.price) +
                        "  ·  " + Fmt.d1(r.pickupKm) + " + " + Fmt.d1(r.tripKm) + " km",
                    12f, Ui.MUTED
                )
            )
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (r.pay >= 0 && !cx) {
                val cash = r.pay == 1
                val pc = if (cash) Ui.GREEN else Ui.BRAND
                val badge = tv(if (cash) "CASH" else "CARD", 11f, pc, true)
                badge.gravity = Gravity.CENTER
                badge.setPadding(dp(10), dp(5), dp(10), dp(5))
                badge.background = Ui.rounded((pc and 0x00FFFFFF) or 0x22000000, dp(10).toFloat(), pc, dp(1))
                val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                blp.leftMargin = dp(8)
                row.addView(badge, blp)
            }

            row.setOnLongClickListener {
                val first = if (cx) "Refă cursa (o număr din nou)" else "Marchează ca anulată (nu se mai numără)"
                AlertDialog.Builder(this)
                    .setTitle("Cursa din " + fmt.format(Date(r.ts)))
                    .setItems(arrayOf(first, "Șterge definitiv", "Închide")) { _, which ->
                        if (which == 0) { if (cx) Store.restoreRide(this, r.ts) else Store.cancelRide(this, r.ts, 2); render() }
                        else if (which == 1) {
                            AlertDialog.Builder(this)
                                .setTitle("Ștergi definitiv cursa?")
                                .setPositiveButton("Șterge") { _, _ -> Store.deleteRide(this, r.ts); render() }
                                .setNegativeButton("Nu", null)
                                .show()
                        }
                    }
                    .show()
                true
            }
            content.addView(row)
        }

        val clear = tv("Șterge tot istoricul", 14f, Ui.RED, true)
        clear.gravity = Gravity.CENTER
        clear.setPadding(0, dp(16), 0, dp(8))
        clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Ștergi tot istoricul?")
                .setPositiveButton("Șterge") { _, _ ->
                    Store.clearHistory(this)
                    render()
                }
                .setNegativeButton("Anulează", null)
                .show()
        }
        content.addView(clear)
    }

    private fun summaryCard(title: String, rides: List<Ride>): LinearLayout {
        val c = card()
        val dateTxt = SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ro-RO")).format(Date())
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv(title, 17f, Ui.TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv(dateTxt, 12f, Ui.MUTED))
        c.addView(head)
        val net = rides.sumOf { it.net }
        val km = rides.sumOf { it.totalKm }
        val avg = if (km > 0) net / km else 0.0
        fun row(a: LinearLayout, b: LinearLayout) {
            val r = LinearLayout(this)
            r.setPadding(0, dp(8), 0, 0)
            val l1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); l1.rightMargin = dp(4)
            val l2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); l2.leftMargin = dp(4)
            r.addView(a, l1); r.addView(b, l2)
            c.addView(r)
        }
        row(statTile(R.drawable.ic_st_car, "Curse", rides.size.toString()), statTile(R.drawable.ic_st_wallet, "Net", Fmt.d2(net) + " lei"))
        row(statTile(R.drawable.ic_st_road, "KM", Fmt.d1(km)), statTile(R.drawable.ic_st_chart, "Net/KM", Fmt.d2(avg)))
        return c
    }

    private fun stat(label: String, value: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(label, 10f, Ui.MUTED))
        col.addView(tv(value, 17f, Ui.TEXT, true))
        return col
    }

    private fun tipsCard(): LinearLayout {
        val c = card()
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val tIc = ImageView(this); tIc.setImageResource(R.drawable.ic_st_star); tIc.setColorFilter(Ui.BRAND)
        val tLp = LinearLayout.LayoutParams(dp(18), dp(18)); tLp.rightMargin = dp(8)
        head.addView(tIc, tLp)
        head.addView(tv("TIPS AZI", 12f, Ui.MUTED, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (Store.tipsToday(this) > 0) {
            val rs = tv("Resetează azi", 12f, Ui.RED, true)
            rs.setOnClickListener {
                AlertDialog.Builder(this).setMessage("Pui tips-urile de azi la 0?")
                    .setPositiveButton("Da") { _, _ -> Store.resetTipsToday(this); render() }
                    .setNegativeButton("Nu", null).show()
            }
            head.addView(rs)
        }
        c.addView(head)
        c.addView(tv(Fmt.d2(Store.tipsToday(this)) + " lei", 26f, Ui.BRAND, true))
        c.addView(tv("Total tips: " + Fmt.d2(Store.tipsTotal(this)) + " lei · se adună până la 00:00", 12f, Ui.MUTED))
        val f = textField("Cât ai primit (lei)", false)
        f.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        c.addView(f)
        c.addView(button("+ Adaugă tips") {
            val v = f.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (v == null || v <= 0.0) Toast.makeText(this, "Scrie suma primită.", Toast.LENGTH_SHORT).show()
            else { Store.addTips(this, v); render() }
        })
        return c
    }

    private fun emptyKmCard(today: List<Ride>, all: List<Ride>): LinearLayout {
        val c = card()
        c.addView(tv("KM ÎN GOL (fără cursă)", 12f, Ui.MUTED, true))
        val r = LinearLayout(this)
        r.setPadding(0, dp(8), 0, 0)
        r.addView(statTile(R.drawable.ic_st_cal, "Azi", Fmt.d1(Store.emptyToday(this)) + " km"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(r)
        val on = Store.shiftOn(this)
        c.addView(tv(if (on) "Tură pornită — se numără km în gol cât aplicația de ridesharing e deschisă." else "Tură oprită — km rămân salvați, se continuă de unde au rămas.", 12f, if (on) Ui.GREEN else Ui.AMBER))
        c.addView(button(if (on) "Oprește tura" else "Pornește tura") { Store.setShift(this, !on); render() })
        val trk = Store.trackedToday(this)
        val inRide = maxOf(0.0, trk - Store.emptyToday(this))
        c.addView(tv("Azi, cu tura pornită, am măsurat " + Fmt.d1(trk) + " km: " + Fmt.d1(Store.emptyToday(this)) + " în gol și " + Fmt.d1(inRide) + " în cursă.", 12f, Ui.TEXT))
        c.addView(tv("AZI se pune pe 0 la 00:00. Kilometrii cursei acceptate nu se număresc aici, iar valorile nu scad niciodată singure.", 11f, Ui.MUTED))
        return c
    }

    private fun statTile(icon: Int, label: String, value: String): LinearLayout {
        val t = LinearLayout(this)
        t.gravity = Gravity.CENTER_VERTICAL
        t.background = Ui.rounded(Ui.BG, dp(14).toFloat())
        t.setPadding(dp(10), dp(10), dp(10), dp(10))
        val ib = FrameLayout(this)
        ib.background = Ui.rounded(Ui.SURFACE2, dp(10).toFloat())
        val iv = ImageView(this); iv.setImageResource(icon); iv.setColorFilter(Ui.BRAND)
        ib.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        t.addView(ib, LinearLayout.LayoutParams(dp(40), dp(40)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), 0, 0, 0)
        col.addView(tv(label, 12f, Ui.MUTED))
        col.addView(tv(value, 17f, Ui.TEXT, true))
        t.addView(col)
        return t
    }

    private fun welcomeBanner(): FrameLayout {
        val f = FrameLayout(this)
        f.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat())
        f.clipToOutline = true
        val img = ImageView(this)
        img.setImageResource(R.drawable.bg_car)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        f.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val shade = View(this)
        shade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#F006120C"), Color.parseColor("#B006120C"), Color.TRANSPARENT))
        f.addView(shade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(18))
        col.gravity = Gravity.CENTER_VERTICAL
        val nm = Cloud.name(this).ifEmpty { Cloud.email(this).substringBefore('@') }.ifEmpty { "șofer" }
        col.addView(tv("Bun venit,", 16f, Ui.D_MUTED))
        col.addView(tv(nm, 28f, Ui.D_TEXT, true))
        val sub = tv("Condu inteligent.\nCâștigă mai mult.", 14f, Ui.D_TEXT)
        sub.setPadding(0, dp(8), 0, 0)
        col.addView(sub)
        f.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        lp.bottomMargin = dp(12)
        f.layoutParams = lp
        return f
    }

    private fun quickActions(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val lpB = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpB.bottomMargin = dp(12)
        box.layoutParams = lpB
        fun row(): LinearLayout {
            val r = LinearLayout(this)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            box.addView(r, lp)
            return r
        }
        fun q(r: LinearLayout, icon: Int, label: String, go: () -> Unit) {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4), dp(14), dp(4), dp(14))
            t.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat())
            val iv = ImageView(this); iv.setImageResource(icon); iv.setColorFilter(Ui.BRAND)
            t.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
            val l = tv(label, 12f, Ui.TEXT, true); l.gravity = Gravity.CENTER; l.setPadding(0, dp(6), 0, 0)
            t.addView(l)
            t.setOnClickListener { go() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            r.addView(t, lp)
        }
        val r1 = row()
        q(r1, R.drawable.ic_q_radar, "Analizează zonele") { tab = 1; render() }
        q(r1, R.drawable.ic_st_wallet, "Cheltuieli") { tab = 4; render() }
        q(r1, R.drawable.ic_q_clip, "Gestionează actele") { tab = 3; render() }
        val r2 = row()
        q(r2, R.drawable.ic_q_book, "Ghid de activare") { showGuide = true; render() }
        q(r2, R.drawable.ic_st_star, "Contact") { tab = 5; render() }
        return box
    }


    // ---------- CHELTUIELI ----------
    private fun numField(hint: String): EditText {
        val e = textField(hint, false)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return e
    }

    private fun dbl(e: EditText): Double = e.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun renderExpenses() {
        val list = Expenses.all(this)
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        val month = list.filter { it.ts >= monthStart }
        val fuelM = month.filter { it.fuel }.sumOf { it.amount }
        val otherM = month.filter { !it.fuel }.sumOf { it.amount }
        val netM = Store.history(this).filter { it.ts >= monthStart }.sumOf { it.net }

        // --- luna asta ---
        val sum = card()
        sum.addView(tv("LUNA ASTA", 12f, Ui.MUTED, true))
        val r1 = LinearLayout(this)
        r1.setPadding(0, dp(8), 0, 0)
        r1.addView(stat("COMBUSTIBIL", Fmt.d2(fuelM) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(stat("ALTE CHELTUIELI", Fmt.d2(otherM) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(stat("TOTAL", Fmt.d2(fuelM + otherM) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sum.addView(r1)
        val note = tv(
            "Net estimat din curse luna asta: " + Fmt.d2(netM) + " lei (include deja un cost estimat de combustibil și uzură, " +
                "deci nu-l scădea încă o dată pe cel real).",
            11f, Ui.MUTED
        )
        note.setPadding(0, dp(8), 0, 0)
        sum.addView(note)
        content.addView(sum)

        // --- consum real ---
        val fs = Expenses.fuelStats(this)
        val cs = card()
        cs.addView(tv("CONSUM ȘI PREȚ REAL", 12f, Ui.MUTED, true))
        if (fs.consumption == null && fs.pricePerUnit == null) {
            val h = tv("Adaugă alimentări (lei, litri și km de pe bord). Din două alimentări cu plinul calculez consumul real.", 13f, Ui.MUTED)
            h.setPadding(0, dp(4), 0, 0)
            cs.addView(h)
        } else {
            val r2 = LinearLayout(this)
            r2.setPadding(0, dp(8), 0, 0)
            r2.addView(
                stat("CONSUM REAL", if (fs.consumption != null) Fmt.d1(fs.consumption) + " /100 km" else "—"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            r2.addView(
                stat("PREȚ MEDIU", if (fs.pricePerUnit != null) Fmt.d2(fs.pricePerUnit) + " lei" else "—"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            cs.addView(r2)
            val cfg = Store.config(this)
            val h = tv(
                "În setări ai acum: consum " + Fmt.d1(cfg.consumption) + ", preț " + Fmt.d2(cfg.energyPrice) +
                    ". Calculul presupune că faci plinul de fiecare dată (" + fs.intervals + " intervale folosite).",
                11f, Ui.MUTED
            )
            h.setPadding(0, dp(6), 0, 0)
            cs.addView(h)
            cs.addView(button("Folosește valorile reale în setări") {
                fs.consumption?.let { Store.setRaw(this, "cons", String.format(Locale.US, "%.1f", it)) }
                fs.pricePerUnit?.let { Store.setRaw(this, "eprice", String.format(Locale.US, "%.2f", it)) }
                Toast.makeText(this, "Setările de cost au fost actualizate", Toast.LENGTH_SHORT).show()
                render()
            })
        }
        content.addView(cs)

        // --- adaugă alimentare ---
        val fc = card()
        fc.addView(tv("ADAUGĂ ALIMENTARE", 12f, Ui.MUTED, true))
        val fAmount = numField("Cât ai plătit (lei)")
        val fLiters = numField("Litri / kWh")
        val fOdo = numField("Km la bord (opțional, pentru consum)")
        fc.addView(fAmount); fc.addView(fLiters); fc.addView(fOdo)
        fc.addView(button("Salvează alimentarea") {
            val a = dbl(fAmount)
            val l = dbl(fLiters)
            if (a <= 0.0 || l <= 0.0) {
                Toast.makeText(this, "Completează lei și litri", Toast.LENGTH_SHORT).show()
            } else {
                Expenses.add(this, Expense(System.currentTimeMillis(), true, a, l, dbl(fOdo), ""))
                Toast.makeText(this, "Alimentare salvată", Toast.LENGTH_SHORT).show()
                render()
            }
        })
        content.addView(fc)

        // --- altă cheltuială ---
        val oc = card()
        oc.addView(tv("ADAUGĂ ALTĂ CHELTUIALĂ", 12f, Ui.MUTED, true))
        val oNote = textField("Ce a fost (spălătorie, service, parcare...)", false)
        val oAmount = numField("Cât (lei)")
        oc.addView(oNote); oc.addView(oAmount)
        oc.addView(button("Salvează cheltuiala") {
            val a = dbl(oAmount)
            if (a <= 0.0) {
                Toast.makeText(this, "Completează suma", Toast.LENGTH_SHORT).show()
            } else {
                Expenses.add(this, Expense(System.currentTimeMillis(), false, a, 0.0, 0.0, oNote.text.toString().trim()))
                Toast.makeText(this, "Cheltuială salvată", Toast.LENGTH_SHORT).show()
                render()
            }
        })
        content.addView(oc)

        // --- listă ---
        if (list.isNotEmpty()) {
            val hd = tv("Ultimele înregistrări", 17f, Ui.TEXT, true)
            hd.setPadding(0, dp(4), 0, dp(8))
            content.addView(hd)
            val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale.forLanguageTag("ro-RO"))
            for (e in list.take(15)) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat())
                row.setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                row.layoutParams = lp
                val title = if (e.fuel) "Alimentare  ·  " + Fmt.d2(e.amount) + " lei" else (e.note.ifEmpty { "Cheltuială" }) + "  ·  " + Fmt.d2(e.amount) + " lei"
                row.addView(tv(title, 15f, Ui.TEXT, true))
                val detail = if (e.fuel) {
                    fmt.format(Date(e.ts)) + "  ·  " + Fmt.d1(e.liters) + " l/kWh  ·  " + Fmt.d2(e.amount / e.liters) + " lei/unitate" +
                        (if (e.odo > 0.0) "  ·  " + Math.round(e.odo) + " km" else "")
                } else fmt.format(Date(e.ts))
                row.addView(tv(detail, 12f, Ui.MUTED))
                row.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Ștergi înregistrarea?")
                        .setPositiveButton("Șterge") { _, _ -> Expenses.delete(this, e.ts); render() }
                        .setNegativeButton("Anulează", null)
                        .show()
                    true
                }
                content.addView(row)
            }
            val h2 = tv("Ține apăsat pe o înregistrare ca s-o ștergi.", 11f, Ui.MUTED)
            h2.gravity = Gravity.CENTER
            content.addView(h2)
        }
    }

    // ---------- ACTE ----------
    private fun pickDate(initial: Long, onPick: (Long) -> Unit) {
        val d = LocalDate.ofEpochDay(initial)
        DatePickerDialog(this, { _, y, m, day -> onPick(Docs.epochOf(y, m, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun takePhoto(id: Long) {
        val name = "doc_" + id + "_" + System.currentTimeMillis() + ".jpg"
        Store.setRaw(this, "pending_photo", "$id|$name")
        val uri = Uri.parse("content://ro.drivescore.photos/$name")
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        i.clipData = ClipData.newRawUri("", uri)
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, 77) } catch (e: Exception) {
            Toast.makeText(this, "Nu am găsit o aplicație de cameră.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77) return
        val p = (Store.raw(this, "pending_photo") ?: "").split("|")
        if (p.size == 2) {
            val id = p[0].toLongOrNull() ?: 0L
            val f = Docs.photoFile(this, p[1])
            if (resultCode == RESULT_OK && f.exists() && f.length() > 0) {
                Docs.load(this).firstOrNull { it.id == id }?.let { if (it.photo.isNotEmpty()) Docs.photoFile(this, it.photo).delete() }
                Docs.update(this, id) { it.copy(photo = p[1]) }
            } else f.delete()
        }
        Store.setRaw(this, "pending_photo", "")
        render()
    }

    private fun showPhoto(d: DocItem) {
        val f = Docs.photoFile(this, d.photo)
        val opts = BitmapFactory.Options()
        opts.inSampleSize = 4
        var bmp = BitmapFactory.decodeFile(f.path, opts)
        if (bmp == null) { Toast.makeText(this, "Poza nu mai există.", Toast.LENGTH_SHORT).show(); return }
        try {
            val rot = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) { val m = Matrix(); m.postRotate(rot); bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }
        } catch (e: Exception) { }
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        AlertDialog.Builder(this).setTitle(d.type).setView(iv)
            .setPositiveButton("Închide", null)
            .setNeutralButton("Poză nouă") { _, _ -> takePhoto(d.id) }
            .show()
    }

    private fun renderDocs() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val head = card()
        head.addView(tv("ACTE ȘI VALABILITATE", 12f, Ui.MUTED, true))
        head.addView(tv("Adaugă rovinieta, ITP-ul, RCA-ul etc. cu data de expirare și o poză. Cu 3 zile înainte primești o notificare în fiecare zi, până apeși „Am rezolvat”.", 13f, Ui.TEXT))
        head.addView(button("+ Adaugă act") {
            AlertDialog.Builder(this).setTitle("Ce act adaugi?").setItems(Docs.TYPES) { _, which ->
                pickDate(Docs.today() + 365) { exp ->
                    val id = Docs.add(this, Docs.TYPES[which], exp)
                    render()
                    AlertDialog.Builder(this).setMessage("Faci o poză actului acum?")
                        .setPositiveButton("Da") { _, _ -> takePhoto(id) }
                        .setNegativeButton("Mai târziu", null).show()
                }
            }.show()
        })
        content.addView(head)
        for (d in Docs.load(this)) {
            val left = d.expiry - Docs.today()
            val col = if (left < 0) Ui.RED else if (left <= Docs.WARN_DAYS) Ui.AMBER else Ui.GREEN
            val c = card()
            c.addView(tv(d.type, 16f, Ui.TEXT, true))
            c.addView(tv("Valabil până la " + Docs.fmt(d.expiry), 13f, Ui.MUTED))
            c.addView(tv(Docs.statusText(left).replaceFirstChar { it.uppercase() }, 14f, col, true))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            fun sb(label: String, onClick: () -> Unit) {
                val b = button(label, onClick)
                b.textSize = 13f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(3), dp(10), dp(3), 0)
                b.layoutParams = lp
                row.addView(b)
            }
            sb(if (d.photo.isEmpty()) "Poză" else "Vezi poză") { if (d.photo.isEmpty()) takePhoto(d.id) else showPhoto(d) }
            sb("Am rezolvat") {
                Toast.makeText(this, "Alege noua dată de expirare.", Toast.LENGTH_SHORT).show()
                pickDate(maxOf(d.expiry, Docs.today()) + 365) { exp -> Docs.update(this, d.id) { it.copy(expiry = exp, lastNotif = -1) }; render() }
            }
            sb("Șterge") {
                AlertDialog.Builder(this).setMessage("Ștergi ${d.type}?")
                    .setPositiveButton("Șterge") { _, _ -> Docs.delete(this, d.id); render() }
                    .setNegativeButton("Anulează", null).show()
            }
            c.addView(row)
            content.addView(c)
        }
    }

    // ---------- ZONE ----------
    private fun button(text: String, onClick: () -> Unit): TextView {
        val b = tv(text, 14f, Ui.ON_ACCENT, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(12), dp(14), dp(12))
        b.background = Ui.rounded(Ui.ACCENT, dp(12).toFloat())
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        return b
    }

    private fun confidence(z: Zone): String {
        return Zones.confText(z)
    }

    /** Merită să te muți? Câștigul estimat pe cursă în zona bună, față de cât costă deplasarea până acolo. */
    private fun recommendation(zones: List<Zone>, an: Zones.Analysis, me: Pair<Double, Double>?): String? {
        if (me == null || zones.isEmpty()) return null
        val cfg = Store.config(this)
        val hist = Store.history(this)
        val myNpk = hist.map { it.netPerKm }.filter { !it.isNaN() && it > 0 }.average().takeIf { !it.isNaN() } ?: an.globalNpk
        val typKm = hist.map { it.totalKm }.filter { it > 0 }.average().takeIf { !it.isNaN() && hist.size >= 3 } ?: 8.0
        val z = zones.firstOrNull { it.conf >= 1 } ?: return null
        val dist = Geo.distKm(me.first, me.second, z.lat, z.lon)
        if (dist < 0.8) return "Ești deja în zona bună."
        val here = zones.minByOrNull { Geo.distKm(me.first, me.second, it.lat, it.lon) }
        val hereNpk = if (here != null && Geo.distKm(me.first, me.second, here.lat, here.lon) < 0.8 && here.avgNetPerKm > 0) here.avgNetPerKm else myNpk
        val gain = (z.avgNetPerKm - hereNpk) * typKm
        val cost = dist * cfg.costPerKm
        val name = Zones.label(z, an)
        return if (gain <= 0.3) "Aici câștigi cam la fel ca în $name; deplasarea (${Fmt.d1(dist)} km, ~${Fmt.d1(cost)} lei) nu s-ar plăti."
        else "Mergi spre $name (${Fmt.d1(dist)} km): ~+${Fmt.d1(gain)} lei pe cursă față de aici, deplasarea costă ~${Fmt.d1(cost)} lei și se recuperează în ~${Math.ceil(cost / gain).toInt().coerceAtLeast(1)} curse."
    }

    private fun renderZones() {
        // ----- permisiuni -----
        if (!Geo.hasPermission(this)) {
            val c = card()
            c.addView(tv("LOCAȚIE", 12f, Ui.MUTED, true))
            c.addView(tv("Ca să țin minte de unde iei curse și unde apar cele mai multe oferte, am nevoie de locația telefonului. Locația se păstrează pe telefon; dacă intri în comunitate (mai jos), se trimite și în contul comun.", 13f, Ui.TEXT))
            c.addView(button("Permite locația") {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            })
            content.addView(c)
            return
        }
        if (!Geo.hasBackground(this)) {
            val c = card()
            c.addView(tv("LOCAȚIE ÎN FUNDAL", 12f, Ui.MUTED, true))
            c.addView(tv("Ca să salvez locația și când ești în aplicația de ridesharing, deschide Permisiuni → Locație și alege „Permite tot timpul”.", 13f, Ui.TEXT))
            c.addView(button("Deschide setările aplicației") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            content.addView(c)
        }

        val events = Store.geoEvents(this) + Cloud.communityEvents(this)
        val now = System.currentTimeMillis()
        Names.init(this)
        val an = Zones.analyze(events, now, Store.dwellSnapshot(this), Store.config(this).greenNetPerKm)
        val me = Geo.last(this)
        val zones = an.zones
        Names.prefetch(zones.take(10)) { if (!isFinishing && tab == 1) render() }

        if (events.sumOf { it.weight } < 10 || zones.isEmpty()) {
            val c = card()
            c.addView(tv("Adun date…", 15f, Ui.TEXT, true))
            c.addView(tv("Am ${events.sumOf { it.weight }} puncte cu locație. Recomandările apar după ~10 oferte sau curse. Lucrează normal cu aplicația de ridesharing deschisă, iar eu notez de unde vin ofertele.", 13f, Ui.MUTED))
            content.addView(c)
            addZoneData(events)
            return
        }

        val top = zones[0].score
        fun norm(z: Zone): Double = if (top > 0) 100.0 * z.score / top else 0.0
        val hh = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        val sub = (if (an.usedTimeFilter) "după ora și tipul zilei (lucrătoare / weekend)" else "încă puține date pentru această oră, folosesc și restul istoricului") + " · $hh"

        fun where(z: Zone): String {
            if (me == null) return Zones.label(z, an)
            val km = Geo.distKm(me.first, me.second, z.lat, z.lon)
            return Zones.label(z, an) + "  ·  " + Fmt.d1(km) + " km spre " + Geo.direction(me.first, me.second, z.lat, z.lon)
        }

        // ----- cea mai bună zonă -----
        val best = zones[0]
        val bc = card()
        bc.background = Ui.rounded(Ui.SURFACE2, dp(18).toFloat(), Ui.BRAND, dp(1))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv("CEA MAI BUNĂ ZONĂ ACUM", 11f, Ui.BRAND, true))
        left.addView(tv(Zones.label(best, an), 22f, Ui.TEXT, true))
        left.addView(tv(where(best), 12f, Ui.MUTED))
        head.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.addView(tv("100.0", 28f, Ui.BRAND, true))
        right.addView(tv("SCOR", 10f, Ui.MUTED, true))
        head.addView(right)
        bc.addView(head)
        val st = LinearLayout(this)
        st.orientation = LinearLayout.HORIZONTAL
        st.setPadding(0, dp(10), 0, 0)
        st.addView(stat("OFERTE", best.offers.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        st.addView(stat("CURSE", best.rides.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        st.addView(stat("NET/KM", Fmt.d2(best.avgNetPerKm)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bc.addView(st)
        val st2 = LinearLayout(this)
        st2.orientation = LinearLayout.HORIZONTAL
        st2.setPadding(0, dp(8), 0, 0)
        st2.addView(stat("OFERTE/ORĂ", if (best.offersPerHour >= 0) Fmt.d1(best.offersPerHour) else "–"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        st2.addView(stat("CURSE BUNE", if (best.goodShare >= 0) (best.goodShare * 100).toInt().toString() + "%" else "–"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        st2.addView(stat("DATE", Zones.confText(best)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bc.addView(st2)
        recommendation(zones, an, me)?.let {
            val rt = tv(it, 12f, Ui.TEXT)
            rt.setPadding(0, dp(8), 0, 0)
            bc.addView(rt)
        }
        bc.addView(tv(sub + " · " + confidence(best), 11f, Ui.MUTED))

        // ----- alte zone -----
        if (zones.size > 1) {
            val oh = tv("Alte zone care merită", 13f, Ui.TEXT, true)
            oh.setPadding(0, dp(14), 0, dp(4))
            bc.addView(oh)
            for (i in 1 until minOf(zones.size, 3)) {
                val z = zones[i]
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.background = Ui.rounded(Ui.SURFACE2, dp(12).toFloat())
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                row.layoutParams = lp
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.addView(tv(Zones.label(z, an), 15f, Ui.TEXT, true))
                col.addView(tv("Opțiunea ${i + 1} · ${z.offers} oferte, ${z.rides} curse · ${confidence(z)}", 11f, Ui.MUTED))
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(tv(Fmt.d1(norm(z)), 16f, Ui.AMBER, true))
                bc.addView(row)
            }
        }
        content.addView(bc)

        // ----- harta cu zone rotunde care pulsează -----
        val mc = card()
        mc.addView(tv("HARTA CERERII", 12f, Ui.MUTED, true))
        val detail = tv("Atinge un cerc ca să vezi detaliile. Punctul albastru ești tu.", 12f, Ui.MUTED)
        detail.setPadding(0, dp(4), 0, dp(8))
        mc.addView(detail)

        val map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)   // hartă întunecată
        map.controller.setZoom(12.5)
        map.controller.setCenter(GeoPoint(me?.first ?: an.centerLat, me?.second ?: an.centerLon))
        map.overlays.add(HotZoneOverlay(zones.take(25), top, me) { z ->
            detail.text = Zones.label(z, an) + ": ${z.offers} oferte, ${z.rides} curse, " +
                Fmt.d2(z.avgNetPerKm) + " lei/km net · scor " + Fmt.d1(norm(z))
            detail.setTextColor(Ui.TEXT)
        })
        map.setOnTouchListener { v, e ->
            // harta stă într-un ScrollView: las gesturile hărții să nu fie furate de derulare
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        map.onResume()
        zoneMap = map
        mc.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(380)))
        val legend = tv("Cercurile mari și roșii = cerere mare. Cele fierbinți pulsează: cu cât mai repede, cu atât mai multă cerere. Harta are nevoie de internet.", 11f, Ui.MUTED)
        legend.setPadding(0, dp(8), 0, 0)
        mc.addView(legend)
        content.addView(mc)

        addZoneData(events)
    }

    private fun addZoneData(events: List<GeoEvent>) {
        val c = card()
        c.addView(tv("DATE ADUNATE", 12f, Ui.MUTED, true))
        val o = events.filter { it.kind == 0 }.sumOf { it.weight }
        val r = events.filter { it.kind == 1 }.sumOf { it.weight }
        c.addView(tv("$o oferte văzute, $r curse acceptate cu locație (tu + comunitatea)", 13f, Ui.TEXT))
        c.addView(button("Șterge datele de zone (oferte văzute)") {
            AlertDialog.Builder(this)
                .setMessage("Ștergi ofertele văzute folosite pentru zone? Cursele din istoric rămân.")
                .setPositiveButton("Șterge") { _, _ -> Store.clearOffers(this); render() }
                .setNegativeButton("Anulează", null)
                .show()
        })
        content.addView(c)
        renderCloudCard()
    }

    private fun textField(hint: String, caps: Boolean, extraType: Int = 0): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Ui.MUTED)
        e.setTextColor(Ui.TEXT)
        e.textSize = 15f
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or extraType or (if (caps) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
        e.background = Ui.rounded(Ui.SURFACE2, dp(10).toFloat())
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        e.layoutParams = lp
        return e
    }

    private fun refreshAfter(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (!isFinishing) render()
    }

    private val CONTACT_EMAIL = "alexdbx89@gmail.com"

    private fun copyEmail() {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("email", CONTACT_EMAIL))
        Toast.makeText(this, "Adresa a fost copiată.", Toast.LENGTH_SHORT).show()
    }

    private fun contactMail() {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + CONTACT_EMAIL + "?subject=" + Uri.encode("DBX Pulse – sugestie sau problemă"))))
        } catch (e: Exception) { copyEmail() }
    }

    private fun openRevolut() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://revolut.me/alexanzcux")))

    private fun sendPaid() {
        Cloud.requestPayment(this) { ok, msg ->
            Toast.makeText(this, if (ok) "Cererea a fost trimisă. Activarea durează între 1 și 3 ore." else msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun payDialog() {
        AlertDialog.Builder(this).setTitle("Abonament 19,9 lei pe lună")
            .setMessage("Plătești 19,9 lei pe Revolut și scrii în mesaj emailul contului: " + Cloud.email(this) + ". Apoi apeși „Am plătit”. Activarea durează între 1 și 3 ore de la plată.")
            .setPositiveButton("Deschide Revolut") { _, _ -> openRevolut() }
            .setNeutralButton("Am plătit") { _, _ -> sendPaid() }
            .setNegativeButton("Închide", null).show()
    }

    private fun accessCard(): LinearLayout? {
        if (!Cloud.REQUIRE_INVITE || !Cloud.joined(this) || Cloud.isUnlimited(this)) return null
        val until = Cloud.accessUntil(this)
        if (until <= 0L) return null
        val days = Math.ceil((until - System.currentTimeMillis()) / 86400000.0).toInt()
        val d = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("ro-RO")).format(Date(until))
        val c = card()
        c.addView(tv("ACCES", 12f, Ui.MUTED, true))
        c.addView(tv(if (days > 1) "Acces activ până la $d (mai ai $days zile)." else "Accesul expiră în curând ($d).", 14f, if (days <= 3) Ui.AMBER else Ui.TEXT, true))
        c.addView(tv("Abonament: 19,9 lei pe lună, plătit pe Revolut. Activarea durează între 1 și 3 ore de la plată.", 12f, Ui.MUTED))
        c.addView(button("Plătește / reînnoiește") { payDialog() })
        return c
    }

    private fun maybeWelcome() {
        if (!Cloud.joined(this) || Store.raw(this, "welcomed") == "1") return
        Store.setRaw(this, "welcomed", "1")
        val until = Cloud.accessUntil(this)
        val days = if (Cloud.isUnlimited(this) || until <= 0L) 0 else Math.ceil((until - System.currentTimeMillis()) / 86400000.0).toInt()
        val msg = (if (days > 0) "Ai $days zile gratuite, apoi 19,9 lei pe lună. " else "") +
            "Primul pas: activează serviciul de accesibilitate, ca să apară cardul peste aplicația de ridesharing. Ghidul te duce pas cu pas."
        AlertDialog.Builder(this).setTitle("Bun venit în DBX Pulse!").setMessage(msg)
            .setPositiveButton("Deschide ghidul") { _, _ -> showGuide = true; render() }
            .setNegativeButton("Mai târziu", null).show()
    }

    private val guideSteps = listOf(
        Triple(R.drawable.guide_01, "Deschide Accesibilitate și alege DBX Pulse", "Intră în Setări → Funcții de accesibilitate → Servicii descărcate. Apasă pe DBX Pulse. Aici se pornește serviciul care citește oferta din aplicația de ridesharing și afișează cardul."),
        Triple(R.drawable.guide_02, "Comutatorul e gri", "Scrie „Controlat prin setare restricționată”. E normal: Android blochează serviciile din aplicațiile instalate din afara magazinului Play. Apasă pe comutator."),
        Triple(R.drawable.guide_03, "Mesajul „Accesul aplicației a fost refuzat”", "Apasă Închide. Aplicația nu e stricată: trebuie doar să ridici restricția din informațiile aplicației, în pașii următori."),
        Triple(R.drawable.guide_04, "Deschide Setări", "Caută „Setări” din căutarea telefonului sau apasă iconița cu rotița. Meniurile diferă puțin între mărci, dar pașii sunt aceiași."),
        Triple(R.drawable.guide_05, "Setări → Aplicații", "Derulează în listă și apasă pe Aplicații."),
        Triple(R.drawable.guide_06, "Aplicații → Gestionare aplicații", "Apasă pe Gestionare aplicații, ca să vezi lista cu toate aplicațiile."),
        Triple(R.drawable.guide_07, "Alege DBX Pulse din listă", "Derulează la litera D sau folosește căutarea de sus și apasă pe DBX Pulse."),
        Triple(R.drawable.guide_08, "Îndepărtează restricția", "Sus apare un banner roșu: „Aplicația a fost restricționată”. Apasă pe „Îndepărtați restricția”. Pe alte telefoane (Android 13 sau mai nou): apeși cele trei puncte din colț și alegi „Permite setări restricționate”. Poate cere PIN sau amprentă."),
        Triple(R.drawable.guide_09, "Restricția a fost anulată", "Apare mesajul „Restricționarea aplicației a fost anulată”. Acum apasă pe Permisiuni."),
        Triple(R.drawable.guide_10, "Dă permisiunile", "Apasă pe Locație și alege „Permite tot timpul”, ca aplicația să măsoare km și zonele și când aplicația de ridesharing e deschisă. Apasă pe Notificări și permite-le, pentru remindere. Recomand și să oprești comutatorul de jos, „Gestionează aplicația dacă nu e folosită”, ca Android să nu-ți ia permisiunile."),
        Triple(R.drawable.guide_11, "Pornește serviciul de accesibilitate", "Întoarce-te în Accesibilitate → DBX Pulse și apasă pe comutator. Apare fereastra „Utilizați DBX Pulse”: apasă OK."),
        Triple(R.drawable.guide_12, "Gata: „Activ”", "În aplicație, sub titlu, trebuie să scrie „Activ — cardul apare automat în aplicația de ridesharing”. Deschide aplicația de ridesharing; la prima ofertă apare cardul.")
    )

    private fun renderGuide() {
        val top = card()
        top.addView(tv("Ghid de activare", 22f, Ui.TEXT, true))
        top.addView(tv("Android blochează implicit aplicațiile instalate din afara magazinului Play. Urmează pașii în ordine; durează câteva minute.", 13f, Ui.MUTED))
        top.addView(button("← Înapoi") { showGuide = false; render() })
        content.addView(top)
        val pre = card()
        pre.addView(tv("Înainte de pasul 1", 15f, Ui.TEXT, true))
        pre.addView(tv("La instalarea APK-ului permite instalarea din „surse necunoscute”. Dacă Google Play Protect avertizează, alege „Instalează oricum”.", 13f, Ui.MUTED))
        content.addView(pre)
        for ((i, s) in guideSteps.withIndex()) {
            val c = card()
            c.addView(tv("Pasul ${i + 1} · " + s.second, 16f, Ui.BRAND, true))
            val iv = ImageView(this)
            iv.setImageResource(s.first)
            iv.adjustViewBounds = true
            val lp = LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(10); lp.bottomMargin = dp(10)
            c.addView(iv, lp)
            c.addView(tv(s.third, 13f, Ui.TEXT))
            content.addView(c)
        }
        val end = card()
        end.addView(tv("Dacă se oprește după o actualizare sau o repornire a telefonului, repetă pasul 11 (comutatorul din Accesibilitate).", 13f, Ui.MUTED))
        end.addView(button("← Înapoi") { showGuide = false; render() })
        content.addView(end)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showGuide) { showGuide = false; render() } else super.onBackPressed()
    }

    private fun renderSplash() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val logo = LinearLayout(this)
        logo.gravity = Gravity.CENTER_VERTICAL
        val dbx = tv("DBX", 44f, Ui.TEXT, true)
        dbx.setTypeface(dbx.typeface, Typeface.BOLD_ITALIC)
        logo.addView(dbx)
        val pl = ImageView(this)
        pl.setImageResource(R.drawable.ic_pulse)
        logo.addView(pl)
        col.addView(logo)
        val pulse = tv("Pulse", 38f, Ui.BRAND, true)
        pulse.setTypeface(pulse.typeface, Typeface.BOLD_ITALIC)
        col.addView(pulse)
        val tag = tv("Statistici inteligente\npentru șoferi ridesharing", 17f, Ui.TEXT)
        tag.gravity = Gravity.CENTER
        tag.setPadding(0, dp(12), 0, dp(8))
        col.addView(tag)
        val car = ImageView(this)
        car.setImageResource(R.drawable.bg_car)
        car.adjustViewBounds = true
        car.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(car, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun feat(icon: Int, t: String, sub: String) {
            val f = LinearLayout(this)
            f.orientation = LinearLayout.VERTICAL
            f.gravity = Gravity.CENTER_HORIZONTAL
            f.setPadding(dp(6), dp(14), dp(6), dp(14))
            f.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat())
            val i = ImageView(this)
            i.setImageResource(icon)
            i.setColorFilter(Ui.BRAND)
            f.addView(i, LinearLayout.LayoutParams(dp(30), dp(30)))
            val a = tv(t, 13f, Ui.TEXT, true); a.gravity = Gravity.CENTER; a.setPadding(0, dp(8), 0, dp(4))
            val b = tv(sub, 11f, Ui.MUTED); b.gravity = Gravity.CENTER
            f.addView(a); f.addView(b)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            row.addView(f, lp)
        }
        feat(R.drawable.ic_nav_bars, "Urmărește câștigurile", "Vezi veniturile în timp real")
        feat(R.drawable.ic_nav_zone, "Analizează zonele", "Află cele mai bune zone din oraș")
        feat(R.drawable.ic_nav_docs, "Gestionează actele", "Păstrează totul într-un singur loc")
        col.addView(row)
        col.addView(button("Intră în aplicație  →") { splashDone = true; render() })
        content.addView(col)
    }

    private fun renderJoin() {
        val c = card()
        c.addView(tv("Cod de invitație", 22f, Ui.TEXT, true))
        c.addView(tv("Contul " + Cloud.email(this) + " e creat, dar nu are încă acces. Scrie numele și codul primit de la administrator.", 13f, Ui.MUTED))
        val err = Store.raw(this, "join_error") ?: ""
        if (err.isNotEmpty()) c.addView(tv(err, 13f, Ui.RED, true))
        val nameF = textField("Numele sau nickname-ul tău", false)
        val codeF = textField("Cod de invitație", false)
        c.addView(nameF)
        c.addView(codeF)
        c.addView(button("Intru în aplicație") {
            val n = nameF.text.toString().trim()
            val code = codeF.text.toString().trim()
            if (n.isEmpty() || code.isEmpty()) {
                Toast.makeText(this, "Completează numele și codul.", Toast.LENGTH_SHORT).show()
            } else {
                Cloud.join(this, n, code) { ok, msg ->
                    if (ok) Cloud.syncAsync(this, true) { }
                    Store.setRaw(this, "join_error", if (ok) "" else msg)
                    refreshAfter(msg)
                }
            }
        })
        c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        content.addView(c)
    }

    private fun renderExpired() {
        val c = card()
        c.addView(tv("Perioada gratuită s-a încheiat", 22f, Ui.TEXT, true))
        c.addView(tv("Abonamentul DBX Pulse costă 19,9 lei pe lună.\n\n1. Apasă „Plătește 19,9 lei pe Revolut”.\n2. În mesajul plății scrie emailul contului tău: " + Cloud.email(this) + "\n3. Revino aici și apasă „Am plătit”.\n\nActivarea durează între 1 și 3 ore de la plată. Primești accesul automat, nu trebuie să faci nimic altceva.\n\nDacă după 3 ore nu ai acces, scrie-mi la " + CONTACT_EMAIL + ".", 13f, Ui.MUTED))
        c.addView(button("Plătește 19,9 lei pe Revolut") { openRevolut() })
        c.addView(button("Am plătit") { sendPaid() })
        c.addView(button("Verific din nou") { Cloud.refreshAccessAsync(this, true) { render() } })
        c.addView(button("Ghid de activare") { showGuide = true; render() })
        c.addView(button("Scrie-mi un email") { contactMail() })
        c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        content.addView(c)
    }

    private fun showInvite(code: String) {
        AlertDialog.Builder(this).setTitle("Cod de invitație").setMessage(code + "\n\nValabil o singură dată. Trimite-l persoanei.")
            .setPositiveButton("Copiază") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("cod", code))
                Toast.makeText(this, "Cod copiat.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Închide", null).show()
    }

    private fun renderLogin() {
        val c = card()
        c.addView(tv("Bun venit", 22f, Ui.TEXT, true))
        c.addView(tv("Intră în cont sau creează unul cu email și parolă.", 13f, Ui.MUTED))
        val emailF = textField("Email", false, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passF = textField("Parolă (minim 6 caractere)", false, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val nickF = textField("Nume sau nickname (la cont nou)", false)
        val codeF = textField("Cod de invitație (la cont nou)", false)
        c.addView(nickF)
        if (Cloud.REQUIRE_INVITE) c.addView(codeF)
        c.addView(emailF)
        c.addView(passF)
        fun go(create: Boolean) {
            val em = emailF.text.toString().trim()
            val pw = passF.text.toString()
            if (em.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this, "Completează emailul și parola.", Toast.LENGTH_SHORT).show()
                return
            }
            if (create && (nickF.text.toString().trim().isEmpty() || (Cloud.REQUIRE_INVITE && codeF.text.toString().trim().isEmpty()))) {
                Toast.makeText(this, "Scrie numele și codul de invitație.", Toast.LENGTH_SHORT).show()
                return
            }
            Store.setRaw(this, "pending_nick", nickF.text.toString().trim())
            Store.setRaw(this, "pending_code", codeF.text.toString().trim())
            Store.setRaw(this, "join_error", "")
            Cloud.signIn(this, em, pw, create) { ok, msg ->
                if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                refreshAfter(msg)
            }
        }
        if (Cloud.REQUIRE_INVITE) c.addView(tv("Cont nou: ai nevoie de un cod de invitație. Primești 7 zile gratuite, apoi 19,9 lei pe lună.", 12f, Ui.MUTED))
        c.addView(button("Intru în cont") { go(false) })
        c.addView(button("Creez cont") { go(true) })
        c.addView(button("Ghid de activare") { showGuide = true; render() })
        c.addView(tv("Prin „Creez cont” ești de acord ca locația (unde apare oferta), ora, prețul și km ale ofertelor tale să fie trimise în contul comun. Ceilalți șoferi văd doar zone agregate, fără nume sau ID, iar administratorul aplicației poate vedea datele din cont. Destinația cursei nu se trimite. Îți poți șterge datele oricând din Setări.", 12f, Ui.MUTED))
        content.addView(c)
    }

    private fun renderCloudCard() {
        val c = card()
        c.addView(tv("CONT ȘI COMUNITATE", 12f, Ui.MUTED, true))
        if (!Cloud.configured()) {
            c.addView(tv("Sincronizarea nu e configurată încă (lipsește cheia publică Supabase din Cloud.kt).", 13f, Ui.AMBER))
            content.addView(c)
            return
        }
        if (!Cloud.loggedIn(this)) {
            // 1) autentificare
            c.addView(
                tv(
                    "Cu un cont păstrezi datele dacă schimbi telefonul. Dacă intri în comunitate, aplicația trimite în " +
                        "contul comun DBX Pulse locația, ora, prețul și km ale ofertelor văzute și ale curselor acceptate, " +
                        "legate de ID-ul contului tău. Ceilalți șoferi văd doar zone agregate, fără nume sau ID. " +
                        "Administratorul vede tot. Poți șterge oricând datele tale din cloud. " +
                        "Prin „Creez cont” ești de acord cu acestea.", 13f, Ui.TEXT
                )
            )
            val emailF = textField("Email", false, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val passF = textField("Parolă (minim 6 caractere)", false, InputType.TYPE_TEXT_VARIATION_PASSWORD)
            c.addView(emailF)
            c.addView(passF)
            fun go(create: Boolean) {
                val em = emailF.text.toString().trim()
                val pw = passF.text.toString()
                if (em.isEmpty() || pw.isEmpty()) {
                    Toast.makeText(this, "Completează emailul și parola.", Toast.LENGTH_SHORT).show()
                    return
                }
                Cloud.signIn(this, em, pw, create) { ok, msg ->
                    if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                    refreshAfter(msg)
                }
            }
            c.addView(button("Intru în cont") { go(false) })
            c.addView(button("Creez cont") { go(true) })
        } else if (!Cloud.joined(this)) {
            // 2) cont făcut, dar încă nu e în comunitate (ex. fără internet la intrare): doar numele
            c.addView(tv("Cont: " + Cloud.email(this), 13f, Ui.BRAND, true))
            c.addView(tv("Alege numele cu care apari în comunitate.", 13f, Ui.TEXT))
            val nameF = textField("Numele tău (ex: Mihai)", false)
            c.addView(nameF)
            c.addView(button("Intru în comunitate") {
                val n = nameF.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "Completează numele.", Toast.LENGTH_SHORT).show()
                } else {
                    Cloud.join(this, n, "") { ok, msg ->
                        if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                        refreshAfter(msg)
                    }
                }
            })
            c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        } else {
            // 3) membru
            val roleTxt = if (Cloud.role(this) == "admin") " · admin" else ""
            c.addView(tv("Conectat ca ${Cloud.name(this)}$roleTxt", 15f, Ui.BRAND, true))
            c.addView(tv(Cloud.email(this) + " · ID " + Cloud.uid(this).take(8), 12f, Ui.MUTED))
            val st = Cloud.status(this)
            if (st.isNotEmpty()) c.addView(tv(st, 12f, Ui.MUTED))
            c.addView(button("Sincronizează acum") {
                Cloud.syncAsync(this, true) { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (!isFinishing && tab == 1) render()
                }
            })
            c.addView(button("Șterge datele mele din cloud") {
                AlertDialog.Builder(this)
                    .setMessage("Ștergi toate datele tale din cloud și ieși din comunitate? Istoricul de pe telefon rămâne.")
                    .setPositiveButton("Șterge") { _, _ -> Cloud.leave(this) { _, msg -> refreshAfter(msg) } }
                    .setNegativeButton("Anulează", null)
                    .show()
            })
        }
        content.addView(c)
    }

    // ---------- SETĂRI ----------
    /** Card de Setări, cu contur vizibil ca să se vadă unde se termină o secțiune. */
    private fun scard(): LinearLayout {
        val c = card()
        val line = (Ui.MUTED and 0x00FFFFFF) or 0x66000000
        c.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat(), line, dp(1))
        (c.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(18)
        return c
    }

    /** Pagina „Contact” (separată de Setări). */
    private fun renderContact() {
        val ct = card()
        ct.addView(tv("CONTACT", 13f, Ui.BRAND, true))
        ct.addView(tv("Ai o idee, ai găsit o problemă sau ceva nu merge cum te aștepți? Scrie-mi. Aplicația o fac împreună cu voi, șoferii, și fiecare sugestie ajută.", 14f, Ui.TEXT))
        val ct2 = tv("Pentru o problemă, spune-mi pe ce telefon ești și ce ai văzut pe ecran. O captură mă ajută mult.", 13f, Ui.MUTED)
        ct2.setPadding(0, dp(6), 0, 0)
        ct.addView(ct2)
        val em = tv(CONTACT_EMAIL, 17f, Ui.BRAND, true)
        em.setPadding(0, dp(10), 0, dp(2))
        em.setOnClickListener { copyEmail() }
        ct.addView(em)
        ct.addView(tv("Răspund cât de repede pot.", 13f, Ui.MUTED))
        ct.addView(button("Scrie-mi un email") { contactMail() })
        content.addView(ct)
    }

    /** Contul, pe pagina principală: cine e conectat și ieșirea din cont. */
    private fun accountCard(): LinearLayout? {
        if (!Cloud.configured()) return null
        val c = card()
        if (Cloud.loggedIn(this)) {
            c.addView(tv("CONT", 12f, Ui.MUTED, true))
            c.addView(tv(Cloud.email(this) + (if (Cloud.role(this) == "admin") " · admin" else ""), 14f, Ui.TEXT))
            c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        } else {
            c.addView(tv("Nu ești conectat. Cu un cont păstrezi datele și intri în comunitate.", 13f, Ui.MUTED))
            c.addView(button("Intră în cont") { tab = 1; render() })
        }
        return c
    }

    private fun renderSettings() {
        // simulator
        val sim = scard()
        sim.addView(tv("SIMULATOR", 13f, Ui.BRAND, true))
        val simHint = tv("Încearcă o cursă și vezi exact cum arată cardul cu setările tale.", 13f, Ui.MUTED)
        simHint.setPadding(0, dp(4), 0, dp(8))
        sim.addView(simHint)

        val oc = OverlayCard(this)
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.addView(oc.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var simPrice = "28"
        var simPick = "2"
        var simTrip = "7"
        val refresh: () -> Unit = {
            val p = simPrice.replace(',', '.').toDoubleOrNull() ?: 0.0
            val a = simPick.replace(',', '.').toDoubleOrNull() ?: 0.0
            val b = simTrip.replace(',', '.').toDoubleOrNull() ?: 0.0
            if (p > 0.0 && a + b > 0.0) {
                val o = Offer(p, a, b, 0, 0, true)
                oc.bind(o, Scorer.score(o, Store.config(this)))
                oc.view.visibility = View.VISIBLE
            } else {
                oc.view.visibility = View.INVISIBLE
            }
        }
        simRefresh = refresh

        sim.addView(numberRow("Preț brut (lei)", simPrice) { simPrice = it; refresh() })
        sim.addView(numberRow("Km până la pasager", simPick) { simPick = it; refresh() })
        sim.addView(numberRow("Km cu pasagerul", simTrip) { simTrip = it; refresh() })
        val lpH = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpH.topMargin = dp(10)
        sim.addView(holder, lpH)
        content.addView(sim)
        refresh()

        // costuri
        val costs = scard()
        costs.addView(tv("COSTURI (pentru „net”)", 12f, Ui.MUTED, true))
        costs.addView(settingRow("comm", "Comision platformă (%) — 0 dacă prețul e deja net", 0.0))
        costs.addView(settingRow("cons", "Consum (l sau kWh / 100 km)", 7.0))
        costs.addView(settingRow("eprice", "Preț combustibil / energie (lei)", 10.0))
        costs.addView(settingRow("maint", "Mentenanță + uzură (lei/km)", 0.20))
        costs.addView(settingRow("fix", "Costuri fixe lunare: asigurare, taxe (lei)", 0.0))
        costs.addView(settingRow("kmw", "Km pe săptămână (pt. costuri fixe)", 0.0))
        content.addView(costs)

        // praguri
        val th = scard()
        th.addView(tv("PRAGURI: VERDE / GALBEN / ROȘU / VIOLET", 13f, Ui.BRAND, true))
        th.addView(settingRow("green", "Verde de la (lei/km net)", 2.5))
        th.addView(settingRow("red", "Roșu sub (lei/km net)", 1.8))
        th.addView(settingRow("top", "Violet „DE NERATAT” de la (lei/km net)", 4.5))
        th.addView(settingRow("short", "Cursă scurtă = mereu bună, până la (km total)", 3.0))
        content.addView(th)

        if (Cloud.REQUIRE_INVITE && Cloud.role(this) == "admin") {
            val iv = scard()
            iv.addView(tv("INVITAȚII (doar admin)", 12f, Ui.MUTED, true))
            iv.addView(tv("Fiecare cod merge o singură dată: 7 zile gratuite, apoi 19,9 lei pe lună. Nelimitat e pentru prieteni.", 13f, Ui.TEXT))
            iv.addView(button("Cod nou — 7 zile gratuite") {
                Cloud.createInvite(this, 7, false) { code, msg -> if (code != null) showInvite(code) else Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            })
            iv.addView(button("Cod nou — acces nelimitat") {
                Cloud.createInvite(this, 0, true) { code, msg -> if (code != null) showInvite(code) else Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            })
            iv.addView(button("Cereri de plată") {
                Cloud.listPayments(this) { list, msg ->
                    if (list == null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    else if (list.isEmpty()) Toast.makeText(this, "Nicio cerere în așteptare.", Toast.LENGTH_LONG).show()
                    else {
                        val labels = list.map { it.name + " · " + it.email + " · " + it.ts.take(10) }.toTypedArray()
                        AlertDialog.Builder(this).setTitle("Cereri de plată").setItems(labels) { _, i ->
                            val r = list[i]
                            AlertDialog.Builder(this).setMessage("Ai primit 19,9 lei de la " + r.email + " pe Revolut? Aprobi 30 de zile de acces.")
                                .setPositiveButton("Aprob +30 zile") { _, _ ->
                                    Cloud.approvePayment(this, r.userId, 30) { ok, m ->
                                        Toast.makeText(this, if (ok) "Acces prelungit cu 30 de zile." else m, Toast.LENGTH_LONG).show()
                                    }
                                }.setNegativeButton("Anulează", null).show()
                        }.setNegativeButton("Închide", null).show()
                    }
                }
            })
            content.addView(iv)
        }
        val gd = scard()
        gd.addView(tv("AJUTOR", 13f, Ui.BRAND, true))
        gd.addView(button("Ghid de activare (pas cu pas)") { showGuide = true; render() })
        gd.addView(tv("Versiunea instalată: " + Updater.currentName(this), 12f, Ui.MUTED))
        val crash = Store.raw(this, "last_crash") ?: ""
        if (crash.isNotEmpty()) {
            gd.addView(tv("Ultima eroare a aplicației (trimite-o dezvoltatorului):\n" + crash, 10f, Ui.AMBER))
            gd.addView(button("Șterge mesajul de eroare") { Store.setRaw(this, "last_crash", ""); render() })
        }
        if (Cloud.loggedIn(this)) {
            gd.addView(tv("Cont: " + Cloud.email(this) + (if (Cloud.role(this) == "admin") " · admin" else ""), 12f, Ui.MUTED))
            gd.addView(button("Reîmprospătează contul (rol și acces)") {
                Cloud.refreshAccessAsync(this, true) {
                    Toast.makeText(this, "Cont actualizat.", Toast.LENGTH_SHORT).show()
                    render()
                }
            })
        }
        gd.addView(button("Caută actualizări") {
            Updater.check(this, true) {
                if (Updater.available(this) != null) render()
                else Toast.makeText(this, "Ai deja ultima versiune.", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(gd)
        val asp = scard()
        asp.addView(tv("ASPECT", 13f, Ui.BRAND, true))
        val light = Store.raw(this, "theme") == "light"
        asp.addView(button(if (light) "Temă luminoasă — treci pe întunecată" else "Temă întunecată — treci pe luminoasă") {
            Store.setRaw(this, "theme", if (light) "dark" else "light")
            recreate()
        })
        val chips = LinearLayout(this)
        chips.setPadding(0, dp(10), 0, 0)
        val curKey = Store.raw(this, "accent") ?: "green"
        for ((nm, key) in listOf("Verde" to "green", "Albastru" to "blue", "Roșu" to "red", "Mov" to "purple")) {
            val t = tv((if (key == curKey) "✓ " else "") + nm, 13f, Ui.onAccentOf(key, light), true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4), dp(12), dp(4), dp(12))
            t.background = Ui.rounded(Ui.accentOf(key, light), dp(12).toFloat())
            t.setOnClickListener { Store.setRaw(this, "accent", key); recreate() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            chips.addView(t, lp)
        }
        asp.addView(chips)
        content.addView(asp)
        val au = scard()
        au.addView(tv("ALERTE ȘI CARD PESTE APLICAȚIA DE RIDESHARING", 13f, Ui.BRAND, true))
        au.addView(tv("Când se salvează o cursă: doar după ce platforma ți-o atribuie (apare ecranul cursei active). Nu se salvează ofertele respinse, cele luate de alt șofer, cele anulate de pasager sau anulate de tine.", 13f, Ui.TEXT))
        val alOn = Store.raw(this, "alert_top") != "0"
        au.addView(button(if (alOn) "Alertă „DE NERATAT” (vibrație + sunet): PORNITĂ" else "Alertă „DE NERATAT”: OPRITĂ") {
            Store.setRaw(this, "alert_top", if (alOn) "0" else "1")
            render()
        })
        val ptOn = Store.raw(this, "pass_touch") != "0"
        au.addView(tv("Cardul peste aplicația de ridesharing: " + (if (ptOn) "lasă atingerea să treacă prin el, deci poți apăsa „Refuză” sau „Acceptă” sub card. Apăsarea lungă pe card nu mai merge." else "prinde atingerea; apăsarea lungă pe card salvează cursa manual, dar poate acoperi butoanele din aplicația de ridesharing."), 13f, Ui.TEXT))
        au.addView(button(if (ptOn) "Atingere prin card: PORNITĂ — apasă ca s-o oprești" else "Atingere prin card: OPRITĂ — apasă ca s-o pornești") {
            Store.setRaw(this, "pass_touch", if (ptOn) "0" else "1")
            render()
        })
        content.addView(au)

        // diagnostic
        val diag = scard()
        diag.addView(tv("DIAGNOSTIC", 13f, Ui.BRAND, true))
        diag.addView(tv("Apasă butonul ca să faci un fișier .txt cu tot ce trebuie (versiune, setări, ultimele curse, jurnal de evenimente, ultimul ecran din aplicația de ridesharing) și să-l trimiți pe WhatsApp sau email.", 12f, Ui.MUTED))
        diag.addView(button("Generează diagnostic (fișier .txt)") {
            try { Diag.share(this) } catch (e: Exception) { Toast.makeText(this, "Nu am putut crea fișierul.", Toast.LENGTH_LONG).show() }
        })
        diag.addView(button("Copiază textul diagnosticului") {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DBX Pulse diagnostic", Diag.build(this)))
                Toast.makeText(this, "Copiat. Lipește-l într-un mesaj.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Toast.makeText(this, "Nu am putut copia.", Toast.LENGTH_LONG).show() }
        })
        val d = Store.dump(this)
        val dv = tv(
            if (d.isEmpty()) "Încă nu am citit nimic din aplicația de ridesharing. Deschide o ofertă cu serviciul activ."
            else "Ultimul text citat din aplicația de ridesharing:\n\n" + d,
            12f, Ui.MUTED
        )
        dv.setTextIsSelectable(true)
        dv.setPadding(0, dp(6), 0, 0)
        diag.addView(dv)
        content.addView(diag)

        val dt = scard()
        dt.addView(tv("DATE", 13f, Ui.BRAND, true))
        dt.addView(tv("Șterge toate cursele salvate pe acest telefon. Nu se poate anula.", 12f, Ui.MUTED))
        val del = button("Șterge tot istoricul de curse") {
            AlertDialog.Builder(this).setTitle("Ștergi tot istoricul?")
                .setMessage("Se șterg toate cursele (și cele marcate anulate) și kilometrii în gol. Nu se poate anula.")
                .setPositiveButton("Șterge tot") { _, _ -> Store.clearHistory(this); render() }
                .setNegativeButton("Nu", null).show()
        }
        dt.addView(del)
        content.addView(dt)
    }

    private fun numberField(initial: String, onChange: (String) -> Unit): EditText {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(initial)
        et.setTextColor(Ui.TEXT)
        et.textSize = 16f
        et.gravity = Gravity.END
        et.minWidth = dp(90)
        et.setSingleLine(true)
        et.background = Ui.rounded(Ui.SURFACE2, dp(12).toFloat())
        et.setPadding(dp(12), dp(8), dp(12), dp(8))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return et
    }

    private fun numberRow(label: String, initial: String, onChange: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val l = tv(label, 14f, Ui.TEXT)
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(numberField(initial, onChange))
        return row
    }

    private fun settingRow(key: String, label: String, def: Double): LinearLayout {
        val saved = Store.raw(this, key)
        val initial = saved ?: (if (def == Math.floor(def)) def.toLong().toString() else def.toString())
        return numberRow(label, initial) { v ->
            Store.setRaw(this, key, v)
            simRefresh?.invoke()
        }
    }
}
