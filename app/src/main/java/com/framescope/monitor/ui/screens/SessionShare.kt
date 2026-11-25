package com.framescope.monitor.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.framescope.monitor.core.MAX_BATTERY_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.ShellLogs
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.chartTemperatures
import com.framescope.monitor.core.chipTemperatures
import com.framescope.monitor.core.frameTimeCeiling
import com.framescope.monitor.core.hardwareCharts
import com.framescope.monitor.core.plausibleTempC
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bad numbers become "--" everywhere in a shared report. */
private fun fmt(value: Float?, decimals: Int): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

private fun List<Float>.clean(): List<Float> = mapNotNull { it.finiteOrNull() }
private fun List<Float>.positive(): List<Float> = clean().filter { it > 0f }
private fun List<Float>.hotspot(): List<Float> = mapNotNull { it.plausibleTempC(TEMP_MAX_C) }
private fun List<Float>.battery(): List<Float> =
    mapNotNull { it.plausibleTempC(MAX_BATTERY_TEMP_C) }
private fun List<Float>.clamped(min: Float, max: Float): List<Float> =
    clean().map { it.coerceIn(min, max) }
private fun List<Float>.avgOrNull(): Float? =
    clean().takeIf { it.isNotEmpty() }?.average()?.toFloat()

private fun column(rows: List<List<Float?>>, index: Int): List<Float> =
    rows.mapNotNull { it.getOrNull(index)?.finiteOrNull() }

private fun intColumn(rows: List<List<Int?>>, index: Int): List<Float> =
    rows.mapNotNull { it.getOrNull(index)?.toFloat()?.finiteOrNull() }

private fun duration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
    else String.format(Locale.US, "%dm %02ds", m, s)
}

private fun tick(ms: Long): String {
    val total = ms / 1000
    if (total <= 0L) return "0"
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h${m}m${s}s"
        m > 0 -> "${m}m${s}s"
        else -> "${s}s"
    }
}

private fun timeLabels(session: SessionRecord): List<String> =
    (0..5).map { tick(session.durationMs * it / 5L) }

/** Named CPU/SoC sensor, with honest legacy fallbacks. Battery skin never wins. */
private fun temperatureSeries(session: SessionRecord): List<Float> =
    session.chipTemperatures().hotspot()

/**
 * The temperature the chart of the frame rate draws.
 *
 * It is the temperature of the chip, and the battery of an old run that
 * stored nothing else.
 */
private fun chartTemperatureSeries(session: SessionRecord): List<Float> =
    session.chartTemperatures().hotspot()

private fun batteryTemperatureSeries(session: SessionRecord): List<Float> =
    session.batteryTempSeries.battery()

/** Mean chip temperature; an old run may only have the recorded session peak. */
private fun avgSoc(session: SessionRecord): Float? =
    temperatureSeries(session).avgOrNull()
        ?: session.maxTempC?.plausibleTempC(TEMP_MAX_C)

/** Peak BatteryManager reading; a legacy run may only carry the session peak. */
private fun maxBattery(session: SessionRecord): Float? =
    batteryTemperatureSeries(session).maxOrNull()
        ?: session.maxTempC?.plausibleTempC(MAX_BATTERY_TEMP_C)

private fun allFrequencies(session: SessionRecord): List<Float> =
    session.cpuGroupFreqSeries.flatMap { row -> row.mapNotNull { it?.finiteOrNull() } }
        .positive()
        .ifEmpty { session.cpuFreqSeries.positive() }

/** Ticks are always top-to-bottom: largest value first. */
private fun scale(max: Float, steps: Int, decimals: Int = 0): List<String> =
    (steps downTo 0).map { fmt(max * it / steps, decimals) }

private const val TEMP_MAX_C = 110f
private const val PAGE_W = 1080
// The report is 1080px wide, so one dp is three pixels: the outer margin is
// 40px, the radius of a card is 12dp and the gap between two cards is 10dp.
// The page keeps 48px above the first card and 48px below the last one, so
// the top row of the device card is never cut off by the edge of the paper.
private const val MARGIN = 40f
private const val TOP_MARGIN = 48f
private const val BOTTOM_MARGIN = 48f
private const val PAD = 36f
private const val GAP = 30f
private const val RADIUS = 36f
/** Reserved right-axis strip and the gutter that keeps lines off its numbers. */
private const val RIGHT_AXIS_W = 60f
private const val PLOT_EDGE_GAP = 24f

private object ReportTheme {
    val background = Color.parseColor("#0D0D0F")
    val card = Color.parseColor("#18191C")
    /** The bright edge of a card, so a printed page still shows its cards. */
    val cardBorder = Color.parseColor("#3A3E48")
    val grid = Color.parseColor("#26282E")
    val text = Color.parseColor("#E6E8EC")
    val muted = Color.parseColor("#8E929B")
    val dim = Color.parseColor("#6E737D")
    val blue = Color.parseColor("#3897F0")
    val fps = Color.parseColor("#E8EAED")
    val temp = Color.parseColor("#D97736")
    val cpu = Color.parseColor("#E056FD")
    val gpu = Color.parseColor("#4FC3F7")
    val total = Color.parseColor("#00E5FF")
    val frame = Color.parseColor("#7CC4FA")
    val power = Color.parseColor("#2962FF")
    val capacity = Color.parseColor("#90CAF9")
    val chipTemp = Color.parseColor("#81D4FA")
    val warnBack = Color.parseColor("#2A211B")
    val warn = Color.parseColor("#FF8C42")
    val platform = Color.parseColor("#7B61FF")
    val host = Color.parseColor("#4A90E2")
    val os = Color.parseColor("#3DDC84")
    val profile = Color.parseColor("#FF6B35")
    val cluster = intArrayOf(
        Color.parseColor("#9C27B0"), Color.parseColor("#00BFA5"),
        Color.parseColor("#2979FF"), Color.parseColor("#FF9100")
    )
}

private data class ChartLine(
    val label: String,
    val values: List<Float>,
    val color: Int,
    val min: Float,
    val max: Float,
    val spikes: Boolean = false,
    val width: Float = 3f,
    /** Uses the exact TEMP formula instead of the generic min/max formula. */
    val temperature: Boolean = false
)

private fun peakSoc(session: SessionRecord): Float? =
    listOfNotNull(session.maxTempC, temperatureSeries(session).maxOrNull())
        .mapNotNull { it.plausibleTempC(TEMP_MAX_C) }
        .maxOrNull()

/** Height of the last card is measured from the rows it actually contains. */
private fun detailHeight(session: SessionRecord): Float {
    val coreRows = minOf(
        maxOf(session.perCoreLoadSeries.size, session.perCoreFreqSeries.size), 8
    )
    val threadRows = minOf(session.threadNames.size, 6)
    // Only the lines that carry a real reading are printed: the identity line
    // and the lines under it.
    val logRows = ShellLogs.realLines(session.shellLogs).size.coerceIn(1, 13)
    return 440f + coreRows * 32f + threadRows * 32f + logRows * 24f
}

/** One measured height per card, in the exact order the report draws them. */
private fun cardHeights(session: SessionRecord): List<Float> = buildList {
    // Card 1 exists only for a genuinely hot chip. Below 85 °C it is not drawn,
    // so it must not reserve height either or every later card would shift.
    if ((peakSoc(session) ?: 0f) >= 85f) add(104f) // 1 thermal warning
    add(190f)                 // 2 device info
    // The note card only exists when there is a note. "Note not set" is a
    // reminder for the app, not a line of the report.
    if (session.note.isNotBlank()) add(96f)      // 3 note
    add(350f)                 // 4 game header + 2x4 KPI grid
    add(600f)                 // 5 FPS & temperature
    add(520f)                 // 6 frame time
    // The charts of the hardware are only drawn when the run measured them.
    // An empty chart is a box with nothing in it, and the app and the report
    // use the same answer.
    val charts = session.hardwareCharts()
    if (charts.cpuUsage) add(560f)           // 7 CPU usage
    if (charts.cpuFrequency) add(600f)       // 8 CPU frequency
    add(520f)                 // 9 power & capacity
    if (charts.cpuTemperature) add(520f)     // 10 CPU temperature
    add(detailHeight(session)) // 11 device detail + shell logs
}



/** One continuous page: no split cards, no empty pages, no watermark. */
private fun reportHeight(session: SessionRecord): Int {
    val heights = cardHeights(session)
    return (heights.sum() + GAP * (heights.size - 1) + TOP_MARGIN + BOTTOM_MARGIN).toInt()
}

/** Crisp vector glyphs, replacing solid squares in the device row. */
private fun drawDeviceIcon(canvas: Canvas, kind: Int, cx: Float, cy: Float, color: Int) {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    when (kind) {
        0 -> {
            canvas.drawRoundRect(RectF(cx - 12f, cy - 12f, cx + 12f, cy + 12f), 4f, 4f, stroke)
            canvas.drawRoundRect(RectF(cx - 6f, cy - 6f, cx + 6f, cy + 6f), 2f, 2f, fill)
            (-8..8 step 8).forEach { offset ->
                canvas.drawLine(cx + offset, cy - 18f, cx + offset, cy - 12f, stroke)
                canvas.drawLine(cx + offset, cy + 12f, cx + offset, cy + 18f, stroke)
                canvas.drawLine(cx - 18f, cy + offset, cx - 12f, cy + offset, stroke)
                canvas.drawLine(cx + 12f, cy + offset, cx + 18f, cy + offset, stroke)
            }
        }
        1 -> {
            canvas.drawRoundRect(RectF(cx - 10f, cy - 16f, cx + 10f, cy + 16f), 4f, 4f, stroke)
            canvas.drawLine(cx - 4f, cy + 11f, cx + 4f, cy + 11f, stroke)
        }
        2 -> {
            canvas.drawArc(RectF(cx - 10f, cy - 14f, cx + 10f, cy + 6f), 180f, 180f, false, stroke)
            canvas.drawRoundRect(RectF(cx - 9f, cy - 2f, cx + 9f, cy + 14f), 4f, 4f, stroke)
            canvas.drawCircle(cx - 4f, cy - 6f, 1.5f, fill)
            canvas.drawCircle(cx + 4f, cy - 6f, 1.5f, fill)
        }
        else -> {
            canvas.drawArc(RectF(cx - 14f, cy - 12f, cx + 14f, cy + 16f), 200f, 140f, false, stroke)
            canvas.drawLine(cx, cy + 4f, cx + 8f, cy - 8f, stroke)
            canvas.drawCircle(cx, cy + 4f, 2f, fill)
        }
    }
}

private fun drawGamepad(canvas: Canvas, x: Float, y: Float, color: Int) {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(RectF(x, y, x + 46f, y + 38f), 10f, 10f, stroke)
    canvas.drawLine(x + 12f, y + 12f, x + 12f, y + 26f, stroke)
    canvas.drawLine(x + 5f, y + 19f, x + 19f, y + 19f, stroke)
    canvas.drawCircle(x + 33f, y + 14f, 2.5f, fill)
    canvas.drawCircle(x + 39f, y + 23f, 2.5f, fill)
}

/**
 * Draws the eleven Scene cards into one canvas at 1080 px logical width.
 *
 * Chart Y is always upward: chartBottom - ((value - min) / (max - min)) * chartHeight.
 * For a temperature line that is exactly
 * chartBottom - ((tempC.coerceIn(0,110) / 110) * chartHeight).
 */
private class SceneReportRenderer(
    private val context: Context,
    private val canvas: Canvas,
    session: SessionRecord
) {
    private val heights = cardHeights(session)
    private var cardIndex = 0
    private var cursor = TOP_MARGIN

    private fun paint(size: Float, style: Int, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
        this.color = color
    }

    private val title = paint(30f, Typeface.BOLD, ReportTheme.text)
    private val header = paint(26f, Typeface.BOLD, ReportTheme.text)
    private val body = paint(24f, Typeface.NORMAL, ReportTheme.text)
    private val label = paint(21f, Typeface.NORMAL, ReportTheme.muted)
    private val axisLeft = paint(17f, Typeface.NORMAL, ReportTheme.dim).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val axisRight = paint(17f, Typeface.NORMAL, ReportTheme.dim).apply {
        textAlign = Paint.Align.LEFT
    }
    private val axisCenter = paint(17f, Typeface.NORMAL, ReportTheme.dim).apply {
        textAlign = Paint.Align.CENTER
    }
    private val kpi = paint(36f, Typeface.BOLD, ReportTheme.blue).apply {
        textAlign = Paint.Align.CENTER
    }
    private val kpiLabel = paint(17f, Typeface.NORMAL, ReportTheme.dim).apply {
        textAlign = Paint.Align.CENTER
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        color = ReportTheme.dim
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.card }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReportTheme.cardBorder
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.warnBack }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReportTheme.grid
        strokeWidth = 1.5f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(value: String, x: Float, y: Float, style: Paint) =
        canvas.drawText(value, x, y, style)

    private fun fit(value: String, width: Float, style: Paint): String {
        if (style.measureText(value) <= width) return value
        var short = value
        while (short.isNotEmpty() && style.measureText("$short…") > width) short = short.dropLast(1)
        return "$short…"
    }

    private fun card(back: Paint = cardPaint, draw: (Float, Float, Float, Float) -> Unit) {
        val height = heights.getOrElse(cardIndex) { 200f }
        cardIndex++
        val rect = RectF(MARGIN, cursor, PAGE_W - MARGIN, cursor + height)
        canvas.drawRoundRect(rect, RADIUS, RADIUS, back)
        // The card keeps a bright edge on the dark page: a printed report is
        // read on paper, where a card without an edge is a grey block.
        canvas.drawRoundRect(rect, RADIUS, RADIUS, borderPaint)
        draw(MARGIN + PAD, cursor + PAD, PAGE_W - MARGIN - PAD, cursor + height - PAD)
        cursor += height + GAP
    }

    fun start() {
        canvas.drawColor(ReportTheme.background)
    }

    /** Card 1 is a warning only; a cool chip must not produce a false status strip. */
    fun thermalStatus(peak: Float?, secondsAbove90: Int) {
        if ((peak ?: 0f) < 85f) return
        card(warnPaint) { x0, y0, x1, y1 ->
            val baseline = (y0 + y1) / 2f + 8f
            body.color = ReportTheme.warn
            text(
                fit("Soc temperature too high MAX:${fmt(peak, 1)}°C", (x1 - x0) * 0.65f, body),
                x0, baseline, body
            )
            body.textAlign = Paint.Align.RIGHT
            text("≥90°C ${secondsAbove90}S", x1, baseline, body)
            body.textAlign = Paint.Align.LEFT
            body.color = ReportTheme.text
        }
    }

    fun deviceRow(session: SessionRecord) {
        card { x0, y0, x1, _ ->
            fun clean(value: String?, fallback: String = "--"): String =
                value?.takeIf { it.isNotBlank() } ?: fallback
            val entries: List<Pair<String, String>> = listOf(
                Pair("Platform", clean(session.device.platform?.uppercase(Locale.US))),
                Pair(
                    "Host",
                    clean(
                        session.device.model?.replace(
                            Regex("^vivo\\s+", RegexOption.IGNORE_CASE), ""
                        )
                    )
                ),
                Pair("OS", clean(session.device.osVersion?.substringBefore(" ("))),
                Pair("Profile", clean(session.device.profile, "Balanced"))
            )
            val colors = intArrayOf(
                ReportTheme.platform, ReportTheme.host, ReportTheme.os, ReportTheme.profile
            )
            val columnWidth = (x1 - x0) / 4f
            entries.forEachIndexed { index, entry ->
                val cx = x0 + columnWidth * index + columnWidth / 2f
                label.textAlign = Paint.Align.CENTER
                text(entry.first, cx, y0 + 8f, label)
                drawDeviceIcon(canvas, index, cx, y0 + 52f, colors[index])
                header.textAlign = Paint.Align.CENTER
                text(fit(entry.second, columnWidth - 20f, header), cx, y0 + 124f, header)
            }
            label.textAlign = Paint.Align.LEFT
            header.textAlign = Paint.Align.LEFT
        }
    }

    fun note(session: SessionRecord) {
        card { x0, y0, _, _ ->
            body.color = if (session.note.isBlank()) ReportTheme.muted else ReportTheme.text
            text(
                fit(session.note.ifBlank { "Note not set" }, PAGE_W - 2 * MARGIN - 2 * PAD, body),
                x0, y0 + 12f, body
            )
            body.color = ReportTheme.text
        }
    }

    /** Real launcher icon at 38dp with 8dp corners, scaled to this 1080px canvas. */
    private fun drawLauncherIcon(session: SessionRecord, x: Float, y: Float) {
        val size = 96
        val drawable = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationIcon(session.appPackage)
        }.getOrNull()
        if (drawable == null) {
            drawGamepad(canvas, x, y - 8f, ReportTheme.blue)
            return
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iconCanvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(iconCanvas)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(RectF(x, y, x + size, y + size), 20f, 20f, paint)
    }

    fun sessionHeader(session: SessionRecord) {
        card { x0, y0, x1, _ ->
            val stamp = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
                .format(Date(session.startedAt))
            drawLauncherIcon(session, x0, y0 - 12f)
            // The name of the app with its version, so the report says which
            // build of the game was measured.
            val version = appVersion(context, session.appPackage)
            val name = session.appLabel.ifBlank { session.appPackage }
            val fullName = if (version.isNullOrBlank()) name else "$name($version)"
            text(
                fit(fullName, (x1 - x0) * 0.55f, title),
                x0 + 120f, y0 + 8f, title
            )
            label.textAlign = Paint.Align.RIGHT
            text(stamp, x1, y0 + 8f, label)
            text("Long: ${duration(session.durationMs)}", x1, y0 + 38f, label)
            label.textAlign = Paint.Align.LEFT

            val jitter = session.jitterMs?.let { value ->
                session.avgFrameTimeMs.takeIf { it > 0f }?.let { value / it * 100f }
            }
            val cells: List<Triple<String, String, String>> = listOf(
                Triple("MAX", fmt(session.maxFps, 1), "FPS"),
                Triple("MIN", fmt(session.minFps, 1), "FPS"),
                Triple("AVG", fmt(session.avgFps, 1), "FPS"),
                Triple("JITTER", fmt(jitter, 1) + "%", "Variance"),
                Triple("1% LOW", fmt(session.low1Percent, 1), "FPS"),
                Triple("AVG", fmt(avgSoc(session), 1), "SOC(°C)"),
                Triple("MAX", fmt(maxBattery(session), 1), "Battery(°C)"),
                Triple("AVG", fmt(session.avgPowerW, 2), "Power(W)")
            )
            val cellW = (x1 - x0) / 4f
            cells.forEachIndexed { index, cell ->
                val cx = x0 + cellW * (index % 4) + cellW / 2f
                val cy = y0 + 90f + (index / 4) * 100f
                text(cell.first, cx, cy, kpiLabel)
                kpi.color = ReportTheme.blue
                text(fit(cell.second, cellW - 12f, kpi), cx, cy + 42f, kpi)
                text(cell.third, cx, cy + 66f, kpiLabel)
            }
        }
    }

    /** Upward Y: max at chartTop, min at chartBottom, for every series. */
    private fun drawChart(
        x0: Float, chartTop: Float, x1: Float, chartBottom: Float,
        lines: List<ChartLine>,
        leftLabels: List<String>,
        rightLabels: List<String> = emptyList()
    ) {
        val plotLeft = x0 + 72f
        // Strict right bound: the plot ends a full gutter before the reserved
        // right-axis strip, so no line can run into the 110/88/66 numbers.
        val plotRight = if (rightLabels.isEmpty()) {
            x1 - 12f
        } else {
            x1 - RIGHT_AXIS_W - PLOT_EDGE_GAP
        }
        val chartHeight = chartBottom - chartTop
        val tickLast = (leftLabels.size - 1).coerceAtLeast(1)

        leftLabels.forEachIndexed { index, value ->
            val y = chartTop + chartHeight * index / tickLast
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            text(value, plotLeft - 12f, y + 6f, axisLeft)
            if (rightLabels.isNotEmpty()) {
                rightLabels.getOrNull(index)?.let { right ->
                    text(right, plotRight + PLOT_EDGE_GAP, y + 6f, axisRight)
                }
            }
        }
        repeat(6) { index ->
            val x = plotLeft + (plotRight - plotLeft) * index / 5f
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }

        lines.forEach { line ->
            val values = line.values.clean()
            if (values.size < 2) return@forEach
            val span = (line.max - line.min).takeIf { it > 0f } ?: 1f
            val step = (plotRight - plotLeft) / (values.size - 1)
            fun yOf(value: Float): Float = if (line.temperature) {
                // Exact Card 5 / Card 10 temperature mapping, 0..110 °C.
                chartBottom - ((value.coerceIn(0f, TEMP_MAX_C) / TEMP_MAX_C) * chartHeight)
            } else {
                chartBottom - ((value - line.min) / span).coerceIn(0f, 1f) * chartHeight
            }
            linePaint.color = line.color
            linePaint.strokeWidth = line.width
            if (line.spikes) {
                values.forEachIndexed { index, value ->
                    val x = plotLeft + step * index
                    canvas.drawLine(x, chartBottom, x, yOf(value), linePaint)
                }
            } else {
                var previousX = plotLeft
                var previousY = yOf(values.first())
                for (index in 1 until values.size) {
                    val x = plotLeft + step * index
                    val y = yOf(values[index])
                    canvas.drawLine(previousX, previousY, x, y, linePaint)
                    previousX = x
                    previousY = y
                }
            }
        }
    }

    /** Six evenly spaced X ticks, always drawn even for an empty legacy series. */
    private fun drawTimeTicks(
        x0: Float, baseline: Float, x1: Float,
        rightLabels: List<String> = emptyList(),
        labels: List<String>
    ) {
        val plotLeft = x0 + 72f
        val plotRight = if (rightLabels.isEmpty()) {
            x1 - 12f
        } else {
            x1 - RIGHT_AXIS_W - PLOT_EDGE_GAP
        }
        if (plotRight <= plotLeft) return
        labels.take(6).forEachIndexed { index, value ->
            val x = plotLeft + (plotRight - plotLeft) * index / 5f
            text(value, x, baseline, axisCenter)
        }
    }

    /** One centred legend row, never one row per line. */
    private fun legend(x0: Float, x1: Float, baseline: Float, entries: List<Pair<String, Int>>) {
        var width = 0f
        entries.forEach { width += label.measureText(it.first) + 46f }
        var x = (x0 + x1 - width) / 2f
        label.textAlign = Paint.Align.LEFT
        entries.forEach { entry ->
            canvas.drawRoundRect(
                RectF(x, baseline - 16f, x + 16f, baseline), 4f, 4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = entry.second }
            )
            text(entry.first, x + 24f, baseline, label)
            x += label.measureText(entry.first) + 46f
        }
    }

    private fun centered(value: String, x0: Float, x1: Float, baseline: Float, style: Paint) {
        val old = style.textAlign
        style.textAlign = Paint.Align.CENTER
        text(value, (x0 + x1) / 2f, baseline, style)
        style.textAlign = old
    }

    /** "Chart Options" shares the exact title row, right-aligned like the UI. */
    private fun chartOptions(x1: Float, baseline: Float) {
        val old = label.textAlign
        label.textAlign = Paint.Align.RIGHT
        text("Chart Options", x1, baseline, label)
        label.textAlign = old
    }

    // Card 5 - FPS + temperature, plus real CPU/GPU load. One legend, one TEMP footer.
    fun fpsTemperature(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("FPS & Temperature(°C)", x0, y0, header)
            val fpsCeil = (session.fpsSeries.positive().maxOrNull() ?: 120f)
                .let { if (it > 130f) 144f else 120f }
            val temps = chartTemperatureSeries(session).map { it.coerceIn(0f, TEMP_MAX_C) }
            val chartTop = y0 + 44f
            val chartBottom = y1 - 142f
            // A line without readings is not drawn and is not named in the
            // legend: an empty series looks like a flat zero.
            val series = listOf(
                Pair("FPS", ReportTheme.fps) to
                    ChartLine("FPS", session.fpsSeries.clamped(0f, fpsCeil),
                        ReportTheme.fps, 0f, fpsCeil),
                Pair("TEMP(°C)", ReportTheme.temp) to
                    ChartLine("TEMP(°C)", temps, ReportTheme.temp, 0f, TEMP_MAX_C,
                        temperature = true),
                Pair("CPU(%)", ReportTheme.cpu) to
                    ChartLine("CPU(%)", session.cpuSeries.clamped(0f, 100f),
                        ReportTheme.cpu, 0f, 100f),
                Pair("GPU(%)", ReportTheme.gpu) to
                    ChartLine("GPU(%)", session.gpuPercentSeries.clamped(0f, 100f),
                        ReportTheme.gpu, 0f, 100f)
            ).filter { it.second.values.clean().size >= 2 }
            drawChart(
                x0, chartTop, x1, chartBottom,
                series.map { it.second },
                scale(fpsCeil, 5),
                scale(TEMP_MAX_C, 5)
            )
            drawTimeTicks(x0, chartBottom + 34f, x1, scale(TEMP_MAX_C, 5), timeLabels(session))
            if (series.isNotEmpty()) {
                legend(
                    x0, x1, y1 - 72f,
                    series.map { it.first }
                )
            }
            centered(
                "TEMP MAX: ${fmt(temps.maxOrNull(), 1)}°C   " +
                    "MIN: ${fmt(temps.minOrNull(), 1)}°C   " +
                    "AVG: ${fmt(temps.avgOrNull(), 1)}°C",
                x0, x1, y1 - 24f, body
            )
        }
    }

    // Card 6 - sharp 2.5px sky-blue frame-time spikes.
    fun frameTime(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Frame Time(ms)", x0, y0, header)
            val maxFrame = frameTimeCeiling(session.frameTimeSeries, session.maxFrameTimeMs)
            val chartTop = y0 + 44f
            val chartBottom = y1 - 86f
            drawChart(
                x0, chartTop, x1, chartBottom,
                listOf(
                    ChartLine(
                        "Frame Time", session.frameTimeSeries.clamped(0f, maxFrame),
                        ReportTheme.frame, 0f, maxFrame, spikes = true, width = 2.5f
                    )
                ),
                scale(maxFrame, 5)
            )
            drawTimeTicks(x0, chartBottom + 34f, x1, emptyList(), timeLabels(session))
            centered("MAX: ${fmt(session.maxFrameTimeMs, 0)}ms", x0, x1, y1 - 16f, label)
        }
    }

    // Card 7 - total plus every dynamically detected CPU cluster.
    fun cpuUsage(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Usage(%)", x0, y0, header)
            chartOptions(x1, y0)
            val lines = mutableListOf(
                ChartLine("Total", session.cpuSeries.clamped(0f, 100f),
                    ReportTheme.total, 0f, 100f)
            )
            session.cpuGroupNames.forEachIndexed { index, name ->
                lines.add(
                    ChartLine(
                        name, column(session.cpuGroupSeries, index).clamped(0f, 100f),
                        ReportTheme.cluster[index % ReportTheme.cluster.size], 0f, 100f
                    )
                )
            }
            val chartTop = y0 + 44f
            val chartBottom = y1 - 92f
            drawChart(x0, chartTop, x1, chartBottom, lines, scale(100f, 5))
            drawTimeTicks(x0, chartBottom + 34f, x1, emptyList(), timeLabels(session))
            legend(x0, x1, y1 - 20f, lines.map { Pair(it.label, it.color) })
        }
    }

    // Card 8 - full grid, six ticks, legend and footer even when samples are empty.
    fun cpuFrequency(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Frequency(MHz)", x0, y0, header)
            chartOptions(x1, y0)
            val allFreq = allFrequencies(session)
            val ceiling = maxOf(4000f, (allFreq.maxOrNull() ?: 0f) * 1.05f)
            val lines = session.cpuGroupNames.ifEmpty { listOf("CPU") }.mapIndexed { index, name ->
                ChartLine(
                    name,
                    if (session.cpuGroupNames.isEmpty()) emptyList()
                    else column(session.cpuGroupFreqSeries, index).positive(),
                    ReportTheme.cluster[index % ReportTheme.cluster.size], 0f, ceiling
                )
            }
            val chartTop = y0 + 44f
            val chartBottom = y1 - 142f
            drawChart(x0, chartTop, x1, chartBottom, lines, scale(ceiling, 5))
            drawTimeTicks(x0, chartBottom + 34f, x1, emptyList(), timeLabels(session))
            legend(x0, x1, y1 - 72f, lines.map { Pair(it.label, it.color) })
            centered(
                "MAX: ${fmt(allFreq.maxOrNull(), 0)}MHz   " +
                    "MIN: ${fmt(allFreq.minOrNull(), 0)}MHz   " +
                    "AVG: ${fmt(allFreq.avgOrNull(), 0)}MHz",
                x0, x1, y1 - 24f, body
            )
        }
    }

    // Card 9 - Power(W) left axis 0..8 and Capacity(%) right axis 0..100.
    fun powerCapacity(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Power(W) & Capacity(%)", x0, y0, header)
            val chartTop = y0 + 44f
            val chartBottom = y1 - 142f
            val right = scale(100f, 5)
            drawChart(
                x0, chartTop, x1, chartBottom,
                listOf(
                    ChartLine("Power(W)", session.powerSeries.clamped(0f, 8f),
                        ReportTheme.power, 0f, 8f),
                    ChartLine("Capacity(%)", session.batterySeries.clamped(0f, 100f),
                        ReportTheme.capacity, 0f, 100f)
                ),
                scale(8f, 5, 1),
                right
            )
            drawTimeTicks(x0, chartBottom + 34f, x1, right, timeLabels(session))
            legend(
                x0, x1, y1 - 72f,
                listOf(
                    Pair("Power(W)", ReportTheme.power),
                    Pair("Capacity(%)", ReportTheme.capacity)
                )
            )
            val power = session.powerSeries.positive()
            centered(
                "MAX: ${fmt(power.maxOrNull(), 2)}W   " +
                    "MIN: ${fmt(power.minOrNull(), 2)}W   " +
                    "AVG: ${fmt(power.avgOrNull(), 2)}W",
                x0, x1, y1 - 24f, body
            )
        }
    }

    // Card 10 - dedicated CPU/SoC temperature line, never a battery skin zone.
    fun cpuTemperature(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Temperature(°C)", x0, y0, header)
            val temps = temperatureSeries(session).map { it.coerceIn(0f, TEMP_MAX_C) }
            val chartTop = y0 + 44f
            val chartBottom = y1 - 86f
            drawChart(
                x0, chartTop, x1, chartBottom,
                listOf(
                    ChartLine("CPU(°C)", temps, ReportTheme.chipTemp, 0f, TEMP_MAX_C,
                        temperature = true)
                ),
                scale(TEMP_MAX_C, 5)
            )
            drawTimeTicks(x0, chartBottom + 34f, x1, emptyList(), timeLabels(session))
            centered(
                "MAX: ${fmt(temps.maxOrNull(), 1)}°C   " +
                    "MIN: ${fmt(temps.minOrNull(), 1)}°C   " +
                    "AVG: ${fmt(temps.avgOrNull(), 1)}°C",
                x0, x1, y1 - 16f, body
            )
        }
    }

    // Card 11 - one compact Device Detail card and the full real Shell Logs block.
    fun hardwareDetail(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            val coreRows = minOf(
                maxOf(session.perCoreLoadSeries.size, session.perCoreFreqSeries.size), 8
            )
            val threadRows = minOf(session.threadNames.size, 6)
            // The first line says how the run was measured. A line of "--" is
            // not a reading and is never printed.
            val head = ShellLogs.head(session.shellLogs)
            val logs = ShellLogs.body(session.shellLogs).take(12)
            var y = y0 + 40f

            text("Device Detail", x0, y0, header)
            val freqs = allFrequencies(session)
            val gpuLoad = session.gpuPercentSeries.positive()
            val gpuClock = session.gpuFreqSeries.positive()
            // A row without a reading is not printed. A legacy run without
            // counters gets one clean line instead of a page of "--".
            val detailRows = listOf(
                Pair("CPU CLOCK AVG", fmt(freqs.avgOrNull(), 0)),
                Pair("CPU CLOCK MAX", fmt(freqs.maxOrNull(), 0)),
                Pair("GPU LOAD AVG", fmt(gpuLoad.avgOrNull(), 1)),
                Pair("GPU LOAD MAX", fmt(gpuLoad.maxOrNull(), 1)),
                Pair("GPU CLOCK AVG", fmt(gpuClock.avgOrNull(), 0)),
                Pair("GPU CLOCK MAX", fmt(gpuClock.maxOrNull(), 0))
            ).filter { it.second != "--" }
            if (detailRows.isEmpty()) {
                text("Hardware counters unavailable in this legacy session", x0, y, label)
                y += 30f
            } else {
                detailRows.forEach { row ->
                    text(row.first, x0, y, label)
                    body.textAlign = Paint.Align.RIGHT
                    text(row.second, x1, y, body)
                    body.textAlign = Paint.Align.LEFT
                    y += 30f
                }
            }
            y += 16f

            text("Per-core (0-7)", x0, y, header)
            y += 34f
            if (coreRows == 0) {
                text("Per-core: --", x0, y, label)
                y += 30f
            } else {
                repeat(coreRows) { core ->
                    val load = column(session.perCoreLoadSeries, core).avgOrNull()
                    val clock = intColumn(session.perCoreFreqSeries, core).positive().avgOrNull()
                    text("CPU $core", x0, y, label)
                    body.textAlign = Paint.Align.RIGHT
                    text("${fmt(load, 0)}%   ${fmt(clock, 0)} MHz", x1, y, body)
                    body.textAlign = Paint.Align.LEFT
                    y += 30f
                }
            }
            y += 16f

            text("Top threads", x0, y, header)
            y += 34f
            if (threadRows == 0) {
                text("--", x0, y, label)
                y += 30f
            } else {
                repeat(threadRows) { index ->
                    val name = session.threadNames[index]
                    text(fit(name, (x1 - x0) * 0.7f, label), x0, y, label)
                    body.textAlign = Paint.Align.RIGHT
                    val load = session.threadAvgLoads.getOrNull(index)
                    text(if (load == null) "--" else fmt(load, 1) + "%", x1, y, body)
                    body.textAlign = Paint.Align.LEFT
                    y += 30f
                }
            }
            y += 20f

            text("Shell Logs", x0, y, header)
            y += 34f
            if (head != null) {
                body.color = ReportTheme.blue
                text(fit(head, x1 - x0, body), x0, y, body)
                body.color = ReportTheme.text
                y += 28f
            } else {
                text("No shell output was captured for this run.", x0, y, label)
                y += 28f
            }
            // The identity line is already printed in blue directly above.
            logs.forEach { line ->
                if (y > y1) return@forEach
                text(fit(line, x1 - x0, mono), x0, y, mono)
                y += 24f
            }
        }
    }
}

private fun renderReport(context: Context, session: SessionRecord, canvas: Canvas) {
    val report = SceneReportRenderer(context, canvas, session)
    report.start()
    report.thermalStatus(peakSoc(session), temperatureSeries(session).count { it >= 90f })
    report.deviceRow(session)
    if (session.note.isNotBlank()) report.note(session)
    report.sessionHeader(session)
    report.fpsTemperature(session)
    report.frameTime(session)
    val charts = session.hardwareCharts()
    if (charts.cpuUsage) report.cpuUsage(session)
    if (charts.cpuFrequency) report.cpuFrequency(session)
    report.powerCapacity(session)
    if (charts.cpuTemperature) report.cpuTemperature(session)
    report.hardwareDetail(session)
}

private fun safeName(session: SessionRecord): String =
    session.appLabel.replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_').take(24).ifBlank { "session" }

private fun buildPdf(
    context: Context,
    session: SessionRecord,
    output: OutputStream
) {
    val document = PdfDocument()
    try {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, reportHeight(session), 1).create()
        val page = document.startPage(info)
        renderReport(context, session, page.canvas)
        document.finishPage(page)
        document.writeTo(output)
    } finally {
        document.close()
    }
}

private fun buildImage(context: Context, session: SessionRecord): Bitmap {
    val bitmap = Bitmap.createBitmap(PAGE_W, reportHeight(session), Bitmap.Config.ARGB_8888)
    renderReport(context, session, Canvas(bitmap))
    return bitmap
}

/** Full session report. No CSV, no external dependency. */
object SessionShare {
    fun shareAsPdf(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, "Qstats_${safeName(session)}_${session.id}.pdf")
            FileOutputStream(file).use { buildPdf(context, session, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Qstats - ${safeName(session)} session")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share session PDF"))
        }
    }

    /** Writes the single-page PDF to Downloads/FrameScope and offers to open it. */
    fun exportPdfToDownloads(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val name = "Qstats_${safeName(session)}_${session.id.take(8)}.pdf"
            var uri: Uri? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/FrameScope"
                    )
                }
                uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let { target ->
                    context.contentResolver.openOutputStream(target)?.use {
                        buildPdf(context, session, it)
                    }
                }
            }
            if (uri == null) {
                val dir = File(context.filesDir, "exports").apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { buildPdf(context, session, it) }
                uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(open, "Open session PDF"))
        }
    }

    fun shareAsImage(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val bitmap = buildImage(context, session)
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, "Qstats_${safeName(session)}_${session.id}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Qstats - ${safeName(session)} session")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share session image"))
        }
    }
}

@Composable
fun SessionShareDialog(
    session: SessionRecord,
    threshold: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share session") },
        text = {
            Text(
                "Share the full Qstats breakdown with real charts for this match. " +
                    "Every value comes from the privileged hardware read of this session."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                SessionShare.shareAsPdf(context, session, threshold)
                onDismiss()
            }) { Text("PDF") }
        },
        dismissButton = {
            TextButton(onClick = {
                SessionShare.shareAsImage(context, session, threshold)
                onDismiss()
            }) { Text("Image") }
        }
    )
}
