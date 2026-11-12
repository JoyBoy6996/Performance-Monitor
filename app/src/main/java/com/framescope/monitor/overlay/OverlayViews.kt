package com.framescope.monitor.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers to build the classic, game friendly overlay widgets. */
object OverlayViews {

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()

    fun label(context: Context, text: String, color: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
    }

    fun value(context: Context, text: String, color: Int, sizeSp: Float = 12f): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.END
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 1f) }
    }

    fun fpsColor(fps: Float, target: Int): Int = when {
        fps >= target -> Color.parseColor("#4ADE80")
        fps >= target * 0.75f -> Color.parseColor("#FACC15")
        fps >= target * 0.5f -> Color.parseColor("#FB923C")
        fps > 0 -> Color.parseColor("#F87171")
        else -> Color.parseColor("#94A3B8")
    }
}

/** Lightweight line graph used by the overlay and the dashboard preview. */
class GraphView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#4ADE80")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#334ADE80")
    }
    private val path = Path()
    private val fillPath = Path()

    private var values: List<Float> = emptyList()
    private var ceiling: Float = 60f

    fun setValues(list: List<Float>, target: Float = 60f) {
        values = list
        ceiling = maxOf(target * 1.25f, (list.maxOrNull() ?: target) * 1.1f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (values.size - 1)
        path.reset()
        fillPath.reset()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = h - (value / ceiling).coerceIn(0f, 1f) * h
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, paint)
    }
}
