package com.hardwarescope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object Pal {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171B22.toInt()
    const val SURFACE2 = 0xFF202633.toInt()
    const val BORDER = 0xFF2A3140.toInt()
    const val TEXT = 0xFFE6EAF2.toInt()
    const val MUTED = 0xFF8A94A6.toInt()
    const val ACCENT = 0xFF3DDC97.toInt()
    const val BLUE = 0xFF4EA8FF.toInt()
    const val WARN = 0xFFFFB547.toInt()
    const val BAD = 0xFFFF5C6C.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.label(s: CharSequence, size: Float = 14f, color: Int = Pal.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, strokeColor)
}

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

/** Pill-shaped button built from a TextView (no AndroidX / Material dependency). */
fun Context.pill(text: String, filled: Boolean = false, onClick: () -> Unit): TextView = label(text, 13f, if (filled) Pal.BG else Pal.TEXT, true).apply {
    background = rounded(if (filled) Pal.ACCENT else Pal.SURFACE2, dp(18).toFloat(), if (filled) 0 else dp(1), Pal.BORDER)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    gravity = Gravity.CENTER
    isClickable = true
    setOnClickListener { onClick() }
}

/** A rolling line chart of the last 90 samples. */
class Sparkline(ctx: Context) : View(ctx) {
    private val data = FloatArray(90)
    private var count = 0
    private var head = 0
    var title = ""
    var unit = ""
    var fixedMax: Float? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); strokeJoin = Paint.Join.ROUND; color = Pal.ACCENT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (Pal.ACCENT and 0x00FFFFFF) or 0x2A000000 }
    private val grid = Paint().apply { color = Pal.BORDER; strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.MUTED; textSize = ctx.dp(11).toFloat() }
    private val val_ = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; textSize = ctx.dp(12).toFloat(); textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val path = Path()

    fun push(v: Float) {
        data[head] = v; head = (head + 1) % data.size; if (count < data.size) count++; invalidate()
    }

    fun reset() {
        count = 0; head = 0; invalidate()
    }

    private fun at(i: Int) = data[(head - count + i + data.size) % data.size]

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val top = txt.textSize + context.dp(6)
        canvas.drawText(title, 0f, txt.textSize, txt)
        if (count > 0) {
            val cur = at(count - 1)
            canvas.drawText(if (kotlin.math.abs(cur) >= 100) f("%.0f %s", cur, unit) else f("%.1f %s", cur, unit), w, txt.textSize, val_)
        }
        canvas.drawLine(0f, h - 1, w, h - 1, grid)
        canvas.drawLine(0f, top, w, top, grid)
        if (count < 2) return
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until count) {
            mn = minOf(mn, at(i)); mx = maxOf(mx, at(i))
        }
        fixedMax?.let { mn = 0f; mx = maxOf(it, mx) }
        if (fixedMax == null) mn = minOf(mn, 0f)
        val range = if (mx - mn < 1e-6f) 1f else mx - mn
        val step = w / (data.size - 1)
        val plotH = h - top - 2
        path.reset()
        for (i in 0 until count) {
            val x = w - (count - 1 - i) * step
            val y = top + plotH - (at(i) - mn) / range * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        path.lineTo(w, h); path.lineTo(w - (count - 1) * step, h); path.close()
        canvas.drawPath(path, fill)
    }
}

/** One hardware category card: header, specification rows, live rows and a graph. */
class HwCard(private val ctx: Context, val cat: Category, collapsed: Boolean, private val onCollapse: (Boolean) -> Unit) {
    val root: LinearLayout = ctx.vbox()
    private val body = ctx.vbox()
    private val staticBox = ctx.vbox()
    private val liveBox = ctx.vbox()
    private val spark = Sparkline(ctx)
    private val liveViews = LinkedHashMap<String, TextView>()
    private val badge: TextView
    private val chevron: TextView
    private var showAll = false
    private var staticRows: List<Row> = emptyList()
    private var isCollapsed = collapsed

    init {
        val d = ctx.dp(1)
        root.background = rounded(Pal.SURFACE, d * 14f, d, Pal.BORDER)
        root.setPadding(d * 14, d * 12, d * 14, d * 12)
        val head = ctx.hbox()
        head.addView(ctx.label(cat.icon, 18f), lp(WRAP, WRAP).apply { rightMargin = d * 10 })
        head.addView(ctx.label(cat.title, 16f, Pal.TEXT, true), lp(0, WRAP, 1f))
        badge = ctx.label("● LIVE", 10f, Pal.ACCENT, true).apply {
            background = rounded((Pal.ACCENT and 0x00FFFFFF) or 0x22000000, d * 8f)
            setPadding(d * 8, d * 3, d * 8, d * 3)
        }
        head.addView(badge, lp(WRAP, WRAP).apply { rightMargin = d * 8 })
        chevron = ctx.label(if (collapsed) "▸" else "▾", 18f, Pal.MUTED)
        head.addView(chevron)
        head.setOnClickListener {
            isCollapsed = !isCollapsed
            body.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            chevron.text = if (isCollapsed) "▸" else "▾"
            onCollapse(isCollapsed)
        }
        root.addView(head)
        body.addView(staticBox)
        body.addView(liveBox)
        spark.visibility = View.GONE
        body.addView(spark, lp(MATCH, d * 70).apply { topMargin = d * 10 })
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        root.addView(body, lp().apply { topMargin = d * 6 })
        setLiveEnabled(false)
    }

    private fun rowView(name: String, value: String, valueColor: Int = Pal.TEXT): Pair<View, TextView> {
        val d = ctx.dp(1)
        val r = ctx.hbox().apply { gravity = Gravity.TOP; setPadding(0, d * 5, 0, d * 5) }
        r.addView(ctx.label(name, 13f, Pal.MUTED), lp(0, WRAP, 0.42f).apply { rightMargin = d * 10 })
        val v = ctx.label(value, 13f, valueColor)
        r.addView(v, lp(0, WRAP, 0.58f))
        return r to v
    }

    private fun valueColor(v: String) = when {
        v == "Yes" || v.startsWith("Available") -> Pal.ACCENT
        v == "No" || v == "N/A" || v.startsWith("Not ") || v.startsWith("None") -> Pal.MUTED
        v == "Permission needed" -> Pal.WARN
        else -> Pal.TEXT
    }

    fun setStatic(rows: List<Row>) {
        staticRows = rows
        renderStatic()
    }

    private fun renderStatic() {
        staticBox.removeAllViews()
        val limit = if (showAll) Int.MAX_VALUE else 12
        staticRows.take(limit).forEach { staticBox.addView(rowView(it.name, it.value, valueColor(it.value)).first) }
        if (staticRows.size > 12) {
            staticBox.addView(ctx.label(if (showAll) "Show less ▴" else "Show all ${staticRows.size} ▾", 13f, Pal.BLUE, true).apply {
                setPadding(0, ctx.dp(8), 0, ctx.dp(4))
                setOnClickListener { showAll = !showAll; renderStatic() }
            })
        }
    }

    fun setLiveEnabled(on: Boolean) {
        badge.visibility = if (on && cat.hasLive) View.VISIBLE else View.GONE
        liveBox.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) {
            spark.visibility = View.GONE; spark.reset()
        }
    }

    fun setLive(l: Live) {
        if (l.rows.map { it.name } != liveViews.keys.toList()) {
            liveBox.removeAllViews(); liveViews.clear()
            if (l.rows.isNotEmpty()) {
                liveBox.addView(View(ctx).apply { setBackgroundColor(Pal.BORDER) }, lp(MATCH, 1).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(4) })
                liveBox.addView(ctx.label("LIVE", 10f, Pal.ACCENT, true).apply { letterSpacing = 0.15f })
            }
            l.rows.forEach { val (v, t) = rowView(it.name, it.value, valueColor(it.value)); liveBox.addView(v); liveViews[it.name] = t }
        } else {
            l.rows.forEach { r -> liveViews[r.name]?.let { it.text = r.value; it.setTextColor(valueColor(r.value)) } }
        }
        if (l.spark != null && !l.spark.isNaN()) {
            spark.visibility = View.VISIBLE
            spark.title = l.sparkLabel; spark.unit = l.sparkUnit; spark.fixedMax = l.sparkMax
            spark.push(l.spark)
        }
    }
}

/** Interactive multi-touch test surface. */
class TouchTestView(ctx: Context) : View(ctx) {
    var maxPointers = 0
        private set
    private val pts = HashMap<Int, Pair<Float, Float>>()
    private val colors = intArrayOf(Pal.ACCENT, Pal.BLUE, Pal.WARN, Pal.BAD, Color.MAGENTA, Color.CYAN, Color.YELLOW, Color.WHITE, 0xFFFF8A00.toInt(), 0xFF9C6BFF.toInt())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; textSize = ctx.dp(22).toFloat(); textAlign = Paint.Align.CENTER }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.MUTED; textSize = ctx.dp(14).toFloat(); textAlign = Paint.Align.CENTER }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pts.clear()
        val up = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        for (i in 0 until event.pointerCount) {
            if (up) break
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
            pts[event.getPointerId(i)] = event.getX(i) to event.getY(i)
        }
        maxPointers = maxOf(maxPointers, pts.size)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Pal.BG)
        canvas.drawText("Touches: ${pts.size}   (max ${maxPointers})", width / 2f, height * 0.18f, t)
        canvas.drawText("Use as many fingers as you can · press Back to finish", width / 2f, height * 0.18f + context.dp(30), s)
        for ((id, xy) in pts) {
            p.color = colors[id % colors.size]
            p.style = Paint.Style.FILL; p.alpha = 70
            canvas.drawCircle(xy.first, xy.second, context.dp(48).toFloat(), p)
            p.style = Paint.Style.STROKE; p.alpha = 255; p.strokeWidth = context.dp(3).toFloat()
            canvas.drawCircle(xy.first, xy.second, context.dp(48).toFloat(), p)
            canvas.drawLine(0f, xy.second, width.toFloat(), xy.second, p.apply { strokeWidth = 1f })
            canvas.drawLine(xy.first, 0f, xy.first, height.toFloat(), p)
        }
    }
}

/** Full-screen solid colours for spotting dead or stuck pixels. */
class ColorTestView(ctx: Context, private val onDone: () -> Unit) : View(ctx) {
    private val seq = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK, Color.GRAY)
    private var i = 0

    init {
        setOnClickListener { i++; if (i >= seq.size) onDone() else invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(seq[minOf(i, seq.size - 1)])
    }
}
