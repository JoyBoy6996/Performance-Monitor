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
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
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

/** Every number in a shared report goes through here: bad values become "--". */
private fun fmt(value: Float?, decimals: Int): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

private fun List<Float>.avgOrNull(): Float? =
    if (isEmpty()) null else mapNotNull { it.finiteOrNull() }.takeIf { it.isNotEmpty() }
        ?.average()?.toFloat()

private fun List<Float>.positive(): List<Float> =
    mapNotNull { it.finiteOrNull()?.takeIf { v -> v > 0f } }

private fun List<Float>.hotspot(): List<Float> =
    mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }

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

/** First and last real reading only - no graph for power, chip temperature or battery. */
private fun beforeAfter(values: List<Float>, decimals: Int, unit: String): String {
    val clean = values.mapNotNull { it.finiteOrNull() }
    if (clean.isEmpty()) return "--"
    val first = fmt(clean.first(), decimals) + unit
    val last = fmt(clean.last(), decimals) + unit
    return if (clean.size == 1) first else "$first  →  $last"
}

/** A4 in PostScript points; every drawing coordinate is 1080 logical px wide. */
private const val PAGE_W = 595f
private const val PAGE_H = 842f
private const val VW = 1080f
private const val VH = PAGE_H / PAGE_W * VW
private const val MARGIN = 36f
private const val GAP = 30f
private const val IMAGE_SCALE = 2f

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
    val frame = Color.parseColor("#7CC4FA")
    val total = Color.parseColor("#00E5FF")
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

/** One page at a time, whichever format is being written. */
private interface PageSink {
    fun beginPage(number: Int): Canvas
    fun endPage(canvas: Canvas, number: Int)
}

private class BitmapPageSink : PageSink {
    val pages = mutableListOf<Bitmap>()

    override fun beginPage(number: Int): Canvas {
        val bitmap = Bitmap.createBitmap(
            (PAGE_W * IMAGE_SCALE).toInt(),
            (PAGE_H * IMAGE_SCALE).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.scale(IMAGE_SCALE, IMAGE_SCALE)
        pages.add(bitmap)
        return canvas
    }

    override fun endPage(canvas: Canvas, number: Int) = Unit
}

private class PdfPageSink(private val document: PdfDocument) : PageSink {
    private var current: PdfDocument.Page? = null

    override fun beginPage(number: Int): Canvas {
        val info = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), number).create()
        current = document.startPage(info)
        return current!!.canvas
    }

    override fun endPage(canvas: Canvas, number: Int) {
        current?.let { document.finishPage(it) }
        current = null
    }
}

private data class ChartLine(
    val label: String,
    val values: List<Float>,
    val color: Int,
    val min: Float,
    val max: Float,
    val spikes: Boolean = false
)

/**
 * Draws the same eleven cards as the screen, in virtual 1080 px coordinates.
 *
 * Every card is measured before it is drawn. A card that would cross a page edge
 * moves wholly to the next page, so no chart or text is ever sliced in half.
 */
private class SceneReportRenderer(private val sink: PageSink) {
    private lateinit var canvas: Canvas
    private var pageNumber = 0
    private var cursor = MARGIN
    private var pageTop = 0f

    private fun paint(size: Float, style: Int, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
        this.color = color
    }

    private val micro = paint(14f, Typeface.NORMAL, ReportTheme.dim)
    private val label = paint(16f, Typeface.NORMAL, ReportTheme.muted)
    private val body = paint(20f, Typeface.NORMAL, ReportTheme.text)
    private val medium = paint(20f, Typeface.BOLD, ReportTheme.text)
    private val header = paint(24f, Typeface.BOLD, ReportTheme.text)
    private val kpi = paint(32f, Typeface.BOLD, ReportTheme.blue)
    private val xAxis = paint(13f, Typeface.NORMAL, ReportTheme.dim)
    private val pagePaint = paint(14f, Typeface.NORMAL, ReportTheme.dim)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.card }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ReportTheme.warnBack }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReportTheme.grid
        strokeWidth = 1.5f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(value: String, x: Float, baseline: Float, style: Paint) =
        canvas.drawText(value, x, baseline, style)

    /** Long labels are clipped to their own box instead of overlapping a neighbour. */
    private fun fit(value: String, width: Float, style: Paint): String {
        if (style.measureText(value) <= width) return value
        var short = value
        while (short.isNotEmpty() && style.measureText("$short…") > width) short = short.dropLast(1)
        return "$short…"
    }

    private fun newPage() {
        if (pageNumber > 0) {
            footer()
            sink.endPage(canvas, pageNumber)
        }
        pageNumber++
        canvas = sink.beginPage(pageNumber)
        canvas.save()
        canvas.scale(PAGE_W / VW, PAGE_W / VW)
        canvas.drawColor(ReportTheme.background)
        pageTop = (pageNumber - 1) * VH
        cursor = pageTop + MARGIN
    }

    private fun footer() {
        canvas.save()
        canvas.translate(0f, -pageTop)
        text(
            "Qstats  ·  real recorded data only  ·  unavailable values shown as --",
            MARGIN, VH - 18f, pagePaint
        )
        val number = "Page $pageNumber"
        pagePaint.textAlign = Paint.Align.RIGHT
        text(number, VW - MARGIN, VH - 18f, pagePaint)
        pagePaint.textAlign = Paint.Align.LEFT
        canvas.restore()
    }

    fun start() = newPage()

    /** Reserves a whole card, moving to a new page when it would not fit. */
    private fun card(height: Float, back: Paint = cardPaint, draw: (Float, Float, Float, Float) -> Unit) {
        if (cursor + height > pageTop + VH - MARGIN) newPage()
        val top = cursor
        canvas.save()
        canvas.translate(0f, -pageTop)
        canvas.drawRoundRect(RectF(MARGIN, top, VW - MARGIN, top + height), 36f, 36f, back)
        draw(MARGIN + 36f, top + 24f, VW - MARGIN - 36f, top + height - 24f)
        canvas.restore()
        cursor += height + GAP
    }

    fun finish() {
        footer()
        sink.endPage(canvas, pageNumber)
    }

    fun thermalBanner(peak: Float?, secondsAbove90: Int) {
        if (peak == null || peak < 85f) return
        card(96f, warnPaint) { x0, y0, x1, y1 ->
            val baseline = (y0 + y1) / 2f + 7f
            medium.color = ReportTheme.warn
            text(
                fit("Soc temperature too high MAX:${fmt(peak, 1)}°C", (x1 - x0) * 0.7f, medium),
                x0, baseline, medium
            )
            medium.textAlign = Paint.Align.RIGHT
            text("≥90°C ${secondsAbove90}S", x1, baseline, medium)
            medium.textAlign = Paint.Align.LEFT
        }
    }

    fun deviceRow(session: SessionRecord) {
        card(168f) { x0, y0, x1, _ ->
            val entries = listOf(
                "Platform" to session.device.platform?.uppercase(Locale.US)?.ifBlank { "--" }?.let { it } ?: "--",
                "Host" to session.device.model?.ifBlank { "--" } ?: "--",
                "OS" to session.device.osVersion?.substringBefore(" (")?.ifBlank { "--" } ?: "--",
                "Profile" to session.device.profile?.ifBlank { "--" } ?: "Balanced"
            )
            val columnWidth = (x1 - x0) / 4f
            val colors = intArrayOf(
                ReportTheme.platform, ReportTheme.host, ReportTheme.os, ReportTheme.profile
            )
            entries.forEachIndexed { index, (name, value) ->
                val cx = x0 + columnWidth * index + columnWidth / 2f
                label.textAlign = Paint.Align.CENTER
                text(name, cx, y0 + 14f, label)
                canvas.drawRoundRect(
                    RectF(cx - 28f, y0 + 28f, cx + 28f, y0 + 84f), 12f, 12f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[index] }
                )
                medium.textAlign = Paint.Align.CENTER
                text(fit(value, columnWidth - 16f, medium), cx, y0 + 116f, medium)
            }
            label.textAlign = Paint.Align.LEFT
            medium.textAlign = Paint.Align.LEFT
        }
    }

    fun note(session: SessionRecord) {
        card(88f) { x0, y0, _, _ ->
            body.color = if (session.note.isBlank()) ReportTheme.muted else ReportTheme.text
            text(
                fit(session.note.ifBlank { "Note not set" }, VW - 2 * MARGIN - 72f, body),
                x0, y0 + 6f, body
            )
            body.color = ReportTheme.text
        }
    }

    fun sessionHeader(session: SessionRecord) {
        card(340f) { x0, y0, x1, y1 ->
            val stamp = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
                .format(Date(session.startedAt))
            header.textAlign = Paint.Align.LEFT
            text(
                fit(session.appLabel.ifBlank { session.appPackage }, (x1 - x0) * 0.62f, header),
                x0, y0 + 10f, header
            )
            label.textAlign = Paint.Align.RIGHT
            text(stamp, x1, y0 + 10f, label)
            text("Long: ${duration(session.durationMs)}", x1, y0 + 36f, label)
            label.textAlign = Paint.Align.LEFT

            val jitter = session.jitterMs?.let { j ->
                session.avgFrameTimeMs.takeIf { it > 0f }?.let { j / it * 100f }
            }
            val cells = listOf(
                Triple("MAX", fmt(session.maxFps, 1), "FPS"),
                Triple("MIN", fmt(session.minFps, 1), "FPS"),
                Triple("AVG", fmt(session.avgFps, 1), "FPS"),
                Triple("JITTER", fmt(jitter, 1), "FPS"),
                Triple("1% LOW", fmt(session.low1Percent, 1), "FPS"),
                Triple("AVG", fmt(session.tempSeries.hotspot().avgOrNull(), 1), "SOC(°C)"),
                Triple("MAX", fmt(session.tempSeries.hotspot().maxOrNull(), 1), "Battery(°C)"),
                Triple("AVG", fmt(session.powerSeries.avgOrNull(), 2), "Power(W)")
            )
            val cellW = (x1 - x0) / 4f
            val rowY = y0 + 76f
            cells.forEachIndexed { index, (name, value, unit) ->
                val cx = x0 + cellW * (index % 4) + cellW / 2f
                val cy = rowY + (index / 4) * 96f
                micro.textAlign = Paint.Align.CENTER
                text(name, cx, cy, micro)
                kpi.textAlign = Paint.Align.CENTER
                text(fit(value, cellW - 12f, kpi), cx, cy + 40f, kpi)
                label.textAlign = Paint.Align.CENTER
                text(unit, cx, cy + 62f, label)
            }
            micro.textAlign = Paint.Align.LEFT
            label.textAlign = Paint.Align.LEFT
            kpi.textAlign = Paint.Align.LEFT
        }
    }

    private fun timeTicks(session: SessionRecord): List<String> = listOf(
        "0", duration(session.durationMs / 4), duration(session.durationMs / 2),
        duration(session.durationMs * 3 / 4), duration(session.durationMs)
    )

    private fun chartFrame(
        x0: Float, y0: Float, x1: Float, y1: Float,
        title: String, ticks: List<String>, yLabels: List<String>
    ): Pair<Float, Float> {
        header.textSize = 22f
        text(title, x0, y0, header)
        header.textSize = 24f
        val top = y0 + 22f
        val bottom = y1 - 28f
        val left = x0 + 54f
        val right = x1 - 20f
        for (i in 0..4) {
            val gy = top + (bottom - top) * i / 4f
            canvas.drawLine(x0, gy, x1, gy, gridPaint)
            yLabels.getOrNull(4 - i)?.let { value ->
                micro.textAlign = Paint.Align.RIGHT
                text(value, left - 10f, gy + 5f, micro)
                micro.textAlign = Paint.Align.LEFT
            }
        }
        for (i in 0..4) {
            val gx = left + (right - left) * i / 4f
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            xAxis.textAlign = Paint.Align.CENTER
            text(fit(ticks[i], (right - left) / 4f, xAxis), gx, bottom + 22f, xAxis)
            xAxis.textAlign = Paint.Align.LEFT
        }
        return left to bottom
    }

    private fun drawLines(
        left: Float, top: Float, right: Float, bottom: Float, lines: List<ChartLine>
    ) {
        lines.forEach { line ->
            val values = line.values.takeIf { it.size >= 2 } ?: return@forEach
            val span = (line.max - line.min).takeIf { it > 0f } ?: 1f
            val step = (right - left) / (values.size - 1)
            linePaint.color = line.color
            if (line.spikes) {
                values.forEachIndexed { index, value ->
                    val y = bottom - ((value - line.min) / span).coerceIn(0f, 1f) * (bottom - top)
                    val x = left + step * index
                    canvas.drawLine(x, bottom, x, y, linePaint)
                }
            } else {
                var previousX = left
                var previousY = bottom - ((values[0] - line.min) / span).coerceIn(0f, 1f) * (bottom - top)
                for (i in 1 until values.size) {
                    val x = left + step * i
                    val y = bottom - ((values[i] - line.min) / span).coerceIn(0f, 1f) * (bottom - top)
                    canvas.drawLine(previousX, previousY, x, y, linePaint)
                    previousX = x
                    previousY = y
                }
            }
        }
    }

    private fun legend(x0: Float, x1: Float, entries: List<Pair<String, Int>>, baseline: Float) {
        var width = 0f
        entries.forEach { width += label.measureText(it.first) + 42f }
        var x = (x0 + x1 - width) / 2f
        entries.forEach { (name, color) ->
            canvas.drawRect(RectF(x, baseline - 15f, x + 14f, baseline - 1f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            text(name, x + 20f, baseline, label)
            x += label.measureText(name) + 42f
        }
    }

    fun fpsTemperature(session: SessionRecord) {
        card(560f) { x0, y0, x1, y1 ->
            val ticks = timeTicks(session)
            val (left, bottom) = chartFrame(
                x0, y0, x1, y1 - 60f, "FPS & Temperature(°C)", ticks,
                listOf("120", "96", "72", "48", "24", "0")
            )
            val top = y0 + 22f
            val lines = listOf(
                ChartLine("FPS", session.fpsSeries, ReportTheme.fps, 0f, 120f),
                ChartLine("TEMP(°C)", session.tempSeries.hotspot(), ReportTheme.temp, 40f, 90f),
                ChartLine("CPU(%)", session.cpuSeries, ReportTheme.cpu, 0f, 100f),
                ChartLine("GPU(%)", session.gpuPercentSeries, ReportTheme.gpu, 0f, 100f)
            )
            drawLines(left, top, x1 - 20f, bottom, lines)
            legend(
                x0, x1, listOf(
                    "FPS" to ReportTheme.fps, "TEMP(°C)" to ReportTheme.temp,
                    "CPU(%)" to ReportTheme.cpu, "GPU(%)" to ReportTheme.gpu
                ), y1 - 8f
            )
        }
    }

    fun frameTime(session: SessionRecord) {
        card(500f) { x0, y0, x1, y1 ->
            val ticks = timeTicks(session)
            val (left, bottom) = chartFrame(
                x0, y0, x1, y1 - 46f, "Frame Time(ms)", ticks,
                listOf("100", "80", "60", "40", "20", "0")
            )
            drawLines(
                left, y0 + 22f, x1 - 20f, bottom,
                listOf(
                    ChartLine(
                        "Frame Time", session.frameTimeSeries, ReportTheme.frame, 0f, 100f,
                        spikes = true
                    )
                )
            )
            label.textAlign = Paint.Align.CENTER
            text("MAX: ${fmt(session.maxFrameTimeMs, 0)}ms", (x0 + x1) / 2f, y1 - 8f, label)
            label.textAlign = Paint.Align.LEFT
        }
    }

    fun cpuUsage(session: SessionRecord) {
        card(580f) { x0, y0, x1, y1 ->
            val ticks = timeTicks(session)
            val (left, bottom) = chartFrame(
                x0, y0, x1, y1 - 60f, "CPU Usage(%)", ticks,
                listOf("100", "80", "60", "40", "20", "0")
            )
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
            drawLines(left, y0 + 22f, x1 - 20f, bottom, lines)
            legend(x0, x1, lines.map { it.label to it.color }, y1 - 8f)
        }
    }

    fun cpuFrequency(session: SessionRecord) {
        card(580f) { x0, y0, x1, y1 ->
            val ticks = timeTicks(session)
            val peak = session.cpuGroupFreqSeries.flatMap { row ->
                row.mapNotNull { it?.finiteOrNull() }
            }.maxOrNull() ?: 0f
            val ceiling = maxOf(4000f, peak * 1.05f)
            val (left, bottom) = chartFrame(
                x0, y0, x1, y1 - 60f, "CPU Frequency(MHz)", ticks,
                listOf(
                    fmt(ceiling, 0), fmt(ceiling * 0.8f, 0), fmt(ceiling * 0.6f, 0),
                    fmt(ceiling * 0.4f, 0), fmt(ceiling * 0.2f, 0), "0"
                )
            )
            val lines = session.cpuGroupNames.mapIndexed { index, name ->
                ChartLine(
                    name, column(session.cpuGroupFreqSeries, index).positive(),
                    ReportTheme.cluster[index % ReportTheme.cluster.size], 400f, ceiling
                )
            }
            if (lines.isEmpty()) {
                label.color = ReportTheme.muted
                text("No samples recorded", left, (y0 + bottom) / 2f, label)
                label.color = ReportTheme.text
            } else {
                drawLines(left, y0 + 22f, x1 - 20f, bottom, lines)
            }
            legend(x0, x1, lines.map { it.label to it.color }, y1 - 8f)
        }
    }

    fun beforeAfterCard(title: String, rows: List<Pair<String, String>>) {
        card(110f + rows.size * 44f) { x0, y0, x1, _ ->
            text(title, x0, y0, medium)
            rows.forEachIndexed { index, (name, value) ->
                val baseline = y0 + 44f + index * 44f
                label.textAlign = Paint.Align.LEFT
                text(name, x0, baseline, label)
                body.textAlign = Paint.Align.RIGHT
                text(fit(value, (x1 - x0) * 0.6f, body), x1, baseline, body)
                body.textAlign = Paint.Align.LEFT
            }
        }
    }

    fun deviceDetail(session: SessionRecord) {
        card(700f) { x0, y0, x1, y1 ->
            text("Device Detail", x0, y0, medium)
            val cpuClock = session.cpuFreqSeries.positive()
            val gpuLoad = session.gpuPercentSeries.positive()
            val gpuClock = session.gpuFreqSeries.positive()
            val rows = listOf(
                "CPU CLOCK AVG" to fmt(cpuClock.avgOrNull(), 0),
                "CPU CLOCK MAX" to fmt(cpuClock.maxOrNull(), 0),
                "GPU LOAD AVG" to fmt(gpuLoad.avgOrNull(), 1),
                "GPU LOAD MAX" to fmt(gpuLoad.maxOrNull(), 1),
                "GPU CLOCK AVG" to fmt(gpuClock.avgOrNull(), 0),
                "GPU CLOCK MAX" to fmt(gpuClock.maxOrNull(), 0)
            )
            var y = y0 + 36f
            rows.forEach { (name, value) ->
                label.textAlign = Paint.Align.LEFT
                text(name, x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text(value, x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 28f
            }
            y += 10f
            val cores = maxOf(session.perCoreLoadSeries.size, session.perCoreFreqSeries.size)
            text(
                if (cores == 0) "Per-core (0-8): --"
                else "Per-core (0-${cores - 1})", x0, y, label
            )
            y += 26f
            repeat(minOf(cores, 8)) { core ->
                val load = column(session.perCoreLoadSeries, core).avgOrNull()
                val clock = column(
                    session.perCoreFreqSeries.map { row -> row.map { it?.toFloat() } }, core
                ).avgOrNull()
                text("CPU $core", x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text("${fmt(load, 0)}%   ${fmt(clock, 0)} MHz", x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 24f
            }
            y += 10f
            text("Top threads", x0, y, label)
            y += 26f
            session.threadNames.take(5).forEachIndexed { index, name ->
                text(fit(name, (x1 - x0) * 0.7f, label), x0, y, label)
                body.textAlign = Paint.Align.RIGHT
                text(fmt(session.threadAvgLoads.getOrElse(index) { 0f }, 1) + "%", x1, y, body)
                body.textAlign = Paint.Align.LEFT
                y += 24f
            }
            y += 10f
            text("Shell Logs", x0, y, label)
            y += 24f
            val logs = session.shellLogs.ifBlank { "--" }.take(400)
            val block = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f
                color = ReportTheme.dim
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            logs.lineSequence().take(8).forEach { line ->
                if (y > y1) return@forEach
                text(fit(line, x1 - x0, block), x0, y, block)
                y += 18f
            }
        }
    }
}

private fun renderReport(session: SessionRecord, threshold: Int, sink: PageSink) {
    val painter = SceneReportRenderer(sink)
    painter.start()
    val peak = listOfNotNull(session.maxTempC, session.tempSeries.maxOrNull()).hotspot().maxOrNull()
    val secondsAbove90 = session.tempSeries.count {
        (it.plausibleTempC(MAX_HOTSPOT_TEMP_C) ?: 0f) >= 90f
    }
    painter.thermalBanner(peak, secondsAbove90)
    painter.deviceRow(session)
    painter.note(session)
    painter.sessionHeader(session)
    painter.fpsTemperature(session)
    painter.frameTime(session)
    painter.cpuUsage(session)
    painter.cpuFrequency(session)
    painter.beforeAfterCard(
        "Power(W) & Capacity(%)",
        listOf(
            "Power" to beforeAfter(session.powerSeries, 2, "W"),
            "Capacity" to beforeAfter(session.batterySeries, 0, "%")
        )
    )
    painter.beforeAfterCard(
        "CPU Temperature(°C)",
        listOf(
            "CPU temperature" to beforeAfter(
                session.cpuTempSeries.ifEmpty { session.socTempSeries }.hotspot(), 1, "°C"
            )
        )
    )
    painter.deviceDetail(session)
    painter.finish()
}

private fun safeName(session: SessionRecord): String =
    session.appLabel.replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_').take(24).ifBlank { "session" }

private fun buildPdf(session: SessionRecord, threshold: Int, output: OutputStream) {
    val document = PdfDocument()
    try {
        renderReport(session, threshold, PdfPageSink(document))
        document.writeTo(output)
    } finally {
        document.close()
    }
}

/** Full session report. No CSV, no dependency needed. */
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

    /** Writes the PDF to Downloads/FrameScope and offers to open it. */
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
            val sink = BitmapPageSink()
            renderReport(session, threshold, sink)
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val uris = ArrayList<Uri>()
            sink.pages.forEachIndexed { index, bitmap ->
                val file = File(
                    dir, "Qstats_${safeName(session)}_${session.id}_p${index + 1}.png"
                )
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uris.add(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            }
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Qstats - ${safeName(session)} session")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share session images"))
        }
    }
}

@Composable
fun SessionShareDialog(
    session: SessionRecord,
    threshold: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share session") },
        text = {
            Text(
                "Share the full Qstats breakdown with five graphs for this match. " +
                    "Power, CPU temperature and battery are written as before and after values."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                SessionShare.shareAsPdf(LocalContext.current, session, threshold)
                onDismiss()
            }) { Text("PDF") }
        },
        dismissButton = {
            TextButton(onClick = {
                SessionShare.shareAsImage(LocalContext.current, session, threshold)
                onDismiss()
            }) { Text("Image") }
        }
    )
}
