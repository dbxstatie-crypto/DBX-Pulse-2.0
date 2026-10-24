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
        val l2 = tv("Pulse", 28f, Ui.GREEN, true); l2.setTypeface(l2.typeface, Typeface.BOLD_ITALIC)
        val l3 = ImageView(this); l3.setImageResource(R.drawable.ic_pulse)
        logoRow.addView(l1); logoRow.addView(l2)
        logoRow.addView(l3, LinearLayout.LayoutParams(dp(30), dp(26)))
        header.addView(logoRow)
        header.addView(tv("Statistici inteligente pentru șoferi Bolt", 13f, Ui.TEXT))
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
        bolt.background = Ui.rounded(Ui.GREEN, dp(26).toFloat())
        bolt.setOnClickListener {
            if (!serviceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else Toast.makeText(this, "Activ – cardul apare automat în Bolt Driver", Toast.LENGTH_SHORT).show()
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
        val col = if (active) Ui.GREEN else Ui.MUTED
        t.setTextColor(col)
        t.compoundDrawables[1]?.mutate()?.setTint(col)
    }

    private fun updateStatus() {
        if (serviceEnabled()) {
            statusView.text = "●  Activ — cardul apare automat în Bolt Driver"
            statusView.setTextColor(Ui.GREEN)
        } else {
            statusView.text = "●  Dezactivat — apasă aici și activează „DBX Pulse” la Accesibilitate"
            statusView.setTextColor(Ui.AMBER)
        }
    }

    private fun render() {
        simRefresh = null
        if (Cloud.configured() && !Cloud.loggedIn(this)) {
            // fără cont: doar ecranul de autentificare, restul aplicației apare după login
            tabsBar.visibility = View.GONE
            zoneMap?.onDetach()
            zoneMap = null
            content.removeAllViews()
            if (!splashDone) renderSplash() else renderLogin()
            return
        }
        tabsBar.visibility = View.VISIBLE
        styleTab(tabHistory, tab == 0)
        styleTab(tabZones, tab == 1)
        styleTab(tabDocs, tab == 3)
        styleTab(tabSettings, tab == 2)
        zoneMap?.onDetach()
        zoneMap = null
        content.removeAllViews()
        when (tab) {
            0 -> renderHistory()
            1 -> renderZones()
            3 -> renderDocs()
            4 -> renderExpenses()
            else -> renderSettings()
        }
    }

    // ---------- ISTORIC ----------
    private fun renderHistory() {
        val rides = Store.history(this)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val today = rides.filter { it.ts >= dayStart }

        content.addView(welcomeBanner())
        content.addView(shiftCard())
        content.addView(summaryCard("AZI", today, Odometer.today(this)))
        content.addView(summaryCard("TOTAL", rides, Odometer.total(this)))
        content.addView(quickActions())

        if (rides.isEmpty()) {
            val c = card()
            c.addView(tv("Nicio cursă salvată încă.", 15f, Ui.TEXT, true))
            val hint = tv(
                "Când apeși „Acceptă” în Bolt, cursa apare aici. Cursele card acceptate automat " +
                    "se salvează singure după câteva secunde.",
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
        if (rides.size > 4) {
            val all = tv(if (showAllRides) "Mai puține" else "Vezi toate →", 14f, Ui.GREEN, true)
            all.setOnClickListener { showAllRides = !showAllRides; render() }
            hd.addView(all)
        }
        content.addView(hd)
        for (r in rides.take(if (showAllRides) 200 else 4)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat())
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            row.layoutParams = lp

            val dot = View(this)
            dot.background = Ui.rounded(Ui.levelColor(r.level), dp(8).toFloat())
            row.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, 0, 0)
            col.addView(tv(Fmt.d2(r.netPerKm) + " lei/km net  ·  " + Fmt.d1(r.totalKm) + " km", 16f, Ui.TEXT, true))
            col.addView(
                tv(
                    fmt.format(Date(r.ts)) + (if (r.pay == 1) "  ·  numerar" else if (r.pay == 0) "  ·  card" else "") + "  ·  net " + Fmt.d2(r.net) + " lei din " + Fmt.d2(r.price) +
                        "  ·  " + Fmt.d1(r.pickupKm) + " + " + Fmt.d1(r.tripKm) + " km",
                    12f, Ui.MUTED
                )
            )
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Ștergi cursa?")
                    .setPositiveButton("Șterge") { _, _ ->
                        Store.deleteRide(this, r.ts)
                        render()
                    }
                    .setNegativeButton("Anulează", null)
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

    /** Pornește / oprește tura: cât e pornită se numără kilometrii (GPS) pentru „km în gol”. */
    private fun shiftCard(): LinearLayout {
        val c = card()
        val on = Odometer.on(this)
        c.addView(tv("TURĂ", 12f, Ui.MUTED, true))
        val st = tv(
            if (on) "●  În tură — număr kilometrii parcurși (inclusiv în gol)"
            else "●  Tură oprită — kilometrii în gol nu se numără",
            14f, if (on) Ui.GREEN else Ui.AMBER, true
        )
        st.setPadding(0, dp(4), 0, 0)
        c.addView(st)
        if (!Geo.hasBackground(this)) {
            val h = tv("Pentru kilometri și cu telefonul blocat: Permisiuni → Locație → „Permite tot timpul”.", 12f, Ui.MUTED)
            h.setPadding(0, dp(4), 0, 0)
            c.addView(h)
        }
        c.addView(button(if (on) "Oprește tura" else "Pornește tura") {
            if (!on && !Geo.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }
            Odometer.setOn(this, !on)
            render()
        })
        return c
    }

    private fun summaryCard(title: String, rides: List<Ride>, odoKm: Double): LinearLayout {
        val c = card()
        val dateTxt = if (title == "AZI") SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ro-RO")).format(Date()) else "toate cursele"
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
        // km în gol = tot ce ai parcurs în tură minus kilometrii curselor
        val empty = (odoKm - km).coerceAtLeast(0.0)
        row(statTile(R.drawable.ic_st_road, "KM în gol", Fmt.d1(empty)), statTile(R.drawable.ic_st_car, "KM în tură", Fmt.d1(odoKm)))
        return c
    }

    /** Mică etichetă + valoare (folosită în cardul zonei). */
    private fun stat(label: String, value: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(label, 10f, Ui.MUTED, true))
        col.addView(tv(value, 16f, Ui.TEXT, true))
        return col
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

    private fun statTile(icon: Int, label: String, value: String): LinearLayout {
        val t = LinearLayout(this)
        t.gravity = Gravity.CENTER_VERTICAL
        t.background = Ui.rounded(Ui.BG, dp(14).toFloat())
        t.setPadding(dp(10), dp(10), dp(10), dp(10))
        val ib = FrameLayout(this)
        ib.background = Ui.rounded(Ui.SURFACE2, dp(10).toFloat())
        val iv = ImageView(this); iv.setImageResource(icon); iv.setColorFilter(Ui.GREEN)
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
        col.addView(tv("Bun venit,", 16f, Ui.MUTED))
        col.addView(tv(nm, 28f, Ui.TEXT, true))
        val sub = tv("Condu inteligent.\nCâștigă mai mult.", 14f, Ui.TEXT)
        sub.setPadding(0, dp(8), 0, 0)
        col.addView(sub)
        f.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170))
        lp.bottomMargin = dp(12)
        f.layoutParams = lp
        return f
    }

    private fun quickActions(): LinearLayout {
        val r = LinearLayout(this)
        val lpR = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpR.bottomMargin = dp(12)
        r.layoutParams = lpR
        fun q(icon: Int, label: String, go: () -> Unit) {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4), dp(14), dp(4), dp(14))
            t.background = Ui.rounded(Ui.SURFACE, dp(16).toFloat())
            val iv = ImageView(this); iv.setImageResource(icon); iv.setColorFilter(Ui.GREEN)
            t.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
            val l = tv(label, 12f, Ui.TEXT, true); l.gravity = Gravity.CENTER; l.setPadding(0, dp(6), 0, 0)
            t.addView(l)
            t.setOnClickListener { go() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            r.addView(t, lp)
        }
        q(R.drawable.ic_nav_zone, "Zone") { tab = 1; render() }
        q(R.drawable.ic_st_wallet, "Cheltuieli") { tab = 4; render() }
        q(R.drawable.ic_nav_docs, "Acte") { tab = 3; render() }
        q(R.drawable.ic_nav_gear, "Setări") { tab = 2; render() }
        return r
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
        val n = z.rides
        return if (n >= 8) "încredere mare" else if (n >= 3) "încredere medie" else "încredere mică"
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
            c.addView(tv("Ca să salvez locația și când ești în Bolt, deschide Permisiuni → Locație și alege „Permite tot timpul”.", 13f, Ui.TEXT))
            c.addView(button("Deschide setările aplicației") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            content.addView(c)
        }

        // zone = doar locurile de unde se PRELUAU curse (tu + toți ceilalți), nu și ofertele văzute
        val events = (Store.geoEvents(this) + Cloud.communityEvents(this)).filter { it.kind == 1 }
        val now = System.currentTimeMillis()
        val an = Zones.analyze(events, now)
        val me = Geo.last(this)
        // o zonă apare doar dacă are cel puțin 2 curse (un singur punct e un caz izolat)
        val zones = an.zones.filter { it.rides >= 2 }

        if (events.sumOf { it.weight } < 5 || zones.isEmpty()) {
            val c = card()
            c.addView(tv("Adun date…", 15f, Ui.TEXT, true))
            c.addView(tv("Am ${events.sumOf { it.weight }} curse cu locație (tu + comunitatea). Zonele apar când ai cel puțin 2 curse în aceeași zonă (~550 m) și ~5 curse în total. Lucrează normal cu Bolt deschis, iar eu notez de unde se preiau cursele.", 13f, Ui.MUTED))
            content.addView(c)
            addZoneData(events)
            return
        }

        val top = zones[0].score
        fun norm(z: Zone): Double = if (top > 0) 100.0 * z.score / top else 0.0
        val hh = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        val sub = (if (an.usedTimeFilter) "zone după istoricul acestei ore" else "încă puține date, folosesc tot istoricul") + " · $hh"

        fun where(z: Zone): String {
            if (me == null) return Zones.label(z, an)
            val km = Geo.distKm(me.first, me.second, z.lat, z.lon)
            return Zones.label(z, an) + "  ·  " + Fmt.d1(km) + " km spre " + Geo.direction(me.first, me.second, z.lat, z.lon)
        }

        // ----- cea mai bună zonă -----
        val best = zones[0]
        val bc = card()
        bc.background = Ui.rounded(Color.parseColor("#0F3D22"), dp(18).toFloat(), Ui.GREEN, dp(1))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(tv("CEA MAI BUNĂ ZONĂ ACUM", 11f, Ui.GREEN, true))
        left.addView(tv(Zones.label(best, an), 22f, Ui.TEXT, true))
        left.addView(tv(where(best), 12f, Ui.MUTED))
        head.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        right.addView(tv("100.0", 28f, Ui.GREEN, true))
        right.addView(tv("SCOR", 10f, Ui.MUTED, true))
        head.addView(right)
        bc.addView(head)
        val st = LinearLayout(this)
        st.orientation = LinearLayout.HORIZONTAL
        st.setPadding(0, dp(10), 0, 0)
        st.addView(stat("CURSE", best.rides.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        st.addView(stat("NET/KM", Fmt.d2(best.avgNetPerKm)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bc.addView(st)
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
                col.addView(tv("Opțiunea ${i + 1} · ${z.rides} curse preluate · ${confidence(z)}", 11f, Ui.MUTED))
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(tv(Fmt.d1(norm(z)), 16f, Ui.AMBER, true))
                bc.addView(row)
            }
        }
        content.addView(bc)

        // ----- când merită: ore și zile bune pentru zona aleasă -----
        val wc = card()
        wc.addView(tv("CÂND MERITĂ", 12f, Ui.MUTED, true))
        val whenTitle = tv("", 15f, Ui.TEXT, true)
        whenTitle.setPadding(0, dp(4), 0, dp(6))
        wc.addView(whenTitle)
        val hourBars = LinearLayout(this)
        hourBars.orientation = LinearLayout.HORIZONTAL
        hourBars.gravity = Gravity.BOTTOM
        wc.addView(hourBars, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        val axis = LinearLayout(this)
        for (lab in listOf("0", "6", "12", "18", "23")) {
            val t = tv(lab, 10f, Ui.MUTED)
            axis.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        wc.addView(axis)
        val whenBody = tv("", 13f, Ui.TEXT)
        whenBody.setPadding(0, dp(8), 0, 0)
        wc.addView(whenBody)

        fun showTiming(z: Zone) {
            val t = Zones.timing(events, z)
            val dayNames = arrayOf("Luni", "Marți", "Miercuri", "Joi", "Vineri", "Sâmbătă", "Duminică")
            val ch = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            whenTitle.text = Zones.label(z, an) + "  ·  " + t.total + " curse"
            hourBars.removeAllViews()
            val mx = (t.hours.maxOrNull() ?: 0).coerceAtLeast(1)
            for (h in 0..23) {
                val bar = View(this)
                bar.background = Ui.rounded(if (h == ch) Ui.GREEN else Ui.SURFACE2, dp(2).toFloat())
                val hgt = if (t.hours[h] == 0) dp(2) else (dp(52) * t.hours[h] / mx).coerceAtLeast(dp(4))
                val lp = LinearLayout.LayoutParams(0, hgt, 1f)
                lp.setMargins(dp(1), 0, dp(1), 0)
                hourBars.addView(bar, lp)
            }
            if (t.total < 3) {
                whenBody.text = "Încă puține curse în zona asta ca să spun când merită."
                return
            }
            val hrs = (0..23).filter { t.hours[it] > 0 }.sortedByDescending { t.hours[it] }.take(3)
            val sb = StringBuilder()
            sb.append("Ore bune: ").append(hrs.joinToString("  ·  ") { String.format(Locale.US, "%02d:00 (%d)", it, t.hours[it]) })
            if (t.daysTotal >= 3) {
                val ds = (0..6).filter { t.days[it] > 0 }.sortedByDescending { t.days[it] }.take(3)
                sb.append("\nZile bune: ").append(ds.joinToString("  ·  ") { dayNames[it] + " (" + t.days[it] + ")" })
            } else {
                sb.append("\nZile bune: apar după mai multe curse în zonă.")
            }
            sb.append("\n")
            sb.append(
                when {
                    t.hours[ch] >= 0.6 * mx -> "✔ Acum e o oră bună aici."
                    t.hours[ch] == 0 -> "Acum nu prea ai avut curse aici."
                    else -> "Acum e o oră medie aici."
                }
            )
            whenBody.text = sb.toString()
        }
        showTiming(best)
        content.addView(wc)

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
        map.controller.setZoom(13.5)
        map.controller.setCenter(GeoPoint(me?.first ?: an.centerLat, me?.second ?: an.centerLon))
        map.overlays.add(HotZoneOverlay(zones.take(25), top, me) { z ->
            detail.text = Zones.label(z, an) + ": ${z.rides} curse preluate, " +
                Fmt.d2(z.avgNetPerKm) + " lei/km net · scor " + Fmt.d1(norm(z))
            detail.setTextColor(Ui.TEXT)
            showTiming(z)
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
        val r = events.filter { it.kind == 1 }.sumOf { it.weight }
        c.addView(tv("$r curse cu locația de preluare (tu + comunitatea)", 13f, Ui.TEXT))
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
        val pulse = tv("Pulse", 38f, Ui.GREEN, true)
        pulse.setTypeface(pulse.typeface, Typeface.BOLD_ITALIC)
        col.addView(pulse)
        val tag = tv("Statistici inteligente\npentru șoferi Bolt", 17f, Ui.TEXT)
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
            i.setColorFilter(Ui.GREEN)
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

    private fun renderLogin() {
        val c = card()
        c.addView(tv("Bun venit", 22f, Ui.TEXT, true))
        c.addView(tv("Intră în cont sau creează unul cu email și parolă.", 13f, Ui.MUTED))
        val emailF = textField("Email", false, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passF = textField("Parolă (minim 6 caractere)", false, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val nickF = textField("Nume sau nickname (la cont nou)", false)
        c.addView(nickF)
        c.addView(emailF)
        c.addView(passF)
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
            Store.setRaw(this, "pending_nick", nickF.text.toString().trim())
            Cloud.signIn(this, em, pw, create) { ok, msg ->
                if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                refreshAfter(msg)
            }
        }
        c.addView(button("Intru în cont") { go(false) })
        c.addView(button("Creez cont") { go(true) })
        c.addView(tv("Prin „Creez cont” ești de acord ca locația, ora, prețul și km ale ofertelor tale să fie trimise în contul comun; ceilalți șoferi văd doar zone agregate, fără nume sau ID. Îți poți șterge datele oricând din Setări.", 12f, Ui.MUTED))
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
            c.addView(tv("Cont: " + Cloud.email(this), 13f, Ui.GREEN, true))
            c.addView(tv("Alege numele cu care apari în comunitate.", 13f, Ui.TEXT))
            val nameF = textField("Numele tău (ex: Mihai)", false)
            c.addView(nameF)
            c.addView(button("Intru în comunitate") {
                val n = nameF.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "Completează numele.", Toast.LENGTH_SHORT).show()
                } else {
                    Cloud.join(this, n) { ok, msg ->
                        if (ok) Cloud.syncAsync(this, true) { if (!isFinishing && tab == 1) render() }
                        refreshAfter(msg)
                    }
                }
            })
            c.addView(button("Ieși din cont") { Cloud.logout(this); render() })
        } else {
            // 3) membru
            val roleTxt = if (Cloud.role(this) == "admin") " · admin" else ""
            c.addView(tv("Conectat ca ${Cloud.name(this)}$roleTxt", 15f, Ui.GREEN, true))
            c.addView(tv(Cloud.email(this) + " · ID " + Cloud.uid(this).take(8), 12f, Ui.MUTED))
            val st = Cloud.status(this)
            if (st.isNotEmpty()) c.addView(tv(st, 12f, Ui.MUTED))
            c.addView(button("Sincronizează acum") {
                Cloud.syncAsync(this, true) { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (!isFinishing && tab == 1) render()
                }
            })
            c.addView(button("Ieși din cont (datele din cloud rămân)") { Cloud.logout(this); render() })
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
    private fun renderSettings() {
        // simulator
        val sim = card()
        sim.addView(tv("SIMULATOR", 12f, Ui.MUTED, true))
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

        // afișare și acceptare
        val opt = card()
        opt.addView(tv("AFIȘARE ȘI ACCEPTARE", 12f, Ui.MUTED, true))
        val auto = Store.autoCard(this)
        val autoTxt = tv(
            if (auto) "Cursele card (acceptate automat de Bolt) se salvează singure în istoric după câteva secunde; dacă respingi, nu se salvează."
            else "Cursele card se salvează doar când apeși Acceptă.",
            13f, Ui.MUTED
        )
        autoTxt.setPadding(0, dp(4), 0, 0)
        opt.addView(autoTxt)
        opt.addView(button(if (auto) "Salvare automată card: PORNITĂ" else "Salvare automată card: OPRITĂ") {
            Store.setRaw(this, "auto_card", if (auto) "0" else "1")
            render()
        })
        val bottom = Store.cardOnBottom(this)
        opt.addView(button(if (bottom) "Cardul apare: JOS (apasă pentru sus)" else "Cardul apare: SUS (apasă pentru jos)") {
            Store.setRaw(this, "card_pos", if (bottom) "top" else "bottom")
            render()
        })
        content.addView(opt)

        // costuri
        val costs = card()
        costs.addView(tv("COSTURI (pentru „net”)", 12f, Ui.MUTED, true))
        costs.addView(settingRow("comm", "Comision platformă (%)", 20.0))
        costs.addView(settingRow("cons", "Consum (l sau kWh / 100 km)", 7.0))
        costs.addView(settingRow("eprice", "Preț combustibil / energie (lei)", 7.5))
        costs.addView(settingRow("maint", "Mentenanță + uzură (lei/km)", 0.20))
        costs.addView(settingRow("fix", "Costuri fixe lunare: asigurare, taxe (lei)", 0.0))
        costs.addView(settingRow("kmw", "Km pe săptămână (pt. costuri fixe)", 1200.0))
        content.addView(costs)

        // praguri
        val th = card()
        th.addView(tv("PRAGURI VERDE / GALBEN / ROȘU", 12f, Ui.MUTED, true))
        th.addView(settingRow("green", "Verde de la (lei/km net)", 2.6))
        th.addView(settingRow("red", "Roșu sub (lei/km net)", 2.0))
        th.addView(settingRow("short", "Cursă scurtă = mereu bună, până la (km total)", 3.0))
        content.addView(th)

        // diagnostic
        val diag = card()
        diag.addView(tv("DIAGNOSTIC", 12f, Ui.MUTED, true))
        val d = Store.dump(this)
        val dv = tv(
            if (d.isEmpty()) "Încă nu am citit nimic din Bolt. Deschide o ofertă cu serviciul activ."
            else "Ultimul text citat din Bolt:\n\n" + d,
            12f, Ui.MUTED
        )
        dv.setTextIsSelectable(true)
        dv.setPadding(0, dp(6), 0, 0)
        diag.addView(dv)
        content.addView(diag)
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
