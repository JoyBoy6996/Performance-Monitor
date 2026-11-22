package com.framescope.monitor.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
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
private fun List<Float>.hotspot(): List<Float> = mapNotNull { it.plausibleTempC(110f) }
private fun List<Float>.avgOrNull(): Float? = clean().takeIf { it.isNotEmpty() }?.average()?.toFloat()

private fun column(rows: List<List<Float?>>, index: Int): List<Float> =
    rows.mapNotNull { it.getOrNull(index)?.finiteOrNull() }

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

/** The named CPU/SoC sensor, with honest legacy fallbacks. */
private fun temperatureSeries(session: SessionRecord): List<Float> = session.cpuTempSeries
    .ifEmpty { session.socTempSeries }
    .ifEmpty { session.tempSeries }
    .hotspot()

/** Ticks are always given top-to-bottom: largest value first. */
private fun scale(max: Float, steps: Int, decimals: Int = 0): List<String> =
    (steps downTo 0).map { fmt(max * it / steps, decimals) }

private const val PAGE_W = 1080
private const val MARGIN = 48f
private const val GAP = 24f
private const val PAD = 28f
private const val RADIUS = 24f

private object ReportTheme {
    val background = Color.parseColor("#0D0D0F")
    val card = Color.parseColor("#18191C")
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
    val width: Float = 3f
)

private fun peakSoc(session: SessionRecord): Float? =
    listOfNotNull(session.maxTempC, temperatureSeries(session).maxOrNull())
        .mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }.maxOrNull()

/** One height per card, in the exact order the report draws them. */
private fun cardHeights(session: SessionRecord): List<Float> {
    val heights = mutableListOf<Float>()
    if ((peakSoc(session) ?: 0f) >= 85f) heights.add(96f)
    heights.addAll(
        listOf(
            190f,  // 2 device info
            96f,   // 3 note
            350f,  // 4 game header + 2x4 KPI grid
            580f,  // 5 FPS & temperature
            520f,  // 6 frame time
            560f,  // 7 CPU usage
            560f,  // 8 CPU frequency
            520f,  // 9 CPU detail
            520f,  // 10 GPU & threads
            920f   // 11 shell logs + start identity
        )
    )
    return heights
}

/** A single continuous page: no split cards, no empty pages, no watermark. */
private fun reportHeight(session: SessionRecord): Int {
    val heights = cardHeights(session)
    return (heights.sum() + GAP * (heights.size - 1) + MARGIN * 2f).toInt() + 8
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
        0 -> { // microchip
            canvas.drawRoundRect(RectF(cx - 12f, cy - 12f, cx + 12f, cy + 12f), 4f, 4f, stroke)
            canvas.drawRoundRect(RectF(cx - 6f, cy - 6f, cx + 6f, cy + 6f), 2f, 2f, fill)
            (-8..8 step 8).forEach { offset ->
                canvas.drawLine(cx + offset, cy - 18f, cx + offset, cy - 12f, stroke)
                canvas.drawLine(cx + offset, cy + 12f, cx + offset, cy + 18f, stroke)
                canvas.drawLine(cx - 18f, cy + offset, cx - 12f, cy + offset, stroke)
                canvas.drawLine(cx + 12f, cy + offset, cx + 18f, cy + offset, stroke)
            }
        }
        1 -> { // phone
            canvas.drawRoundRect(RectF(cx - 10f, cy - 16f, cx + 10f, cy + 16f), 4f, 4f, stroke)
            canvas.drawLine(cx - 4f, cy + 11f, cx + 4f, cy + 11f, stroke)
        }
        2 -> { // android
            canvas.drawArc(RectF(cx - 10f, cy - 14f, cx + 10f, cy + 6f), 180f, 180f, false, stroke)
            canvas.drawRoundRect(RectF(cx - 9f, cy - 2f, cx + 9f, cy + 14f), 4f, 4f, stroke)
            canvas.drawCircle(cx - 4f, cy - 6f, 1.5f, fill)
            canvas.drawCircle(cx + 4f, cy - 6f, 1.5f, fill)
        }
        else -> { // speedometer
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
 * Chart Y labels are placed with the same formula as the data:
 * top = maximum, bottom = minimum. Nothing is paginated or sliced.
 */
private class SceneReportRenderer(
    private val canvas: Canvas,
    session: SessionRecord
) {
    private val heights = cardHeights(session)
    private var cardIndex = 0
    private var cursor = MARGIN

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
        canvas.drawRoundRect(
            RectF(MARGIN, cursor, PAGE_W - MARGIN, cursor + height),
            RADIUS, RADIUS, back
        )
        draw(MARGIN + PAD, cursor + PAD, PAGE_W - MARGIN - PAD, cursor + height - PAD)
        cursor += height + GAP
    }

    fun start() {
        canvas.drawColor(ReportTheme.background)
    }

    fun thermalBanner(peak: Float?, secondsAbove90: Int) {
        if (peak == null || peak < 85f) return
        card(warnPaint) { x0, y0, x1, y1 ->
            body.color = ReportTheme.warn
            val baseline = (y0 + y1) / 2f + 8f
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

    fun sessionHeader(session: SessionRecord) {
        card { x0, y0, x1, _ ->
            val stamp = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
                .format(Date(session.startedAt))
            drawGamepad(canvas, x0, y0 - 4f, ReportTheme.blue)
            text(
                fit(session.appLabel.ifBlank { session.appPackage }, (x1 - x0) * 0.55f, title),
                x0 + 64f, y0 + 8f, title
            )
            label.textAlign = Paint.Align.RIGHT
            text(stamp, x1, y0 + 8f, label)
            text("Long: ${duration(session.durationMs)}", x1, y0 + 38f, label)
            label.textAlign = Paint.Align.LEFT

            val jitter = session.jitterMs?.let { value ->
                session.avgFrameTimeMs.takeIf { it > 0f }?.let { value / it * 100f }
            }
            val temps = temperatureSeries(session)
            val cells: List<Triple<String, String, String>> = listOf(
                Triple("MAX", fmt(session.maxFps, 1), "FPS"),
                Triple("MIN", fmt(session.minFps, 1), "FPS"),
                Triple("AVG", fmt(session.avgFps, 1), "FPS"),
                Triple("JITTER", fmt(jitter, 1) + "%", "FPS"),
                Triple("1% LOW", fmt(session.low1Percent, 1), "FPS"),
                Triple("AVG", fmt(temps.avgOrNull(), 1), "SOC(°C)"),
                Triple("MAX", fmt(temps.maxOrNull(), 1), "SOC(°C)"),
                Triple("AVG", fmt(session.cpuSeries.avgOrNull(), 0), "CPU(%)")
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

    /** Correct orientation: data and labels both use max at top, min at bottom. */
    private fun drawChart(
        x0: Float, top: Float, x1: Float, bottom: Float,
        lines: List<ChartLine>,
        leftLabels: List<String>,
        rightLabels: List<String> = emptyList()
    ) {
        val plotLeft = x0 + 64f
        val plotRight = if (rightLabels.isEmpty()) x1 - 12f else x1 - 64f
        val plotBottom = bottom - 40f
        val tickLast = (leftLabels.size - 1).coerceAtLeast(1)

        leftLabels.forEachIndexed { index, value ->
            val y = top + (plotBottom - top) * index / tickLast
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            text(value, plotLeft - 12f, y + 6f, axisLeft)
            if (rightLabels.isNotEmpty()) {
                rightLabels.getOrNull(index)?.let { right ->
                    text(right, plotRight + 12f, y + 6f, axisRight)
                }
            }
        }
        repeat(6) { index ->
            val x = plotLeft + (plotRight - plotLeft) * index / 5f
            canvas.drawLine(x, top, x, plotBottom, gridPaint)
            // Six evenly spaced ticks are drawn by the caller at this exact x.
        }

        lines.forEach { line ->
            val values = line.values.clean()
            if (values.size < 2) return@forEach
            val span = (line.max - line.min).takeIf { it > 0f } ?: 1f
            val step = (plotRight - plotLeft) / (values.size - 1)
            fun yOf(value: Float): Float =
                plotBottom - ((value - line.min) / span).coerceIn(0f, 1f) * (plotBottom - top)
            linePaint.color = line.color
            linePaint.strokeWidth = line.width
            if (line.spikes) {
                values.forEachIndexed { index, value ->
                    val x = plotLeft + step * index
                    canvas.drawLine(x, plotBottom, x, yOf(value), linePaint)
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

    private fun drawTimeTicks(
        x0: Float, top: Float, x1: Float, bottom: Float,
        rightLabels: List<String> = emptyList(),
        labels: List<String>
    ) {
        val plotLeft = x0 + 64f
        val plotRight = if (rightLabels.isEmpty()) x1 - 12f else x1 - 64f
        val baseline = bottom - 10f
        labels.take(6).forEachIndexed { index, value ->
            val x = plotLeft + (plotRight - plotLeft) * index / 5f
            text(value, x, baseline, axisCenter)
        }
    }

    private fun legend(x0: Float, x1: Float, baseline: Float, entries: List<Pair<String, Int>>) {
        var width = 0f
        entries.forEach { width += label.measureText(it.first) + 46f }
        var x = (x0 + x1 - width) / 2f
        label.textAlign = Paint.Align.LEFT
        entries.forEach { entry ->
            canvas.drawRect(
                RectF(x, baseline - 16f, x + 16f, baseline),
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

    fun fpsTemperature(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("FPS & Temperature(°C)", x0, y0, header)
            val fpsCeil = (session.fpsSeries.positive().maxOrNull() ?: 120f)
                .let { if (it > 130f) 144f else 120f }
            val temps = temperatureSeries(session).map { it.coerceIn(0f, 110f) }
            val chartTop = y0 + 32f
            val chartBottom = y1 - 108f
            drawChart(
                x0, chartTop, x1, chartBottom,
                listOf(
                    ChartLine("FPS", session.fpsSeries, ReportTheme.fps, 0f, fpsCeil),
                    ChartLine("TEMP(°C)", temps, ReportTheme.temp, 0f, 110f, width = 3f)
                ),
                scale(fpsCeil, 5),
                scale(110f, 5)
            )
            drawTimeTicks(x0, chartTop, x1, chartBottom, scale(110f, 5), timeLabels(session))
            legend(
                x0, x1, y1 - 66f,
                listOf(
                    Pair("FPS", ReportTheme.fps),
                    Pair("TEMP(°C)", ReportTheme.temp),
                    Pair("CPU(%)", ReportTheme.cpu),
                    Pair("GPU(%)", ReportTheme.gpu)
                )
            )
            centered(
                "TEMP MAX: ${fmt(temps.maxOrNull(), 1)}°C   " +
                    "MIN: ${fmt(temps.minOrNull(), 1)}°C   " +
                    "AVG: ${fmt(temps.avgOrNull(), 1)}°C",
                x0, x1, y1 - 16f, body
            )
        }
    }

    fun frameTime(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Frame Time(ms)", x0, y0, header)
            val maxFrame = (session.frameTimeSeries.positive().maxOrNull() ?: 100f)
                .coerceAtLeast(100f)
            val chartTop = y0 + 32f
            val chartBottom = y1 - 62f
            drawChart(
                x0, chartTop, x1, chartBottom,
                listOf(
                    ChartLine(
                        "Frame Time", session.frameTimeSeries, ReportTheme.frame,
                        0f, maxFrame, spikes = true, width = 2.5f
                    )
                ),
                scale(maxFrame, 5)
            )
            drawTimeTicks(x0, chartTop, x1, chartBottom, emptyList(), timeLabels(session))
            centered("MAX: ${fmt(session.maxFrameTimeMs, 0)}ms", x0, x1, y1 - 12f, label)
        }
    }

    fun cpuUsage(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Usage(%)", x0, y0, header)
            val lines = mutableListOf(
                ChartLine("Total", session.cpuSeries, ReportTheme.total, 0f, 100f)
            )
            session.cpuGroupNames.forEachIndexed { index, name ->
                lines.add(
                    ChartLine(
                        name, column(session.cpuGroupSeries, index),
                        ReportTheme.cluster[index % ReportTheme.cluster.size], 0f, 100f
                    )
                )
            }
            val chartTop = y0 + 32f
            val chartBottom = y1 - 70f
            drawChart(x0, chartTop, x1, chartBottom, lines, scale(100f, 5))
            drawTimeTicks(x0, chartTop, x1, chartBottom, emptyList(), timeLabels(session))
            legend(x0, x1, y1 - 16f, lines.map { Pair(it.label, it.color) })
        }
    }

    fun cpuFrequency(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Frequency(MHz)", x0, y0, header)
            val allFreq = session.cpuGroupFreqSeries.flatMap { row ->
                row.mapNotNull { it?.finiteOrNull() }
            }.positive()
            val ceiling = maxOf(4000f, (allFreq.maxOrNull() ?: 0f) * 1.05f)
            val lines = session.cpuGroupNames.ifEmpty { listOf("CPU") }.mapIndexed { index, name ->
                ChartLine(
                    name,
                    if (session.cpuGroupNames.isEmpty()) emptyList()
                    else column(session.cpuGroupFreqSeries, index).positive(),
                    ReportTheme.cluster[index % ReportTheme.cluster.size], 0f, ceiling
                )
            }
            val chartTop = y0 + 32f
            val chartBottom = y1 - 108f
            drawChart(x0, chartTop, x1, chartBottom, lines, scale(ceiling, 5))
            drawTimeTicks(x0, chartTop, x1, chartBottom, emptyList(), timeLabels(session))
            legend(x0, x1, y1 - 66f, lines.map { Pair(it.label, it.color) })
            centered(
                "MAX: ${fmt(allFreq.maxOrNull(), 0)}MHz   " +
                    "MIN: ${fmt(allFreq.minOrNull(), 0)}MHz   " +
                    "AVG: ${fmt(allFreq.avgOrNull(), 0)}MHz",
                x0, x1, y1 - 16f, body
            )
        }
    }

    fun cpuDetail(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Detail", x0, y0, header)
            val cores = maxOf(session.perCoreLoadSeries.size, session.perCoreFreqSeries.size)
            var y = y0 + 40f
            if (cores == 0) {
                text("Per-core: --", x0, y, label)
                return@card
            }
            repeat(minOf(cores, 8)) { core ->
                val load = column(session.perCoreLoadSeries, core).avgOrNull()
                val clock = column(
                    session.perCoreFreqSeries.map { row -> row.map { it?.toFloat() } }, core
                ).positive().avgOrNull()
                text("CPU $core", x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text("${fmt(load, 0)}%   ${fmt(clock, 0)} MHz", x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 30f
            }
        }
    }

    fun gpuThreads(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("GPU & Threads", x0, y0, header)
            val gpuLoad = session.gpuPercentSeries.positive()
            val gpuClock = session.gpuFreqSeries.positive()
            val rows = listOf(
                Pair("GPU LOAD AVG", fmt(gpuLoad.avgOrNull(), 1)),
                Pair("GPU LOAD MAX", fmt(gpuLoad.maxOrNull(), 1)),
                Pair("GPU CLOCK AVG", fmt(gpuClock.avgOrNull(), 0)),
                Pair("GPU CLOCK MAX", fmt(gpuClock.maxOrNull(), 0)),
                Pair(
                    "DDR CLOCK",
                    if (session.ddrFreqSeries.isEmpty()) "--"
                    else fmt(session.ddrFreqSeries.average().toFloat(), 0)
                )
            )
            var y = y0 + 40f
            rows.forEach { row ->
                text(row.first, x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text(row.second, x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 30f
            }
            y += 12f
            text("Top threads", x0, y, label)
            y += 28f
            session.threadNames.take(6).forEachIndexed { index, name ->
                text(fit(name, (x1 - x0) * 0.7f, label), x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text(fmt(session.threadAvgLoads.getOrElse(index) { 0f }, 1) + "%", x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 26f
            }
        }
    }

    fun shellLogs(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            val identity = session.shellLogs.lineSequence()
                .firstOrNull { it.startsWith("shell_uid=") }
                ?: "shell_uid=-- | platform=-- | thermal_zone=-- | clusters=--"
            text("Shell Logs", x0, y0, header)
            body.color = ReportTheme.blue
            text(fit(identity, x1 - x0, body), x0, y0 + 40f, body)
            body.color = ReportTheme.text
            var y = y0 + 72f
            session.shellLogs.ifBlank { "--" }.lineSequence().take(24).forEach { line ->
                if (y > y1) return@forEach
                text(fit(line, x1 - x0, mono), x0, y, mono)
                y += 22f
            }
        }
    }
}

private fun renderReport(session: SessionRecord, canvas: Canvas, threshold: Int) {
    val report = SceneReportRenderer(canvas, session)
    report.start()
    report.thermalBanner(
        peakSoc(session),
        temperatureSeries(session).count { it >= 90f }
    )
    report.deviceRow(session)
    report.note(session)
    report.sessionHeader(session)
    report.fpsTemperature(session)
    report.frameTime(session)
    report.cpuUsage(session)
    report.cpuFrequency(session)
    report.cpuDetail(session)
    report.gpuThreads(session)
    report.shellLogs(session)
}

private fun safeName(session: SessionRecord): String =
    session.appLabel.replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_').take(24).ifBlank { "session" }

private fun buildPdf(session: SessionRecord, threshold: Int, output: OutputStream) {
    val document = PdfDocument()
    try {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, reportHeight(session), 1).create()
        val page = document.startPage(info)
        renderReport(session, page.canvas, threshold)
        document.finishPage(page)
        document.writeTo(output)
    } finally {
        document.close()
    }
}

private fun buildImage(session: SessionRecord, threshold: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(PAGE_W, reportHeight(session), Bitmap.Config.ARGB_8888)
    renderReport(session, Canvas(bitmap), threshold)
    return bitmap
}

/** Full session report. No CSV, no external dependency. */
object SessionShare {
    fun shareAsPdf(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, "Qstats_${safeName(session)}_${session.id}.pdf")
            FileOutputStream(file).use { buildPdf(session, threshold, it) }
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
                        buildPdf(session, threshold, it)
                    }
                }
            }
            if (uri == null) {
                val dir = File(context.filesDir, "exports").apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { buildPdf(session, threshold, it) }
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
            val bitmap = buildImage(session, threshold)
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
