package ro.drivescore

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
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

    // culori care urmează tema aleasă (întunecată / luminoasă) și culoarea principală
    var BG = D_BG
    var SURFACE = D_SURFACE
    var SURFACE2 = D_SURFACE2
    var TEXT = D_TEXT
    var MUTED = D_MUTED
    var BRAND = Color.parseColor("#2BE673")
    var ACCENT = Color.parseColor("#22D866")
    var ON_ACCENT = Color.parseColor("#04140B")

    val GREEN = Color.parseColor("#2BE673")
    val TOP = Color.parseColor("#B66DFF")   // „DE NERATAT”
    const val TOP_NPK = 4.5                 // lei/km net de la care cursa e „de neratat”
    val AMBER = Color.parseColor("#F5B301")
    val RED = Color.parseColor("#EF4444")

    fun accentOf(key: String, light: Boolean): Int = Color.parseColor(
        when (key) {
            "blue" -> if (light) "#1E6FD9" else "#3BA7FF"
            "red" -> if (light) "#D63048" else "#FF5A6B"
            "purple" -> if (light) "#7A4DD8" else "#B07CFF"
            else -> if (light) "#12A150" else "#2BE673"
        }
    )

    fun onAccentOf(key: String, light: Boolean): Int =
        if (light || key == "red") Color.WHITE else Color.parseColor("#04140B")

    fun apply(c: Context) {
        val light = Store.raw(c, "theme") == "light"
        val key = Store.raw(c, "accent") ?: "green"
        if (light) {
            BG = Color.parseColor("#EEF4F0"); SURFACE = Color.parseColor("#FFFFFF"); SURFACE2 = Color.parseColor("#DDEBE2")
            TEXT = Color.parseColor("#0E2217"); MUTED = Color.parseColor("#55705F")
        } else {
            BG = D_BG; SURFACE = D_SURFACE; SURFACE2 = D_SURFACE2; TEXT = D_TEXT; MUTED = D_MUTED
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
    private var cash = false
    private var payColor = Ui.GREEN
    private var cutout: Rect? = null
    private var alertedFor = ""
    private var top = false          // „DE NERATAT”: net/km foarte mare
    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Cardul; desenează în fundal CARD/CASH și lasă o „fereastră” goală peste butonul Respinge din Bolt. */
    val view = object : LinearLayout(ctx) {
        init { setWillNotDraw(false) }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val d = ctx.getDrawable(if (cash) R.drawable.wm_cash else R.drawable.wm_card)?.mutate() ?: return
            d.setTint(payColor)
            d.alpha = 75
            if (this@OverlayCard.top) {
                glowPaint.style = Paint.Style.STROKE
                glowPaint.strokeWidth = dp(3).toFloat()
                glowPaint.color = Ui.TOP
                glowPaint.alpha = (45 + 110 * glow).toInt()
                val i = dp(5).toFloat()
                c.drawRoundRect(i, i, width - i, height - i, dp(18).toFloat(), dp(18).toFloat(), glowPaint)
            }
            val s = (height * 0.7f).toInt()
            val right = width - dp(14)
            d.setBounds(right - s, dp(10), right, dp(10) + s)
            d.draw(c)
            wmPaint.color = payColor
            wmPaint.alpha = 100
            wmPaint.textSize = dp(34).toFloat()
            wmPaint.typeface = Typeface.DEFAULT_BOLD
            wmPaint.textAlign = Paint.Align.RIGHT
            val px = (width - dp(14)).toFloat()
            val py = (height - dp(26)).toFloat()
            c.save()
            c.rotate(-16f, px, py)
            c.drawText(if (cash) "CASH" else "CARD", px, py, wmPaint)
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

    /** Vibrație + un semnal scurt când apare o cursă „DE NERATAT” (se poate opri din Setări). */
    private fun alertTop() {
        if (Store.raw(ctx, "alert_top") == "0") return
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } catch (e: Exception) { }
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 300)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 700)
        } catch (e: Exception) { }
    }

    fun setCutout(r: Rect?) { cutout = r; view.invalidate() }

    private val verdict = TextView(ctx)
    private val tag = TextView(ctx)
    private val big = TextView(ctx)
    private val unit = TextView(ctx)
    private val total = TextView(ctx)
    private val pickup = TextView(ctx)
    private val trip = TextView(ctx)
    private val foot = TextView(ctx)

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun column(label: String, value: TextView): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val l = TextView(ctx)
        l.text = label
        l.textSize = 11f
        l.setTextColor(Ui.D_MUTED)
        value.textSize = 17f
        value.setTextColor(Ui.D_TEXT)
        value.typeface = Typeface.DEFAULT_BOLD
        col.addView(l)
        col.addView(value)
        return col
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(dp(16), dp(12), dp(16), dp(12))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        verdict.textSize = 13f
        verdict.typeface = Typeface.DEFAULT_BOLD
        verdict.letterSpacing = 0.06f
        verdict.setPadding(dp(12), dp(5), dp(12), dp(5))
        tag.textSize = 12f
        tag.setTextColor(Ui.D_MUTED)
        tag.gravity = Gravity.END
        top.addView(verdict)
        top.addView(tag, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(top)

        val bigRow = LinearLayout(ctx)
        bigRow.orientation = LinearLayout.HORIZONTAL
        bigRow.gravity = Gravity.BOTTOM
        big.textSize = 40f
        big.typeface = Typeface.DEFAULT_BOLD
        unit.textSize = 14f
        unit.setTextColor(Ui.D_MUTED)
        unit.setPadding(dp(8), 0, 0, dp(9))
        bigRow.addView(big)
        bigRow.addView(unit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bigLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bigLp.topMargin = dp(2)
        view.addView(bigRow, bigLp)

        val stats = LinearLayout(ctx)
        stats.orientation = LinearLayout.HORIZONTAL
        val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stats.addView(column("KM TOTAL", total), w)
        stats.addView(column("PÂNĂ LA PASAGER", pickup), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        stats.addView(column("CU PASAGERUL", trip), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val statsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statsLp.topMargin = dp(8)
        view.addView(stats, statsLp)

        foot.textSize = 12f
        foot.setTextColor(Ui.D_MUTED)
        val footLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        footLp.topMargin = dp(10)
        view.addView(foot, footLp)
    }

    fun bind(o: Offer, r: Result) {
        val color = Ui.levelColor(r.level)
        cash = o.cash
        payColor = if (o.cash) Ui.AMBER else Ui.GREEN
        view.background = Ui.rounded(Color.parseColor(if (o.cash) "#F0261D07" else "#F00A2420"), dp(22).toFloat(), payColor, dp(3))
        view.invalidate()

        top = r.netPerKm >= Ui.TOP_NPK
        if (top) {
            val ak = "${o.price}|${o.totalKm}"
            if (ak != alertedFor) { alertedFor = ak; alertTop() }
        }
        verdict.text = if (top) "DE NERATAT" else Ui.levelLabel(r.level)
        verdict.setTextColor(Ui.D_BG)
        verdict.background = Ui.rounded(if (top) Ui.TOP else color, dp(14).toFloat())
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

        tag.text = when {
            r.short -> "cursă scurtă · bună oricum"
            o.pickupKnown && o.pickupKm > o.tripKm -> "drum lung până la pasager"
            else -> ""
        }

        big.text = Fmt.d2(r.netPerKm)
        big.setTextColor(if (top) Ui.TOP else color)
        unit.text = "lei/km net"

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
