package ro.drivescore

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Roata norocului: felii proporționale cu șansele reale. Rezultatul vine de la server; roata doar îl arată. */
class WheelView(ctx: Context) : View(ctx) {
    // ordine de la ora 12, în sensul acelor de ceasornic: prize_days, procent, culori (sus, jos), text
    private class Seg(val days: Int, val pct: Float, val c1: Int, val c2: Int, val label: String)
    private val segs = listOf(
        Seg(0, 60f, Color.parseColor("#D7DCE6"), Color.parseColor("#AEB6C6"), "Mai încearcă"),
        Seg(1, 25f, Color.parseColor("#5BE0A4"), Color.parseColor("#1E9A63"), "+1 zi"),
        Seg(3, 10f, Color.parseColor("#3F7BE0"), Color.parseColor("#1D4FA8"), "+3 zile"),
        Seg(7, 3f, Color.parseColor("#FBC85A"), Color.parseColor("#E39A1B"), ""),
        Seg(30, 2f, Color.parseColor("#9A72E8"), Color.parseColor("#6638C0"), "")
    )
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rot = 0f
    private var anim: ValueAnimator? = null
    private val logo = ctx.getDrawable(R.mipmap.ic_launcher)

    var spinning = false
        private set

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s + (s * 0.04f).toInt())
    }

    /** Pornește rotirea spre felia premiului primit de la server (0, 1, 3, 7 sau 30 de zile). */
    fun spinTo(prizeDays: Int, done: () -> Unit) {
        if (spinning) return
        val idx = segs.indexOfFirst { it.days == prizeDays }.coerceAtLeast(0)
        var start = 0f
        for (i in 0 until idx) start += segs[i].pct * 3.6f
        val size = segs[idx].pct * 3.6f
        val target = start + size * (0.2f + 0.6f * Math.random().toFloat())   // un punct din interiorul feliei
        val base = rot - (rot % 360f)
        val end = base + 360f * 6 + (360f - target)
        spinning = true
        anim = ValueAnimator.ofFloat(rot, end).apply {
            duration = 5200
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { rot = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { spinning = false; done() }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val cx = s / 2f
        val cy = s / 2f + s * 0.04f
        val rim = s * 0.025f
        val r = s / 2f - s * 0.035f

        // umbră + margine metalică
        p.shader = null; p.style = Paint.Style.FILL
        p.color = Color.argb(40, 15, 23, 42)
        c.drawCircle(cx, cy + s * 0.012f, r + rim * 1.6f, p)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, intArrayOf(Color.WHITE, Color.parseColor("#C9CED8"), Color.WHITE, Color.parseColor("#AEB4C0")), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r + rim * 1.5f, p)
        p.shader = null

        c.save()
        c.rotate(rot, cx, cy)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        var a = -90f
        for (sg in segs) {
            val sweep = sg.pct * 3.6f
            p.shader = LinearGradient(cx, cy - r, cx, cy + r, sg.c1, sg.c2, Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            c.drawArc(oval, a, sweep, true, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.006f
            p.color = Color.WHITE
            c.drawArc(oval, a, sweep, true, p)
            if (sg.label.isNotEmpty()) {
                val mid = Math.toRadians((a + sweep / 2f).toDouble())
                val tx = cx + (r * 0.62f) * Math.cos(mid).toFloat()
                val ty = cy + (r * 0.62f) * Math.sin(mid).toFloat()
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = s * 0.058f
                p.color = if (sg.days == 0) Color.parseColor("#334155") else Color.WHITE
                c.drawText(sg.label, tx, ty + p.textSize * 0.35f, p)
            }
            a += sweep
        }
        c.restore()

        // butonul din centru cu logo
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - s * 0.1f, 0f, cy + s * 0.1f, Color.WHITE, Color.parseColor("#C5CBD6"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.105f, p)
        p.shader = null
        p.color = Color.WHITE
        c.drawCircle(cx, cy, s * 0.088f, p)
        logo?.let {
            val h = (s * 0.075f).toInt()
            it.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
            it.draw(c)
        }

        // ac (fix, sus)
        val pw = s * 0.05f
        val py = cy - r - rim * 1.6f
        val path = Path()
        path.moveTo(cx - pw, py - pw * 0.4f)
        path.lineTo(cx + pw, py - pw * 0.4f)
        path.lineTo(cx, py + pw * 2.1f)
        path.close()
        p.color = Color.parseColor("#0F172A")
        c.drawPath(path, p)
        p.color = Color.WHITE
        c.drawCircle(cx, py + pw * 0.1f, pw * 0.28f, p)
    }
}
