package ro.drivescore

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Context
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
    private lateinit var tabsBar: LinearLayout
    private val navTabs = ArrayList<TextView>()
    private var askedNotif = false
    private var showAllRides = false
    private var showGuide = false
    private var zoneMap: MapView? = null
    private var tab = 0
    private var loginCreate = false
    private var simRefresh: (() -> Unit)? = null

    /** Cum îl numim pe șofer: nickname-ul, altfel emailul fără @gmail.com. */
    private fun displayName(): String =
        Cloud.name(this).ifEmpty { Store.raw(this, "pending_nick") ?: "" }.ifEmpty { Cloud.email(this).substringBefore('@') }.ifEmpty { "șofer" }

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
        window.navigationBarColor = Ui.SURFACE
        if (Ui.light) {
            var fl = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) fl = fl or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = fl
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        // starea serviciului (apare pe Acasă)
        statusView = tv("", 13f, Ui.MUTED, true)
        statusView.setPadding(dp(12), dp(7), dp(12), dp(7))
        statusView.setOnClickListener {
            if (!serviceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // meniul de jos: Acasă / Curse / Zone / Bani / Mai mult
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(4), dp(6), dp(4), dp(8))
        navTabs.clear()
        navTabs.add(tabView("Acasă", R.drawable.ic_nav_home) { tab = 0; render() })
        navTabs.add(tabView("Curse", R.drawable.ic_nav_list) { tab = 6; render() })
        navTabs.add(tabView("Zone", R.drawable.ic_nav_zone) { tab = 1; render() })
        navTabs.add(tabView("Bani", R.drawable.ic_nav_money) { tab = 4; render() })
        navTabs.add(tabView("Mai mult", R.drawable.ic_nav_more) { tab = 7; render() })
        for (t in navTabs) tabs.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setBackgroundColor(Ui.SURFACE)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        val line = View(this)
        line.setBackgroundColor(Ui.SURFACE2)
        bar.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        bar.addView(tabs)
        tabsBar = bar

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(16), dp(16), dp(24))
        val scroll = ScrollView(this)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar)

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
        val on = serviceEnabled()
        statusView.text = if (on) "●  Activ — cardul apare automat" else "●  Dezactivat — apasă și activează „DBX Pulse” la Accesibilitate"
        statusView.setTextColor(Color.parseColor(if (on) "#4ADE9B" else "#F5B84A"))
        statusView.background = Ui.rounded(Color.parseColor("#B006120C"), dp(14).toFloat(), Color.parseColor(if (on) "#554ADE9B" else "#55F5B84A"), dp(1))
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
            renderLogin()
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
        val ni = when (tab) { 0 -> 0; 6 -> 1; 1 -> 2; 4 -> 3; else -> 4 }
        for (i in navTabs.indices) styleTab(navTabs[i], i == ni)
        zoneMap?.onDetach()
        zoneMap = null
        content.removeAllViews()
        updateCard(false)?.let { content.addView(it) }
        when (tab) {
            0 -> renderHome()
            6 -> renderRides()
            7 -> renderMore()
            8 -> renderAppearance()
            9 -> renderWheel()
            10 -> renderFriends()
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
    private fun dayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Titlul paginii (ca în designul nou): rând mic deasupra, titlu mare dedesubt. */
    private fun pageHeader(sub: String, title: String, back: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), 0, 0, dp(14))
        if (back != null) {
            val b = tv("‹", 26f, Ui.TEXT, true)
            b.gravity = Gravity.CENTER
            b.background = Ui.rounded(Ui.SURFACE, dp(19).toFloat(), Ui.SURFACE2, dp(1))
            b.setOnClickListener { back() }
            val blp = LinearLayout.LayoutParams(dp(38), dp(38)); blp.rightMargin = dp(12)
            row.addView(b, blp)
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (sub.isNotEmpty()) col.addView(tv(sub, 12f, Ui.MUTED, true))
        col.addView(tv(title, 24f, Ui.TEXT, true))
        row.addView(col)
        return row
    }

    // ---------- ACASĂ ----------
    private fun renderHome() {
        val all = Store.historyAll(this)
        val rides = all.filter { it.canceled == 0 }
        val ds = dayStart()
        val today = rides.filter { it.ts >= ds }
        val spent = Expenses.all(this).filter { it.ts >= ds && !it.fuel }.sumOf { it.amount }
        val tips = Store.tipsToday(this)
        val net = today.sumOf { it.net }
        val profit = net + tips - spent

        content.addView(homeHero())
        accessCard()?.let { content.addView(it) }
        homeWheelCard()?.let { content.addView(it) }

        // profit azi
        val c = card()
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        hc.addView(tv("Profit azi", 12f, Ui.MUTED, true))
        val big = LinearLayout(this)
        big.gravity = Gravity.BOTTOM
        big.addView(tv(Fmt.d2(profit), 34f, Ui.TEXT, true))
        val lei = tv("  lei", 15f, Ui.MUTED, true)
        big.addView(lei)
        hc.addView(big)
        head.addView(hc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv(SimpleDateFormat("d MMM", Locale.forLanguageTag("ro-RO")).format(Date()), 12f, Ui.MUTED))
        c.addView(head)
        val km = today.sumOf { it.totalKm }
        val tiles = LinearLayout(this)
        tiles.setPadding(0, dp(10), 0, 0)
        fun mini(label: String, value: String, last: Boolean) {
            val m = LinearLayout(this)
            m.orientation = LinearLayout.VERTICAL
            m.background = Ui.rounded(Ui.BG, dp(12).toFloat())
            m.setPadding(dp(10), dp(8), dp(10), dp(8))
            m.addView(tv(label, 11f, Ui.MUTED))
            m.addView(tv(value, 15f, Ui.TEXT, true))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (!last) lp.rightMargin = dp(8)
            tiles.addView(m, lp)
        }
        mini("Curse", today.size.toString(), false)
        mini("Net curse", Fmt.d2(net), false)
        mini("Tips", Fmt.d2(tips), false)
        mini("Cheltuieli", Fmt.d2(spent), true)
        c.addView(tiles)
        val nt = tv("Net/km azi: " + Fmt.d2(if (km > 0) net / km else 0.0) + " lei · " + Fmt.d1(km) + " km. Combustibilul e deja scăzut din net, de aceea nu îl scad încă o dată la cheltuieli.", 11f, Ui.MUTED)
        nt.setPadding(0, dp(8), 0, 0)
        c.addView(nt)
        content.addView(c)

        content.addView(tipsCard())
        content.addView(emptyKmCard(today, rides))

        if (all.isNotEmpty()) {
            val last = all.first()
            val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale.forLanguageTag("ro-RO"))
            val lc = card()
            val lh = LinearLayout(this)
            lh.gravity = Gravity.CENTER_VERTICAL
            lh.addView(tv("Ultima cursă", 12f, Ui.MUTED, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val more = tv("Toate cursele →", 13f, Ui.BRAND, true)
            more.setOnClickListener { tab = 6; render() }
            lh.addView(more)
            lc.addView(lh)
            lc.addView(tv(Fmt.d2(last.netPerKm) + " lei/km net  ·  " + Fmt.d1(last.totalKm) + " km", 16f, Ui.TEXT, true))
            lc.addView(tv(fmt.format(Date(last.ts)) + "  ·  net " + Fmt.d2(last.net) + " lei din " + Fmt.d2(last.price), 12f, Ui.MUTED))
            content.addView(lc)
        }
    }

    private fun homeHero(): FrameLayout {
        val f = FrameLayout(this)
        f.background = Ui.rounded(Ui.D_BG, dp(22).toFloat())
        f.clipToOutline = true
        val img = ImageView(this)
        img.setImageResource(R.drawable.home_hero)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        f.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val shade = View(this)
        shade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#F006120C"), Color.parseColor("#7006120C"), Color.TRANSPARENT))
        f.addView(shade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(16), dp(18), dp(16))
        col.gravity = Gravity.BOTTOM
        val nm = displayName()
        col.addView(tv("Salut,", 14f, Ui.D_MUTED))
        col.addView(tv(nm, 28f, Ui.D_TEXT, true))
        val st = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        st.topMargin = dp(10)
        (statusView.parent as? android.view.ViewGroup)?.removeView(statusView)
        col.addView(statusView, st)
        f.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(210))
        lp.bottomMargin = dp(12)
        f.layoutParams = lp
        return f
    }

    // ---------- CURSE ----------
    private fun renderRides() {
        val all = Store.historyAll(this)           // lista arată și cursele anulate
        val rides = all.filter { it.canceled == 0 } // AZI numără doar cursele valabile
        val today = rides.filter { it.ts >= dayStart() }

        content.addView(pageHeader("Istoric", "Curse"))
        content.addView(summaryCard("AZI", today))

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
        if (all.size > 30) {
            val allBtn = tv(if (showAllRides) "Mai puține" else "Vezi toate →", 14f, Ui.BRAND, true)
            allBtn.setOnClickListener { showAllRides = !showAllRides; render() }
            hd.addView(allBtn)
        }
        content.addView(hd)
        for (r in all.take(if (showAllRides) 300 else 30)) {
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

    /** Sumele rapide pentru tips (se pot schimba din Acasă). Standard: 1, 2, 3, 5. */
    private fun tipAmounts(): List<Double> {
        val raw = Store.raw(this, "tip_amounts") ?: "1,2,3,5"
        val l = raw.split(',', ';', ' ').mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it > 0.0 && it <= 1000.0 }.distinct().take(6)
        return if (l.isEmpty()) listOf(1.0, 2.0, 3.0, 5.0) else l
    }

    private fun amountLabel(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else Fmt.d2(v)

    private fun addTipNow(v: Double) {
        Store.addTips(this, v)
        Store.setRaw(this, "tip_last", v.toString())
        Toast.makeText(this, "+" + amountLabel(v) + " lei tips", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun askText(title: String, hint: String, initial: String, number: Boolean, onOk: (String) -> Unit) {
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.hint = hint
        e.setText(initial)
        e.inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        box.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(if (number) e.text.toString().trim().replace(',', '.') else e.text.toString().trim()) }
            .setNegativeButton("Anulează", null).show()
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
        c.addView(tv(Fmt.d2(Store.tipsToday(this)) + " lei", 26f, Ui.TEXT, true))
        c.addView(tv("Total tips: " + Fmt.d2(Store.tipsTotal(this)) + " lei · se adună și la Bani, azi se pune pe 0 la 00:00", 12f, Ui.MUTED))

        val row = LinearLayout(this)
        row.setPadding(0, dp(10), 0, 0)
        for (v in tipAmounts()) {
            val b = tv("+" + amountLabel(v), 18f, Ui.ON_ACCENT, true)
            b.gravity = Gravity.CENTER
            b.background = Ui.rounded(Ui.BRAND, dp(14).toFloat())
            b.setOnClickListener { addTipNow(v) }
            val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            row.addView(b, lp)
        }
        c.addView(row)

        val row2 = LinearLayout(this)
        row2.gravity = Gravity.CENTER_VERTICAL
        row2.setPadding(0, dp(10), 0, 0)
        val other = tv("Altă sumă", 13f, Ui.BRAND, true)
        other.setOnClickListener {
            askText("Tips primit (lei)", "ex. 7,5", "", true) { t ->
                val v = t.toDoubleOrNull()
                if (v == null || v <= 0.0) Toast.makeText(this, "Scrie suma primită.", Toast.LENGTH_SHORT).show() else addTipNow(v)
            }
        }
        row2.addView(other, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val last = Store.raw(this, "tip_last")?.toDoubleOrNull() ?: 0.0
        if (last > 0.0 && Store.tipsToday(this) + 0.0001 >= last) {
            val undo = tv("Anulează ultimul (−" + amountLabel(last) + ")", 13f, Ui.RED, true)
            undo.setOnClickListener {
                Store.addTips(this, -last)
                Store.setRaw(this, "tip_last", "0")
                render()
            }
            row2.addView(undo)
        }
        c.addView(row2)

        val edit = tv("Schimbă sumele rapide", 12f, Ui.MUTED)
        edit.setPadding(0, dp(8), 0, 0)
        edit.setOnClickListener {
            val cur = tipAmounts().joinToString(", ") { amountLabel(it) }
            askText("Sume rapide pentru tips", "ex. 1, 2, 3, 5", cur, false) { t ->
                val ok = t.split(',', ';', ' ').any { it.trim().toDoubleOrNull() != null }
                if (!ok) Toast.makeText(this, "Scrie sume separate prin virgulă, ex. 1, 2, 3, 5 (zecimale cu punct).", Toast.LENGTH_LONG).show()
                else { Store.setRaw(this, "tip_amounts", t.replace(" ", ",")); render() }
            }
        }
        c.addView(edit)
        return c
    }

    private fun emptyKmCard(today: List<Ride>, all: List<Ride>): LinearLayout {
        val c = card()
        val on = Store.shiftOn(this)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("TURA DE AZI", 12f, Ui.MUTED, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pill = tv(if (on) "● PORNITĂ" else "● OPRITĂ", 11f, if (on) Ui.GREEN else Ui.AMBER, true)
        pill.setPadding(dp(10), dp(4), dp(10), dp(4))
        pill.background = Ui.rounded(((if (on) Ui.GREEN else Ui.AMBER) and 0x00FFFFFF) or 0x22000000, dp(10).toFloat())
        head.addView(pill)
        c.addView(head)

        val trk = Store.trackedToday(this)
        val empty = Store.emptyToday(this)
        val inRide = maxOf(0.0, trk - empty)
        val row = LinearLayout(this)
        row.setPadding(0, dp(12), 0, 0)
        fun big(label: String, value: Double, accent: Boolean, last: Boolean) {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER_HORIZONTAL
            t.background = Ui.rounded(if (accent) Ui.SURFACE2 else Ui.BG, dp(14).toFloat())
            t.setPadding(dp(6), dp(14), dp(6), dp(14))
            t.addView(tv(label, 12f, Ui.MUTED, true))
            val v = tv(Fmt.d1(value), 28f, Ui.TEXT, true)
            v.setPadding(0, dp(4), 0, 0)
            t.addView(v)
            t.addView(tv("km", 12f, Ui.MUTED))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (!last) lp.rightMargin = dp(8)
            row.addView(t, lp)
        }
        big("În gol", empty, false, false)
        big("În cursă", inRide, false, false)
        big("Total", trk, true, true)
        c.addView(row)

        val st = tv(if (on) "Tură pornită — se numără km cât aplicația de ridesharing e deschisă." else "Tură oprită — km rămân salvați, se continuă de unde au rămas.", 12f, if (on) Ui.GREEN else Ui.AMBER)
        st.setPadding(0, dp(10), 0, 0)
        c.addView(st)
        c.addView(button(if (on) "Oprește tura" else "Pornește tura") { Store.setShift(this, !on); render() })
        c.addView(tv("Se pun pe 0 la 00:00. Kilometrii cursei acceptate nu se numără ca „în gol”, iar valorile nu scad niciodată singure.", 11f, Ui.MUTED))
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

    // ---------- CHELTUIELI ----------
    private fun numField(hint: String): EditText {
        val e = textField(hint, false)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return e
    }

    private fun dbl(e: EditText): Double = e.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun renderExpenses() {
        content.addView(pageHeader("Venituri și costuri", "Bani"))
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
        val r1b = LinearLayout(this)
        r1b.setPadding(0, dp(8), 0, 0)
        r1b.addView(stat("TIPS AZI", Fmt.d2(Store.tipsToday(this)) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1b.addView(stat("TIPS TOTAL", Fmt.d2(Store.tipsTotal(this)) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r1b.addView(stat("NET CURSE", Fmt.d2(netM) + " lei"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sum.addView(r1b)
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
        content.addView(pageHeader("Mai mult", "Acte", { tab = 7; render() }))
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
        b.background = Ui.rounded(Ui.ACCENT, dp(16).toFloat())
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
        content.addView(pageHeader("Unde e cerere acum", "Zone"))
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
        // harta rămâne cu culorile normale în orice temă (inversarea o făcea urâtă)
        map.controller.setZoom(13.5)
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
        mc.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420)))
        val legend = tv("Cercurile mari și roșii = cerere mare. Cele fierbinți pulsează: cu cât mai repede, cu atât mai multă cerere. Zonele au ~500 m. Harta are nevoie de internet.", 11f, Ui.MUTED)
        legend.setPadding(0, dp(8), 0, 0)
        mc.addView(legend)
        content.addView(mc)

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
        val others = Zones.distinct(zones.drop(1), 0.8)
        if (others.isNotEmpty()) {
            val oh = tv("Alte zone care merită", 13f, Ui.TEXT, true)
            oh.setPadding(0, dp(14), 0, dp(4))
            bc.addView(oh)
            for (i in 1..minOf(others.size, 2)) {
                val z = others[i - 1]
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

        addZoneData(events)
    }

    private fun addZoneData(events: List<GeoEvent>) {
        val c = card()
        c.addView(tv("DATE ADUNATE", 12f, Ui.MUTED, true))
        val o = events.filter { it.kind == 0 }.sumOf { it.weight }
        val r = events.filter { it.kind == 1 }.sumOf { it.weight }
        c.addView(tv("$o oferte văzute, $r curse acceptate cu locație (tu + comunitatea)", 13f, Ui.TEXT))
        c.addView(button("Șterge doar datele mele de zone") {
            AlertDialog.Builder(this)
                .setMessage("Ștergi ofertele văzute de pe telefonul tău, folosite pentru zone? Datele celorlalți șoferi (comunitatea) și cursele din istoric rămân.")
                .setPositiveButton("Șterge") { _, _ -> Store.clearOffers(this); render() }
                .setNegativeButton("Anulează", null)
                .show()
        })
        if (Cloud.role(this) == "admin") {
            val adm = button("Admin: șterge datele de zone ale comunității") {
                AlertDialog.Builder(this)
                    .setTitle("Ștergi datele tuturor șoferilor?")
                    .setMessage("Se șterg din cloud toate ofertele și cursele cu locație ale TUTUROR utilizatorilor. Nu se poate anula.")
                    .setPositiveButton("Continuă") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("Ești sigur?")
                            .setMessage("Ultima confirmare: ștergi datele de zone ale comunității.")
                            .setPositiveButton("Șterge tot") { _, _ ->
                                Cloud.adminClearEvents(this) { ok, msg ->
                                    if (ok) Cloud.syncAsync(this, true) { }
                                    refreshAfter(msg)
                                }
                            }
                            .setNegativeButton("Nu", null).show()
                    }
                    .setNegativeButton("Anulează", null).show()
            }
            adm.background = Ui.rounded(Ui.RED, dp(16).toFloat())
            c.addView(adm)
        }
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
    private val CONTACT_WA = "40750209081"
    private val SITE_URL = "https://dbxpulse.netlify.app"

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
        if (showGuide) { showGuide = false; render() } else if (tab == 10) { tab = 9; render() } else if (tab == 2 || tab == 3 || tab == 5 || tab == 8 || tab == 9) { tab = 7; render() } else if (tab != 0) { tab = 0; render() } else super.onBackPressed()
    }

    private fun renderJoin() {
        content.addView(authHero(R.drawable.home_hero, 150))
        val c = card()
        c.addView(tv("Mai ai un pas", 22f, Ui.TEXT, true))
        c.addView(tv("Salut, " + displayName() + "! Emailul tău e confirmat. Scrie codul de invitație primit de la administrator sau codul unui prieten care folosește deja aplicația.", 13f, Ui.MUTED))
        val err = Store.raw(this, "join_error") ?: ""
        if (err.isNotEmpty()) c.addView(tv(err, 13f, Ui.RED, true))
        val nameF = textField("Numele sau nickname-ul tău", false)
        nameF.setText(Store.raw(this, "pending_nick") ?: "")
        val codeF = textField("Cod de invitație sau cod de prieten", false)
        c.addView(codeF)
        c.addView(nameF)
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
        c.addView(tv("Fără cod? Cere-l pe WhatsApp la +40 750 209 081.", 12f, Ui.MUTED))
        c.addView(button("Scrie-mi pe WhatsApp") { openWhatsApp() })
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

    /** Imaginea de sus a ecranelor de intrare (rotunjită, la rezoluție completă). */
    private fun authHero(res: Int, heightDp: Int = 0): FrameLayout {
        val f = FrameLayout(this)
        f.background = Ui.rounded(Ui.D_BG, dp(22).toFloat())
        f.clipToOutline = true
        val img = ImageView(this)
        img.setImageResource(res)
        if (heightDp > 0) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            f.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(heightDp)))
        } else {
            img.adjustViewBounds = true
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            f.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        f.layoutParams = lp
        return f
    }

    private fun renderLogin() {
        content.addView(authHero(R.drawable.login_hero))
        val c = card()

        // Am cont / Cont nou
        val seg = LinearLayout(this)
        seg.background = Ui.rounded(Ui.SURFACE2, dp(14).toFloat())
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((create, nm) in listOf(false to "Am cont", true to "Cont nou")) {
            val on = create == loginCreate
            val t = tv(nm, 14f, if (on) Ui.BRAND else Ui.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(11), 0, dp(11))
            if (on) t.background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
            t.setOnClickListener { loginCreate = create; render() }
            seg.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(seg)

        val remember = Store.raw(this, "remember_me") != "0"
        val nickF = textField("Nume sau nickname", false)
        val emailF = textField("Email", false, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passF = textField("Parolă (minim 6 caractere)", false, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (loginCreate) c.addView(nickF)
        c.addView(emailF)
        c.addView(passF)
        if (!loginCreate && remember) emailF.setText(Store.raw(this, "last_email") ?: "")

        if (!loginCreate) {
            val rm = tv((if (remember) "☑  " else "☐  ") + "Ține-mă minte (rămâne emailul)", 13f, Ui.TEXT)
            rm.setPadding(dp(2), dp(12), 0, dp(2))
            rm.setOnClickListener { Store.setRaw(this, "remember_me", if (remember) "0" else "1"); render() }
            c.addView(rm)
        }

        fun go(create: Boolean) {
            val em = emailF.text.toString().trim()
            val pw = passF.text.toString()
            if (em.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this, "Completează emailul și parola.", Toast.LENGTH_SHORT).show()
                return
            }
            if (create && nickF.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "Scrie un nume sau nickname.", Toast.LENGTH_SHORT).show()
                return
            }
            if (create) {
                // codul de invitație se cere după confirmarea emailului
                Store.setRaw(this, "pending_nick", nickF.text.toString().trim())
                Store.setRaw(this, "pending_code", "")
            }
            Store.setRaw(this, "join_error", "")
            if (Store.raw(this, "remember_me") != "0") Store.setRaw(this, "last_email", em) else Store.setRaw(this, "last_email", "")
            Cloud.signIn(this, em, pw, create) { ok, msg ->
                if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                else if (create && msg.startsWith("Cont creat")) loginCreate = false
                refreshAfter(msg)
            }
        }
        if (loginCreate) {
            val n = tv("Cont nou: primești 7 zile gratuite cu un cod de invitație, apoi 19,9 lei pe lună. Codul îl scrii după ce îți confirmi emailul.", 12f, Ui.MUTED)
            n.setPadding(0, dp(10), 0, 0)
            c.addView(n)
            c.addView(button("Creez cont") { go(true) })
        } else {
            c.addView(button("Intru în cont") { go(false) })
        }
        c.addView(button("Ghid de activare") { showGuide = true; render() })
        c.addView(tv("Prin „Creez cont” ești de acord ca locația (unde apare oferta), ora, prețul și km ale ofertelor tale să fie trimise în contul comun. Ceilalți șoferi văd doar zone agregate, fără nume sau ID, iar administratorul aplicației poate vedea datele din cont. Destinația cursei nu se trimite. Îți poți șterge datele oricând din Setări.", 11f, Ui.MUTED))
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

    private fun openWhatsApp() {
        val txt = "Salut, scriu din DBX Pulse " + Updater.currentName(this) + ". "
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + CONTACT_WA + "?text=" + Uri.encode(txt))))
        } catch (e: Exception) {
            Toast.makeText(this, "Nu am putut deschide WhatsApp. Numărul: +40 750 209 081", Toast.LENGTH_LONG).show()
        }
    }

    /** Pagina „Contact”: WhatsApp (direct) și email. */
    private fun renderContact() {
        content.addView(pageHeader("Mai mult", "Contact", { tab = 7; render() }))
        val ct = card()
        ct.addView(tv("Ai o idee, ai găsit o problemă sau ceva nu merge cum te aștepți? Scrie-mi. Aplicația o fac împreună cu voi, șoferii, și fiecare sugestie ajută.", 14f, Ui.TEXT))
        val ct2 = tv("Pentru o problemă, spune-mi pe ce telefon ești și ce ai văzut pe ecran. O captură mă ajută mult.", 13f, Ui.MUTED)
        ct2.setPadding(0, dp(6), 0, 0)
        ct.addView(ct2)
        content.addView(ct)

        val wa = card()
        wa.addView(tv("WHATSAPP", 12f, Ui.MUTED, true))
        val num = tv("+40 750 209 081", 20f, Ui.TEXT, true)
        num.setPadding(0, dp(6), 0, dp(2))
        wa.addView(num)
        wa.addView(tv("Te duce direct în conversație. Răspund cât de repede pot.", 13f, Ui.MUTED))
        wa.addView(button("Scrie-mi pe WhatsApp") { openWhatsApp() })
        content.addView(wa)

        val ec = card()
        ec.addView(tv("EMAIL", 12f, Ui.MUTED, true))
        val em = tv(CONTACT_EMAIL, 17f, Ui.BRAND, true)
        em.setPadding(0, dp(6), 0, dp(2))
        em.setOnClickListener { copyEmail() }
        ec.addView(em)
        ec.addView(button("Scrie-mi un email") { contactMail() })
        content.addView(ec)
    }

    // ---------- ROATA NOROCULUI ----------
    private fun roDate(iso: String?): String {
        if (iso.isNullOrEmpty() || iso == "null") return "—"
        return try {
            val ms = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ro-RO")).format(Date(ms))
        } catch (e: Exception) { "—" }
    }

    private fun wheelRules() {
        val t = "ȘANSE (la fiecare învârtire)\n" +
            "• Mai încearcă: 60%\n• +1 zi: 25%\n• +3 zile: 10%\n• +7 zile: 3%\n• +30 de zile: 2%\n\n" +
            "CUM CÂȘTIGI ÎNVÂRTIRI\n" +
            "• 1 învârtire când plata ta lunară e confirmată\n" +
            "• 1 învârtire când un prieten invitat de tine are prima plată confirmată (o dată per prieten)\n" +
            "• 1 învârtire de bun venit, la crearea contului\n\n" +
            "REGULI\n" +
            "• Rezultatul îl alege serverul, nu telefonul.\n" +
            "• Zilele câștigate se adaugă peste accesul pe care îl ai deja.\n" +
            "• Se câștigă doar zile de acces, nu bani.\n" +
            "• Premiul de +30 de zile se poate da de cel mult 2 ori pe lună în toată comunitatea; dacă s-a epuizat, premiul devine +7 zile.\n" +
            "• Învârtirile nefolosite rămân în contul tău.\n" +
            "• DBX Pulse poate modifica regulile; se aplică cele din versiunea curentă a aplicației."
        android.app.AlertDialog.Builder(this).setTitle("Șanse și regulament").setMessage(t).setPositiveButton("Am înțeles", null).show()
    }

    /** Cardul de pe Acasă: apare doar când ai cel puțin o învârtire. */
    private fun homeWheelCard(): LinearLayout? {
        val n = Cloud.spins(this)
        if (n <= 0 || !Cloud.joined(this)) return null
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = Ui.rounded(Ui.ACCENT, dp(18).toFloat())
        r.setPadding(dp(16), dp(14), dp(14), dp(14))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv("ROATA NOROCULUI", 11f, Ui.ON_ACCENT, true))
        col.addView(tv(if (n == 1) "Ai 1 învârtire!" else "Ai $n învârtiri!", 18f, Ui.ON_ACCENT, true))
        col.addView(tv("Poți câștiga până la 30 de zile", 12f, Ui.ON_ACCENT))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val b = tv("Învârte", 13f, Ui.ACCENT, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), dp(9), dp(16), dp(9))
        b.background = Ui.rounded(Ui.ON_ACCENT, dp(20).toFloat())
        r.addView(b)
        r.setOnClickListener { tab = 9; render() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        r.layoutParams = lp
        return r
    }

    private fun renderWheel() {
        content.addView(pageHeader("Mai mult", "Roata norocului", { tab = 7; render() }))
        val sc = card()
        val spinsT = tv(if (Cloud.spins(this) == 1) "Ai 1 învârtire" else "Ai ${Cloud.spins(this)} învârtiri", 16f, Ui.TEXT, true)
        sc.addView(spinsT)
        sc.addView(tv("Se adună din lunile plătite, prieteni și bun venit.", 12f, Ui.MUTED))
        content.addView(sc)

        val resultBox = LinearLayout(this)
        resultBox.orientation = LinearLayout.VERTICAL
        content.addView(resultBox)

        val wheel = WheelView(this)
        val wlp = LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT)
        wlp.gravity = Gravity.CENTER_HORIZONTAL
        wlp.topMargin = dp(6)
        content.addView(wheel, wlp)

        lateinit var btn: TextView
        fun setSpinsText(n: Int) {
            spinsT.text = if (n == 1) "Ai 1 învârtire" else "Ai $n învârtiri"
            btn.alpha = if (n > 0) 1f else 0.45f
        }
        btn = button("Învârte roata") {
            if (wheel.spinning) return@button
            if (Cloud.spins(this) <= 0) {
                Toast.makeText(this, "Nu ai învârtiri acum. Vezi mai jos cum câștigi.", Toast.LENGTH_LONG).show()
                return@button
            }
            btn.isEnabled = false
            resultBox.removeAllViews()
            Cloud.spin(this) { o, msg ->
                if (o == null) {
                    btn.isEnabled = true
                    Toast.makeText(this, if (msg.startsWith("Eroare")) "Nu am putut învârti acum ($msg). Încearcă din nou." else msg, Toast.LENGTH_LONG).show()
                } else {
                    val prize = o.optInt("prize_days", 0)
                    wheel.spinTo(prize) {
                        btn.isEnabled = true
                        setSpinsText(o.optInt("spins_left", 0))
                        val rc = card()
                        rc.background = Ui.rounded(Ui.SURFACE, dp(18).toFloat(), if (prize > 0) Ui.GREEN else Ui.SURFACE2, dp(2))
                        if (prize > 0) {
                            rc.addView(tv("AI CÂȘTIGAT", 12f, Ui.MUTED, true))
                            rc.addView(tv("+$prize " + (if (prize == 1) "zi gratuită" else "zile gratuite"), 26f, Ui.GREEN, true))
                            if (o.optBoolean("test", false)) {
                                rc.addView(tv("Test (cont admin/nelimitat): zilele nu se adaugă.", 12f, Ui.MUTED))
                            } else {
                                rc.addView(tv("Accesul tău acum: până la " + roDate(o.optString("after")), 15f, Ui.TEXT, true))
                                rc.addView(tv("Înainte: " + roDate(o.optString("before")) + ". Zilele se adaugă peste ce aveai deja.", 12f, Ui.MUTED))
                            }
                        } else {
                            rc.addView(tv("Mai încearcă", 22f, Ui.TEXT, true))
                            rc.addView(tv("De data asta nu a ieșit nimic. Data viitoare!", 13f, Ui.MUTED))
                        }
                        resultBox.addView(rc)
                        try { (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(if (prize > 0) 220L else 60L) } catch (e: Exception) { }
                        Cloud.refreshAccessAsync(this, true) { }
                    }
                }
            }
        }
        content.addView(btn)
        val rules = tv("Șanse și regulament", 13f, Ui.BRAND, true)
        rules.gravity = Gravity.CENTER
        rules.setPadding(0, dp(12), 0, dp(12))
        rules.setOnClickListener { wheelRules() }
        content.addView(rules)

        val hc = card()
        hc.addView(tv("CUM CÂȘTIGI ÎNVÂRTIRI", 12f, Ui.MUTED, true))
        for ((t, sb) in listOf("Plătești o lună" to "+1 învârtire", "Un prieten plătește prima lună" to "+1 învârtire", "Bun venit, la crearea contului" to "+1 o dată")) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, 0)
            row.addView(tv(t, 14f, Ui.TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tv(sb, 12f, Ui.MUTED))
            hc.addView(row)
        }
        content.addView(hc)
        content.addView(moreRow(R.drawable.ic_st_star, "Invită un prieten", "Cod personal și prietenii tăi") { tab = 10; render() })

        // numărul real, de la server
        Cloud.wheel(this) { o, _ -> if (o != null && !isFinishing && tab == 9 && !wheel.spinning) setSpinsText(o.optInt("spins", 0)) }
        setSpinsText(Cloud.spins(this))
    }

    private fun renderFriends() {
        content.addView(pageHeader("Mai mult", "Invită un prieten", { tab = 7; render() }))
        val cc = card()
        cc.gravity = Gravity.CENTER_HORIZONTAL
        cc.addView(tv("Codul tău de prieten", 12f, Ui.MUTED, true))
        val codeT = tv("…", 30f, Ui.TEXT, true)
        codeT.gravity = Gravity.CENTER
        codeT.setPadding(0, dp(6), 0, dp(4))
        cc.addView(codeT)
        val ex = tv("Prietenul descarcă aplicația de pe site, își face cont și scrie codul. Primește 7 zile gratuite, iar tu o învârtire la roată când prima lui plată e confirmată.", 12f, Ui.MUTED)
        ex.gravity = Gravity.CENTER
        cc.addView(ex)
        var code = ""
        val wa = button("Trimite pe WhatsApp") {
            if (code.isEmpty()) return@button
            val txt = "Folosesc DBX Pulse, aplicația care îți arată cât câștigi net pe km la fiecare cursă. Descarcă-o de aici: $SITE_URL . Fă-ți cont și scrie codul meu: $code. Primești 7 zile gratuite."
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(txt)))) }
            catch (e: Exception) { Toast.makeText(this, "Nu am putut deschide WhatsApp.", Toast.LENGTH_LONG).show() }
        }
        cc.addView(wa)
        val dl = tv("Descarcă aplicația: dbxpulse.netlify.app", 13f, Ui.BRAND, true)
        dl.gravity = Gravity.CENTER
        dl.setPadding(0, dp(12), 0, dp(2))
        dl.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL))) } catch (e: Exception) { Toast.makeText(this, SITE_URL, Toast.LENGTH_LONG).show() }
        }
        cc.addView(dl)
        val cp = tv("Copiază codul", 13f, Ui.BRAND, true)
        cp.gravity = Gravity.CENTER
        cp.setPadding(0, dp(12), 0, dp(2))
        cp.setOnClickListener {
            if (code.isNotEmpty()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("cod", code))
                Toast.makeText(this, "Cod copiat.", Toast.LENGTH_SHORT).show()
            }
        }
        cc.addView(cp)
        content.addView(cc)

        val stats = LinearLayout(this)
        val statsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statsLp.bottomMargin = dp(12)
        content.addView(stats, statsLp)
        val listCard = card()
        listCard.addView(tv("PRIETENII TĂI", 12f, Ui.MUTED, true))
        val listBody = LinearLayout(this)
        listBody.orientation = LinearLayout.VERTICAL
        listCard.addView(listBody)
        content.addView(listCard)

        Cloud.wheel(this) { o, msg ->
            if (isFinishing || tab != 10) return@wheel
            if (o == null) { codeT.text = "—"; listBody.addView(tv(msg, 13f, Ui.MUTED)); return@wheel }
            code = o.optString("friend_code", "")
            codeT.text = code
            fun stat(n: String, label: String, last: Boolean) {
                val m = LinearLayout(this)
                m.orientation = LinearLayout.VERTICAL
                m.gravity = Gravity.CENTER
                m.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat(), Ui.SURFACE2, dp(1))
                m.setPadding(dp(8), dp(12), dp(8), dp(12))
                m.addView(tv(n, 24f, Ui.TEXT, true))
                m.addView(tv(label, 11f, Ui.MUTED))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (!last) lp.rightMargin = dp(10)
                stats.addView(m, lp)
            }
            stat(o.optInt("friends_total", 0).toString(), "prieteni aduși", false)
            stat(o.optInt("friends_paid", 0).toString(), "au plătit", false)
            stat(o.optInt("spins", 0).toString(), "învârtiri acum", true)
            val a = o.optJSONArray("friends")
            if (a == null || a.length() == 0) {
                listBody.addView(tv("Încă nimeni. Trimite codul și apare aici.", 13f, Ui.MUTED))
            } else {
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    val row = LinearLayout(this)
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(0, dp(10), 0, 0)
                    row.addView(tv(f.optString("name", "Prieten"), 14f, Ui.TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    val st = f.optString("status")
                    val txt = when (st) { "paid" -> "A plătit ✓"; "trial" -> "În probă · " + f.optInt("days_left", 0) + " zile"; else -> "Fără acces activ" }
                    row.addView(tv(txt, 12f, if (st == "paid") Ui.GREEN else Ui.MUTED, true))
                    listBody.addView(row)
                }
            }
        }
        val note = tv("O învârtire pentru fiecare prieten, o singură dată, când plata lui e confirmată.", 12f, Ui.MUTED)
        note.setPadding(dp(4), dp(10), dp(4), 0)
        content.addView(note)
    }

    // ---------- MAI MULT ----------
    private fun moreRow(icon: Int, title: String, sub: String, go: () -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat(), Ui.SURFACE2, dp(1))
        r.setPadding(dp(14), dp(13), dp(14), dp(13))
        val ib = FrameLayout(this)
        ib.background = Ui.rounded(Ui.SURFACE2, dp(12).toFloat())
        val iv = ImageView(this); iv.setImageResource(icon); iv.setColorFilter(Ui.BRAND)
        ib.addView(iv, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        r.addView(ib, LinearLayout.LayoutParams(dp(42), dp(42)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.addView(tv(title, 15f, Ui.TEXT, true))
        col.addView(tv(sub, 12f, Ui.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv("›", 22f, Ui.MUTED, true))
        r.setOnClickListener { go() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        r.layoutParams = lp
        return r
    }

    private fun renderMore() {
        content.addView(pageHeader("", "Mai mult"))
        accountCard()?.let { content.addView(it) }
        content.addView(moreRow(R.drawable.ic_st_star, "Roata norocului", if (Cloud.spins(this) > 0) "Ai " + Cloud.spins(this) + (if (Cloud.spins(this) == 1) " învârtire" else " învârtiri") + ", învârte acum" else "Câștigă zile gratuite") { tab = 9; render() })
        content.addView(moreRow(R.drawable.ic_q_book, "Invită un prieten", "Cod personal, prieteni, învârtiri") { tab = 10; render() })
        content.addView(moreRow(R.drawable.ic_q_clip, "Acte", "Documente, expirări, poze") { tab = 3; render() })
        content.addView(moreRow(R.drawable.ic_nav_gear, "Setări", "Simulator, costuri, praguri, alerte") { tab = 2; render() })
        content.addView(moreRow(R.drawable.ic_st_chart, "Aspect și culori", "Luminos, întunecat, culoarea aplicației, cardul") { tab = 8; render() })
        content.addView(moreRow(R.drawable.ic_q_book, "Ghid de activare", "Pas cu pas, cu poze") { showGuide = true; render() })
        content.addView(moreRow(R.drawable.ic_st_star, "Contact", "WhatsApp și email") { tab = 5; render() })
        val v = tv("DBX Pulse " + Updater.currentName(this), 12f, Ui.MUTED)
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(10), 0, 0)
        content.addView(v)
    }

    // ---------- ASPECT ȘI CULORI ----------
    private fun renderAppearance() {
        content.addView(pageHeader("Mai mult", "Aspect și culori", { tab = 7; render() }))

        // mod
        val modeKey = Store.raw(this, "theme") ?: "light"
        val mc = card()
        mc.addView(tv("MOD", 12f, Ui.MUTED, true))
        val seg = LinearLayout(this)
        seg.background = Ui.rounded(Ui.SURFACE2, dp(14).toFloat())
        seg.setPadding(dp(4), dp(4), dp(4), dp(4))
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(8)
        for ((k, nm) in listOf("light" to "Luminos", "dark" to "Întunecat", "auto" to "Automat")) {
            val on = k == modeKey
            val t = tv(nm, 13f, if (on) Ui.BRAND else Ui.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            if (on) t.background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
            t.setOnClickListener { Store.setRaw(this, "theme", k); recreate() }
            seg.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        mc.addView(seg, slp)
        mc.addView(tv("Automat urmează setarea telefonului.", 12f, Ui.MUTED))
        content.addView(mc)

        // culoare principală
        val cc = card()
        cc.addView(tv("CULOARE PRINCIPALĂ", 12f, Ui.MUTED, true))
        val curKey = Store.raw(this, "accent") ?: "graphite"
        var row: LinearLayout? = null
        for ((i, pair) in Ui.ACCENTS.withIndex()) {
            val (key, nm) = pair
            if (i % 4 == 0) {
                row = LinearLayout(this)
                row.setPadding(0, dp(10), 0, 0)
                cc.addView(row)
            }
            val sel = key == curKey
            val t = tv((if (sel) "✓ " else "") + nm, 12f, Ui.onAccentOf(key, Ui.light), true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(2), dp(14), dp(2), dp(14))
            t.background = Ui.rounded(Ui.accentOf(key, Ui.light), dp(12).toFloat(), if (sel) Ui.TEXT else 0, if (sel) dp(2) else 0)
            t.setOnClickListener { Store.setRaw(this, "accent", key); recreate() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            row?.addView(t, lp)
        }
        content.addView(cc)

        // cardul peste aplicația de ridesharing
        val kc = card()
        kc.addView(tv("CARDUL DE OFERTĂ", 12f, Ui.MUTED, true))
        val cardDark = Store.raw(this, "card_dark") == "1"
        val seg2 = LinearLayout(this)
        seg2.background = Ui.rounded(Ui.SURFACE2, dp(14).toFloat())
        seg2.setPadding(dp(4), dp(4), dp(4), dp(4))
        val s2lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        s2lp.topMargin = dp(8)
        for ((k, nm) in listOf(false to "Luminos", true to "Întunecat")) {
            val on = k == cardDark
            val t = tv(nm, 13f, if (on) Ui.BRAND else Ui.MUTED, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            if (on) t.background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
            t.setOnClickListener { Store.setRaw(this, "card_dark", if (k) "1" else "0"); render() }
            seg2.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        kc.addView(seg2, s2lp)
        val cashO = Offer(34.0, 2.0, 9.0, 6, 18, true, true)
        val cardO = Offer(21.0, 1.5, 4.0, 4, 11, true, false)
        for (o in listOf(cashO, cardO)) {
            val oc = OverlayCard(this)
            oc.bind(o, Scorer.score(o, Store.config(this)))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            kc.addView(oc.view, lp)
        }
        kc.addView(tv("Numerar are contur auriu, card are contur albastru. Verdele, galbenul și roșul din verdict rămân la fel în orice culoare.", 12f, Ui.MUTED))
        content.addView(kc)
    }

    /** Contul, pe pagina principală: cine e conectat și ieșirea din cont. */
    private fun accountCard(): LinearLayout? {
        if (!Cloud.configured()) return null
        val c = card()
        if (Cloud.loggedIn(this)) {
            c.addView(tv("CONT", 12f, Ui.MUTED, true))
            c.addView(tv(displayName() + (if (Cloud.role(this) == "admin") " · admin" else ""), 16f, Ui.TEXT, true))
            c.addView(tv(Cloud.email(this), 12f, Ui.MUTED))
            if (Cloud.joined(this)) c.addView(button("Schimbă numele") {
                askText("Numele tău", "Nume sau nickname", Cloud.name(this), false) { t ->
                    Cloud.rename(this, t) { ok, msg -> refreshAfter(msg) }
                }
            })
            c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        } else {
            c.addView(tv("Nu ești conectat. Cu un cont păstrezi datele și intri în comunitate.", 13f, Ui.MUTED))
            c.addView(button("Intră în cont") { tab = 1; render() })
        }
        return c
    }

    private fun renderSettings() {
        content.addView(pageHeader("Mai mult", "Setări", { tab = 7; render() }))
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
        var simCash = false
        val refresh: () -> Unit = {
            val p = simPrice.replace(',', '.').toDoubleOrNull() ?: 0.0
            val a = simPick.replace(',', '.').toDoubleOrNull() ?: 0.0
            val b = simTrip.replace(',', '.').toDoubleOrNull() ?: 0.0
            if (p > 0.0 && a + b > 0.0) {
                val o = Offer(p, a, b, 0, 0, true, simCash)
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
        val payBtn = button("Plată în simulator: CARD (apasă ca să schimbi)") { }
        payBtn.setOnClickListener {
            simCash = !simCash
            payBtn.text = if (simCash) "Plată în simulator: NUMERAR (apasă ca să schimbi)" else "Plată în simulator: CARD (apasă ca să schimbi)"
            refresh()
        }
        sim.addView(payBtn)
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
            gd.addView(tv("Cont: " + displayName() + (if (Cloud.role(this) == "admin") " · admin" else ""), 12f, Ui.MUTED))
            gd.addView(tv("Citește din nou de pe server câte zile de acces mai ai. Rolul îl stabilește doar serverul, nu se poate schimba din telefon.", 11f, Ui.MUTED))
            gd.addView(button("Reîmprospătează contul (acces)") {
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
        val au = scard()
        au.addView(tv("ALERTE ȘI CARD PESTE APLICAȚIA DE RIDESHARING", 13f, Ui.BRAND, true))
        au.addView(tv("Când se salvează o cursă: doar după ce platforma ți-o atribuie (apare ecranul cursei active). Nu se salvează ofertele respinse, cele luate de alt șofer, cele anulate de pasager sau anulate de tine.", 13f, Ui.TEXT))
        val alOn = Store.raw(this, "alert_top") != "0"
        au.addView(button(if (alOn) "Vibrație „DE NERATAT”: PORNITĂ — apasă ca s-o oprești" else "Vibrație „DE NERATAT”: OPRITĂ — apasă ca s-o pornești") {
            Store.setRaw(this, "alert_top", if (alOn) "0" else "1")
            render()
        })
        val sdOn = Store.raw(this, "alert_sound") != "0"
        au.addView(button(if (sdOn) "Sunet „DE NERATAT”: PORNIT — apasă ca să-l oprești" else "Sunet „DE NERATAT”: OPRIT — apasă ca să-l pornești") {
            Store.setRaw(this, "alert_sound", if (sdOn) "0" else "1")
            render()
        })
        au.addView(tv("Sunetul pornește când apare o cursă de la pragul violet și ține cât stă cardul pe ecran: minim 4 secunde, maxim 8.", 12f, Ui.MUTED))
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
