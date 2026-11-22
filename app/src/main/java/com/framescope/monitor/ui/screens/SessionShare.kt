package com.framescope.monitor.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    val total = Color.parseColor("#00E5FF")
    val frame = Color.parseColor("#7CC4FA")
    val warnBack = Color.parseColor("#2A211B")
    val warn = Color.parseColor("#FF8C42")
    val platform = Color.parseColor("#7B61FF")
    val host = Color.parseColor("#4A90E2")
    val os = Color.parseColor("#3DDC84")
    val profile = Color.parseColor("#FF6B35")
    val power = Color.parseColor("#2962FF")
    val capacity = Color.parseColor("#90CAF9")
    val cpuTemp = Color.parseColor("#81D4FA")
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
    listOfNotNull(session.maxTempC, session.tempSeries.maxOrNull())
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
            540f,  // 5 FPS & temperature
            480f,  // 6 frame time
            540f,  // 7 CPU usage
            540f,  // 8 CPU frequency
            560f,  // 9 power & capacity
            560f,  // 10 CPU temperature
            920f   // 11 device detail + shell logs
        )
    )
    return heights
}

/** A single continuous page: no split cards, no empty pages, no watermark. */
private fun reportHeight(session: SessionRecord): Int {
    val heights = cardHeights(session)
    return (heights.sum() + GAP * (heights.size - 1) + MARGIN * 2f).toInt() + 8
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
    private val axis = paint(17f, Typeface.NORMAL, ReportTheme.dim)
    private val axisLeft = paint(17f, Typeface.NORMAL, ReportTheme.dim).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val axisRight = paint(17f, Typeface.NORMAL, ReportTheme.dim)
    private val axisCenter = paint(18f, Typeface.NORMAL, ReportTheme.dim).apply {
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
                Pair("Host", clean(session.device.model)),
                Pair("OS", clean(session.device.osVersion?.substringBefore(" ("))),
                Pair("Profile", clean(session.device.profile, "Balanced"))
            )
            val colors = intArrayOf(
                ReportTheme.platform, ReportTheme.host, ReportTheme.os, ReportTheme.profile
            )
            val columnWidth = (x1 - x0) / 4f
            entries.forEachIndexed { index, entry ->
                val cx = x0 + columnWidth * index + columnWidth / 2f
                axisCenter.textAlign = Paint.Align.CENTER
                text(entry.first, cx, y0 + 8f, label.also { it.textAlign = Paint.Align.CENTER })
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[index] }
                canvas.drawRoundRect(RectF(cx - 30f, y0 + 24f, cx + 30f, y0 + 84f), 14f, 14f, box)
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
            text(
                fit(session.appLabel.ifBlank { session.appPackage }, (x1 - x0) * 0.6f, title),
                x0, y0 + 8f, title
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
                Triple("JITTER", fmt(jitter, 1) + "%", "FPS"),
                Triple("1% LOW", fmt(session.low1Percent, 1), "FPS"),
                Triple("AVG", fmt(session.tempSeries.hotspot().avgOrNull(), 1), "SOC(°C)"),
                Triple("MAX", fmt(session.tempSeries.hotspot().maxOrNull(), 1), "Battery(°C)"),
                Triple("AVG", fmt(session.powerSeries.avgOrNull(), 2), "Power(W)")
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

    private fun timeLabels(session: SessionRecord): List<String> = listOf(
        "0", duration(session.durationMs / 4), duration(session.durationMs / 2),
        duration(session.durationMs * 3 / 4), duration(session.durationMs)
    )

    /** Correct orientation: data and labels both use max at top, min at bottom. */
    private fun drawChart(
        x0: Float, top: Float, x1: Float, bottom: Float,
        lines: List<ChartLine>,
        leftLabels: List<String>,
        rightLabels: List<String> = emptyList(),
        timeLabels: List<String> = emptyList()
    ) {
        val plotLeft = x0 + 64f
        val plotRight = if (rightLabels.isEmpty()) x1 - 12f else x1 - 64f
        val plotBottom = bottom - 36f
        val tickLast = (leftLabels.size - 1).coerceAtLeast(1)

        leftLabels.forEachIndexed { index, value ->
            val y = top + (plotBottom - top) * index / tickLast
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            text(value, plotLeft - 12f, y + 6f, axisLeft)
            if (rightLabels.isNotEmpty()) {
                rightLabels.getOrNull(index)?.let { right ->
                    axisRight.textAlign = Paint.Align.LEFT
                    text(right, plotRight + 12f, y + 6f, axisRight)
                    axisRight.textAlign = Paint.Align.RIGHT
                }
            }
        }
        axisLeft.textAlign = Paint.Align.RIGHT
        axisRight.textAlign = Paint.Align.RIGHT
        repeat(5) { index ->
            val x = plotLeft + (plotRight - plotLeft) * index / 4f
            canvas.drawLine(x, top, x, plotBottom, gridPaint)
            timeLabels.getOrNull(index)?.let { text(it, x, plotBottom + 26f, axisCenter) }
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
            drawChart(
                x0, y0 + 32f, x1, y1 - 74f,
                listOf(
                    ChartLine("FPS", session.fpsSeries, ReportTheme.fps, 0f, fpsCeil),
                    ChartLine("TEMP", session.tempSeries.hotspot(), ReportTheme.temp, 0f, 110f)
                ),
                scale(fpsCeil, 5),
                scale(110f, 5),
                timeLabels(session)
            )
            val entries: List<Pair<String, Int>> = listOf(
                Pair("FPS", ReportTheme.fps), Pair("TEMP(°C)", ReportTheme.temp)
            )
            legend(x0, x1, y1 - 30f, entries)
        }
    }

    fun frameTime(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Frame Time(ms)", x0, y0, header)
            val maxFrame = (session.frameTimeSeries.positive().maxOrNull() ?: 100f)
                .coerceAtLeast(100f)
            drawChart(
                x0, y0 + 32f, x1, y1 - 62f,
                listOf(
                    ChartLine(
                        "Frame Time", session.frameTimeSeries, ReportTheme.frame,
                        0f, maxFrame, spikes = true, width = 1.4f
                    )
                ),
                scale(maxFrame, 5), timeLabels = timeLabels(session)
            )
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
            drawChart(
                x0, y0 + 32f, x1, y1 - 70f, lines,
                scale(100f, 5), timeLabels = timeLabels(session)
            )
            legend(x0, x1, y1 - 16f, lines.map { Pair(it.label, it.color) })
        }
    }

    fun cpuFrequency(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Frequency(MHz)", x0, y0, header)
            val peak = session.cpuGroupFreqSeries.flatMap { row ->
                row.mapNotNull { it?.finiteOrNull() }
            }.maxOrNull() ?: 0f
            val ceiling = maxOf(4000f, peak * 1.05f)
            val lines = session.cpuGroupNames.mapIndexed { index, name ->
                ChartLine(
                    name, column(session.cpuGroupFreqSeries, index).positive(),
                    ReportTheme.cluster[index % ReportTheme.cluster.size], 0f, ceiling
                )
            }
            drawChart(
                x0, y0 + 32f, x1, y1 - 70f, lines,
                scale(ceiling, 5), timeLabels = timeLabels(session)
            )
            legend(x0, x1, y1 - 16f, lines.map { Pair(it.label, it.color) })
        }
    }

    fun powerAndCapacity(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Power(W) & Capacity(%)", x0, y0, header)
            val power = session.powerSeries.positive()
            drawChart(
                x0, y0 + 32f, x1, y1 - 110f,
                listOf(
                    ChartLine("Power(W)", power, ReportTheme.power, 0f, 8f),
                    ChartLine(
                        "Capacity(%)", session.batterySeries.positive(),
                        ReportTheme.capacity, 0f, 100f
                    )
                ),
                scale(8f, 8), scale(100f, 5), timeLabels(session)
            )
            legend(
                x0, x1, y1 - 66f,
                listOf(Pair("Power(W)", ReportTheme.power), Pair("Capacity(%)", ReportTheme.capacity))
            )
            centered(
                "MAX: ${fmt(power.maxOrNull(), 2)}W   " +
                    "MIN: ${fmt(power.minOrNull(), 2)}W   " +
                    "AVG: ${fmt(power.avgOrNull(), 2)}W",
                x0, x1, y1 - 16f, body
            )
        }
    }

    fun cpuTemperature(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("CPU Temperature(°C)", x0, y0, header)
            val temps = session.cpuTempSeries.ifEmpty { session.socTempSeries }.hotspot()
            drawChart(
                x0, y0 + 32f, x1, y1 - 62f,
                listOf(
                    ChartLine(
                        "CPU Temperature", temps, ReportTheme.cpuTemp,
                        0f, 110f, width = 1.4f
                    )
                ),
                scale(110f, 11), timeLabels = timeLabels(session)
            )
            centered(
                "MAX: ${fmt(temps.maxOrNull(), 1)}°C   " +
                    "MIN: ${fmt(temps.minOrNull(), 1)}°C   " +
                    "AVG: ${fmt(temps.avgOrNull(), 1)}°C",
                x0, x1, y1 - 12f, body
            )
        }
    }

    fun deviceDetail(session: SessionRecord) {
        card { x0, y0, x1, y1 ->
            text("Device Detail", x0, y0, header)
            val cpuClock = session.cpuFreqSeries.positive()
            val gpuLoad = session.gpuPercentSeries.positive()
            val gpuClock = session.gpuFreqSeries.positive()
            val rows = listOf(
                Pair("CPU CLOCK AVG", fmt(cpuClock.avgOrNull(), 0)),
                Pair("CPU CLOCK MAX", fmt(cpuClock.maxOrNull(), 0)),
                Pair("GPU LOAD AVG", fmt(gpuLoad.avgOrNull(), 1)),
                Pair("GPU LOAD MAX", fmt(gpuLoad.maxOrNull(), 1)),
                Pair("GPU CLOCK AVG", fmt(gpuClock.avgOrNull(), 0)),
                Pair("GPU CLOCK MAX", fmt(gpuClock.maxOrNull(), 0))
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
            val cores = maxOf(session.perCoreLoadSeries.size, session.perCoreFreqSeries.size)
            text(if (cores == 0) "Per-core (0-7): --" else "Per-core (0-${cores - 1})", x0, y, label)
            y += 28f
            repeat(minOf(cores, 8)) { core ->
                val load = column(session.perCoreLoadSeries, core).avgOrNull()
                val clock = column(
                    session.perCoreFreqSeries.map { row -> row.map { it?.toFloat() } }, core
                ).avgOrNull()
                text("CPU $core", x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text("${fmt(load, 0)}%   ${fmt(clock, 0)} MHz", x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 26f
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
            y += 12f
            text("Shell Logs", x0, y, label)
            y += 28f
            session.shellLogs.ifBlank { "--" }.lineSequence().take(14).forEach { line ->
                if (y > y1) return@forEach
                text(fit(line, x1 - x0, mono), x0, y, mono)
                y += 20f
            }
        }
    }
}

private fun renderReport(session: SessionRecord, canvas: Canvas, threshold: Int) {
    val report = SceneReportRenderer(canvas, session)
    report.start()
    report.thermalBanner(
        peakSoc(session),
        session.tempSeries.count { (it.plausibleTempC(MAX_HOTSPOT_TEMP_C) ?: 0f) >= 90f }
    )
    report.deviceRow(session)
    report.note(session)
    report.sessionHeader(session)
    report.fpsTemperature(session)
    report.frameTime(session)
    report.cpuUsage(session)
    report.cpuFrequency(session)
    report.powerAndCapacity(session)
    report.cpuTemperature(session)
    report.deviceDetail(session)
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
