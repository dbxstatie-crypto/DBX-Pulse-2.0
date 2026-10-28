package ro.drivescore

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    // culori fixe, întunecate: cardul peste Bolt și harta nu se schimbă cu tema
    val D_BG = Color.parseColor("#06120C")
    val D_SURFACE = Color.parseColor("#0E1F16")
    val D_SURFACE2 = Color.parseColor("#15392A")
    val D_TEXT = Color.parseColor("#E8F7EE")
    val D_MUTED = Color.parseColor("#86A595")

    // culori care urmează tema aleasă (luminoasă / întunecată / automată) și culoarea principală
    var light = true
    var BG = Color.parseColor("#F4F5F8")
    var SURFACE = Color.parseColor("#FFFFFF")
    var SURFACE2 = Color.parseColor("#EEF0F5")
    var TEXT = Color.parseColor("#0F172A")
    var MUTED = Color.parseColor("#64708A")
    var BRAND = Color.parseColor("#334155")
    var ACCENT = Color.parseColor("#334155")
    var ON_ACCENT = Color.WHITE

    // culorile verdictelor: mai domoale decât înainte, dar mereu aceleași în orice culoare principală
    var GREEN = Color.parseColor("#1B7F55")
    var TOP = Color.parseColor("#7A4FD0")   // „DE NERATAT”
    const val TOP_NPK = 4.5                 // lei/km net de la care cursa e „de neratat”
    var AMBER = Color.parseColor("#A8650A")
    var RED = Color.parseColor("#B4432F")

    /** Culorile principale la alegere (cheie, nume). Prima e cea standard. */
    val ACCENTS = listOf(
        "graphite" to "Grafit", "indigo" to "Indigo", "green" to "Smarald", "teal" to "Turcoaz",
        "blue" to "Albastru", "purple" to "Violet", "amber" to "Chihlimbar", "red" to "Rubin"
    )

    fun accentOf(key: String, light: Boolean): Int = Color.parseColor(
        when (key) {
            "indigo" -> if (light) "#4457D8" else "#8C9BFF"
            "green" -> if (light) "#1B7F55" else "#4ADE9B"
            "teal" -> if (light) "#0E7C86" else "#4FD1D9"
            "blue" -> if (light) "#2563C9" else "#6EA0F0"
            "purple" -> if (light) "#7A4FD0" else "#B79BF2"
            "amber" -> if (light) "#C2410C" else "#FB923C"
            "red" -> if (light) "#BE3A5B" else "#F472A0"
            else -> if (light) "#334155" else "#CBD5E1"
        }
    )

    fun onAccentOf(key: String, light: Boolean): Int {
        val c = accentOf(key, light)
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return if (lum > 0.6) Color.parseColor("#0F172A") else Color.WHITE
    }

    /** Modul ales: „light”, „dark” sau „auto” (după telefon). Standard: luminos. */
    fun isLight(c: Context): Boolean {
        return when (Store.raw(c, "theme")) {
            "dark" -> false
            "auto" -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    fun apply(c: Context) {
        light = isLight(c)
        val key = Store.raw(c, "accent") ?: "graphite"
        if (light) {
            BG = Color.parseColor("#F4F5F8"); SURFACE = Color.parseColor("#FFFFFF"); SURFACE2 = Color.parseColor("#EEF0F5")
            TEXT = Color.parseColor("#0F172A"); MUTED = Color.parseColor("#64708A")
            GREEN = Color.parseColor("#1B7F55"); AMBER = Color.parseColor("#A8650A")
            RED = Color.parseColor("#B4432F"); TOP = Color.parseColor("#7A4FD0")
        } else {
            BG = Color.parseColor("#0F172A"); SURFACE = Color.parseColor("#1E293B"); SURFACE2 = Color.parseColor("#2A3649")
            TEXT = Color.parseColor("#F1F5F9"); MUTED = Color.parseColor("#94A3B8")
            GREEN = Color.parseColor("#4ADE9B"); AMBER = Color.parseColor("#F5B84A")
            RED = Color.parseColor("#F58A74"); TOP = Color.parseColor("#B79BF2")
        }
        BRAND = accentOf(key, light)
        ACCENT = BRAND
        ON_ACCENT = onAccentOf(key, light)
    }

    fun levelColor(l: Level): Int = when (l) {
        Level.GOOD -> GREEN
        Level.OK -> AMBER
        Level.BAD -> RED
    }

    fun levelLabel(l: Level): String = when (l) {
        Level.GOOD -> "BUNĂ"
        Level.OK -> "MEDIE"
        Level.BAD -> "NU MERITĂ"
    }

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx
        if (strokePx > 0) g.setStroke(strokePx, strokeColor)
        return g
    }
}

/** Cardul modern afișat peste aplicația de ride-hailing (și în simulatorul din setări). */
class OverlayCard(private val ctx: Context) {
    private companion object {
        const val SOUND_MIN_MS = 4000L
        const val SOUND_MAX_MS = 8000L
    }
    private var cash = false
    private var payColor = Color.parseColor("#2563C9")
    private var cutout: Rect? = null
    private var alertedFor = ""
    private var top = false          // „DE NERATAT”: net/km foarte mare
    private var topColor = Color.parseColor("#7A4FD0")
    private var dark = false
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = ArrayList<TextView>()

    /** Cardul; desenează în fundal numerar/card și lasă o „fereastră” goală peste butonul Respinge. */
    val view = object : LinearLayout(ctx) {
        init { setWillNotDraw(false) }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val d = ctx.getDrawable(if (cash) R.drawable.wm_cash else R.drawable.wm_card)?.mutate() ?: return
            d.setTint(payColor)
            d.alpha = if (dark) 55 else 42
            if (this@OverlayCard.top) {
                glowPaint.style = Paint.Style.STROKE
                glowPaint.strokeWidth = dp(3).toFloat()
                glowPaint.color = topColor
                glowPaint.alpha = (45 + 110 * glow).toInt()
                val i = dp(5).toFloat()
                c.drawRoundRect(i, i, width - i, height - i, dp(18).toFloat(), dp(18).toFloat(), glowPaint)
            }
            val s = (height * 0.42f).toInt()
            val right = width - dp(18)
            val topY = (height * 0.34f).toInt()
            d.setBounds(right - s, topY, right, topY + s)
            d.draw(c)
            wmPaint.color = payColor
            wmPaint.alpha = if (dark) 70 else 52
            wmPaint.textSize = dp(26).toFloat()
            wmPaint.typeface = Typeface.DEFAULT_BOLD
            wmPaint.letterSpacing = 0.06f
            wmPaint.textAlign = Paint.Align.RIGHT
            val px = (width - dp(16)).toFloat()
            val py = (height - dp(22)).toFloat()
            c.save()
            c.rotate(-8f, px, py)
            c.drawText(if (cash) "NUMERAR" else "CARD", px, py, wmPaint)
            c.restore()
        }
        override fun onDetachedFromWindow() {
            glowAnim?.cancel()
            glowAnim = null
            super.onDetachedFromWindow()
        }
        override fun draw(c: Canvas) {
            val r = cutout
            if (r == null) { super.draw(c); return }
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            c.save()
            c.clipOutRect(Rect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1]))
            super.draw(c)
            c.restore()
        }
    }

    /** Scala colorată (roșu, galben, verde, violet) cu un marcaj la net/km-ul ofertei. */
    private inner class ScaleBar : View(ctx) {
        var frac = 0f
        var mark = Color.BLACK
        var stops = floatArrayOf(0f, 0.36f, 0.5f, 0.9f, 1f)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            if (w <= 0f) return
            val h = dp(8).toFloat()
            val y = (height - h) / 2f
            p.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#D98B7C"), Color.parseColor("#E3BC72"), Color.parseColor("#7FB598"), Color.parseColor("#8E7CD8"), Color.parseColor("#8E7CD8")),
                stops, Shader.TileMode.CLAMP
            )
            c.drawRoundRect(0f, y, w, y + h, h / 2f, h / 2f, p)
            p.shader = null
            p.color = mark
            val x = frac.coerceIn(0f, 1f) * w
            val half = dp(3).toFloat()
            c.drawRoundRect(x - half, y - dp(4), x + half, y + h + dp(4), half, half, p)
        }
    }

    private var player: android.media.MediaPlayer? = null
    private var soundStartedAt = 0L
    private val soundHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val soundMaxStop = Runnable { fadeOutSound() }

    /** Vibrație + sunetul „money” când apare o cursă „DE NERATAT” (fiecare se poate opri din Setări). */
    private fun alertTop() {
        if (Store.raw(ctx, "alert_top") != "0") {
            try {
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
            } catch (e: Exception) { }
        }
        if (Store.raw(ctx, "alert_sound") != "0") startSound()
    }

    private fun startSound() {
        try {
            killSound()
            val mp = android.media.MediaPlayer.create(ctx, R.raw.money_top) ?: return
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setOnCompletionListener { killSound() }
            player = mp
            soundStartedAt = System.currentTimeMillis()
            mp.start()
            soundHandler.postDelayed(soundMaxStop, SOUND_MAX_MS)   // cel mult 8 secunde
        } catch (e: Exception) { killSound() }
    }

    private fun killSound() {
        soundHandler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    private fun fadeOutSound() {
        val mp = player ?: return
        var vol = 1f
        val step = object : Runnable {
            override fun run() {
                vol -= 0.2f
                if (vol <= 0f || player !== mp) { killSound(); return }
                try { mp.setVolume(vol, vol) } catch (e: Exception) { killSound(); return }
                soundHandler.postDelayed(this, 80)
            }
        }
        soundHandler.post(step)
    }

    /** Cardul a dispărut: sunetul se oprește, dar ține cel puțin 4 secunde de la început. */
    fun stopAlert() {
        if (player == null) return
        val left = SOUND_MIN_MS - (System.currentTimeMillis() - soundStartedAt)
        soundHandler.removeCallbacks(soundMaxStop)
        if (left <= 0L) fadeOutSound() else soundHandler.postDelayed(soundMaxStop, left)
    }

    fun setCutout(r: Rect?) { cutout = r; view.invalidate() }

    private val verdict = TextView(ctx)
    private val payPill = TextView(ctx)
    private val tag = TextView(ctx)
    private val big = TextView(ctx)
    private val unit = TextView(ctx)
    private val total = TextView(ctx)
    private val pickup = TextView(ctx)
    private val trip = TextView(ctx)
    private val foot = TextView(ctx)
    private val scale = ScaleBar()
    private val scLow = TextView(ctx)
    private val scMid = TextView(ctx)
    private val scTop = TextView(ctx)

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun column(label: String, value: TextView): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val l = TextView(ctx)
        l.text = label
        l.textSize = 11f
        labels.add(l)
        value.textSize = 17f
        value.typeface = Typeface.DEFAULT_BOLD
        col.addView(l)
        col.addView(value)
        return col
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(dp(16), dp(12), dp(16), dp(12))

        // rândul de sus: verdictul și tipul plății (colțul din dreapta rămâne liber pentru „Respinge”)
        val topRow = LinearLayout(ctx)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        verdict.textSize = 13f
        verdict.typeface = Typeface.DEFAULT_BOLD
        verdict.letterSpacing = 0.05f
        verdict.setPadding(dp(10), dp(4), dp(10), dp(4))
        payPill.textSize = 11f
        payPill.typeface = Typeface.DEFAULT_BOLD
        payPill.letterSpacing = 0.04f
        payPill.gravity = Gravity.CENTER_VERTICAL
        payPill.setPadding(dp(8), dp(3), dp(8), dp(3))
        topRow.addView(verdict)
        val pLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        pLp.leftMargin = dp(6)
        topRow.addView(payPill, pLp)
        view.addView(topRow)

        val bigRow = LinearLayout(ctx)
        bigRow.orientation = LinearLayout.HORIZONTAL
        bigRow.gravity = Gravity.BOTTOM
        big.textSize = 42f
        big.typeface = Typeface.DEFAULT_BOLD
        unit.textSize = 14f
        unit.setPadding(dp(8), 0, 0, dp(9))
        bigRow.addView(big)
        bigRow.addView(unit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bigLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bigLp.topMargin = dp(2)
        view.addView(bigRow, bigLp)

        tag.textSize = 11f
        view.addView(tag)

        val scLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
        scLp.topMargin = dp(4)
        view.addView(scale, scLp)
        val scRow = LinearLayout(ctx)
        scRow.orientation = LinearLayout.HORIZONTAL
        for (t in listOf(scLow, scMid, scTop)) { t.textSize = 10.5f; labels.add(t) }
        scMid.gravity = Gravity.CENTER
        scTop.gravity = Gravity.END
        scRow.addView(scLow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        scRow.addView(scMid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        scRow.addView(scTop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(scRow)

        val stats = LinearLayout(ctx)
        stats.orientation = LinearLayout.HORIZONTAL
        val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stats.addView(column("Total", total), w)
        stats.addView(column("Până la pasager", pickup), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        stats.addView(column("Cu pasagerul", trip), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val statsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statsLp.topMargin = dp(8)
        view.addView(stats, statsLp)

        foot.textSize = 12f
        val footLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        footLp.topMargin = dp(8)
        view.addView(foot, footLp)
    }

    private fun levelCol(l: Level): Int = Color.parseColor(
        when (l) {
            Level.GOOD -> if (dark) "#4ADE9B" else "#1B7F55"
            Level.OK -> if (dark) "#F5B84A" else "#A8650A"
            Level.BAD -> if (dark) "#F58A74" else "#B4432F"
        }
    )

    fun bind(o: Offer, r: Result) {
        // aspectul cardului: luminos (standard) sau întunecat, din Setări
        dark = Store.raw(ctx, "card_dark") == "1"
        val ink = Color.parseColor(if (dark) "#F1F5F9" else "#0F172A")
        val muted = Color.parseColor(if (dark) "#9AA6B8" else "#64708A")
        val bgCol = Color.parseColor(if (dark) "#F21E293B" else "#FAFFFFFF")
        topColor = Color.parseColor(if (dark) "#B79BF2" else "#7A4FD0")
        val color = levelCol(r.level)

        cash = o.cash
        payColor = Color.parseColor(if (o.cash) (if (dark) "#E8B83A" else "#D9A21B") else (if (dark) "#6EA0F0" else "#2563C9"))
        view.background = Ui.rounded(bgCol, dp(22).toFloat(), payColor, dp(2) + dp(1) / 2)
        for (l in labels) l.setTextColor(muted)
        total.setTextColor(ink); pickup.setTextColor(ink); trip.setTextColor(ink)
        unit.setTextColor(muted); tag.setTextColor(muted); foot.setTextColor(muted)
        scale.mark = ink

        top = r.netPerKm >= (Store.raw(ctx, "top")?.replace(',', '.')?.toDoubleOrNull() ?: Ui.TOP_NPK)
        if (top) {
            val ak = "${o.price}|${o.totalKm}"
            if (ak != alertedFor) { alertedFor = ak; alertTop() }
        }
        val vc = if (top) topColor else color
        verdict.text = if (top) "DE NERATAT" else Ui.levelLabel(r.level)
        verdict.setTextColor(vc)
        verdict.background = Ui.rounded((vc and 0x00FFFFFF) or 0x2A000000, dp(8).toFloat())

        payPill.text = if (o.cash) "NUMERAR" else "CARD"
        payPill.setTextColor(payColor)
        payPill.background = Ui.rounded(0, dp(8).toFloat(), payColor, dp(1) + dp(1) / 2)
        val pd = ctx.getDrawable(if (o.cash) R.drawable.wm_cash else R.drawable.wm_card)?.mutate()
        if (pd != null) {
            pd.setTint(payColor)
            pd.setBounds(0, 0, dp(14), dp(14))
            payPill.setCompoundDrawables(pd, null, null, null)
            payPill.compoundDrawablePadding = dp(4)
        }
        view.invalidate()

        if (top && glowAnim == null) {
            // licărire discretă (în jur de o secundă), ca să nu obosească ochii
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 1100
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.addUpdateListener { glow = it.animatedValue as Float; view.invalidate() }
            a.start()
            glowAnim = a
        } else if (!top) {
            glowAnim?.cancel()
            glowAnim = null
        }

        val tg = when {
            r.short -> "cursă scurtă · bună oricum"
            o.pickupKnown && o.pickupKm > o.tripKm -> "drum lung până la pasager"
            else -> ""
        }
        tag.text = tg
        tag.visibility = if (tg.isEmpty()) View.GONE else View.VISIBLE

        big.text = Fmt.d2(r.netPerKm)
        big.setTextColor(vc)
        unit.text = "lei/km net"

        // scala: roșu sub prag, verde de la prag, violet de la „de neratat”
        val cfg = Store.config(ctx)
        val topNpk = Store.raw(ctx, "top")?.replace(',', '.')?.toDoubleOrNull() ?: Ui.TOP_NPK
        val mx = (topNpk / 0.9).coerceAtLeast(1.0)
        val rf = (cfg.redNetPerKm / mx).coerceIn(0.05, 0.9).toFloat()
        val gf = (cfg.greenNetPerKm / mx).coerceIn((rf + 0.02f).toDouble(), 0.93).toFloat()
        val tf = (topNpk / mx).coerceIn((gf + 0.02f).toDouble(), 0.98).toFloat()
        scale.stops = floatArrayOf(0f, rf, gf, tf, 1f)
        scale.frac = (r.netPerKm / mx).toFloat()
        scale.invalidate()
        scLow.text = "sub " + Fmt.d1(cfg.redNetPerKm)
        scMid.text = "peste " + Fmt.d1(cfg.greenNetPerKm)
        scTop.text = "peste " + Fmt.d1(topNpk)

        total.text = Fmt.d1(o.totalKm) + " km"
        pickup.text = if (o.pickupKnown) Fmt.d1(o.pickupKm) + " km" else "?"
        trip.text = Fmt.d1(o.tripKm) + " km"

        val sb = StringBuilder()
        sb.append("Net ").append(Fmt.d2(r.net)).append(" lei din ").append(Fmt.d2(o.price)).append(" brut")
        sb.append("  ·  ").append(Fmt.d2(r.grossPerKm)).append(" lei/km brut")
        sb.append("\nComision −").append(Fmt.d2(r.commission)).append("  ·  Mașină −").append(Fmt.d2(r.cost))
        val h = r.netPerHour
        if (h != null) sb.append("  ·  ").append(Math.round(h)).append(" lei/h")
        foot.text = sb.toString()
    }

    fun asView(): View = view
}
