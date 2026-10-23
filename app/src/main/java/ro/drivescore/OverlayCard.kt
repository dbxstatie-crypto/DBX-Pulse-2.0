package ro.drivescore

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#06120C")
    val SURFACE = Color.parseColor("#0E1F16")
    val SURFACE2 = Color.parseColor("#15392A")
    val TEXT = Color.parseColor("#E8F7EE")
    val MUTED = Color.parseColor("#86A595")
    val GREEN = Color.parseColor("#2BE673")
    val AMBER = Color.parseColor("#F5B301")
    val RED = Color.parseColor("#EF4444")
    val ON_ACCENT = Color.parseColor("#04140B")
    val ACCENT = Color.parseColor("#22D866")

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
    val view = LinearLayout(ctx)

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
        l.setTextColor(Ui.MUTED)
        value.textSize = 17f
        value.setTextColor(Ui.TEXT)
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
        tag.setTextColor(Ui.MUTED)
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
        unit.setTextColor(Ui.MUTED)
        unit.setPadding(dp(8), 0, 0, dp(9))
        bigRow.addView(big)
        bigRow.addView(unit)
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
        foot.setTextColor(Ui.MUTED)
        val footLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        footLp.topMargin = dp(10)
        view.addView(foot, footLp)
    }

    fun bind(o: Offer, r: Result) {
        val color = Ui.levelColor(r.level)
        view.background = Ui.rounded(Color.parseColor("#F2111827"), dp(22).toFloat(), color, dp(2))

        verdict.text = Ui.levelLabel(r.level)
        verdict.setTextColor(Ui.BG)
        verdict.background = Ui.rounded(color, dp(14).toFloat())

        tag.text = when {
            r.short -> "cursă scurtă · bună oricum"
            o.pickupKnown && o.pickupKm > o.tripKm -> "drum lung până la pasager"
            else -> ""
        }

        big.text = Fmt.d2(r.netPerKm)
        big.setTextColor(color)
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
