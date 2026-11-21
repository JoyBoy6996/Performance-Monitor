package com.framescope.monitor.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
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
import com.framescope.monitor.core.steadyWindow
import com.framescope.monitor.core.thermalStatusLabel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every number in a shared report goes through here: bad values become "--". */
private fun fmt(value: Float?, decimals: Int): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

private const val PAGE_W = 1240
private const val PAGE_H = 1754
private const val MARGIN = 60
private const val CONTENT_W = PAGE_W - 2 * MARGIN

private val BG = Color.parseColor("#0B1220")
private val CARD = Color.parseColor("#152139")
private val PRIMARY = Color.parseColor("#E8EEF7")
private val MUTED = Color.parseColor("#9AA9BF")
private val GREEN = Color.parseColor("#22C55E")
private val AMBER = Color.parseColor("#F59E0B")
private val RED = Color.parseColor("#EF4444")
private val CYAN = Color.parseColor("#38BDF8")
private val GRID = Color.parseColor("#26354D")

private data class ShareStat(val label: String, val value: String, val color: Int)

private fun steadyFps(session: SessionRecord): List<Float> = session.fpsSeries.steadyWindow()
    .ifEmpty { session.fpsSeries.filter { it.finiteOrNull() != null && it > 0f } }

private fun shareAtLeast(series: List<Float>, rate: Int): Float? {
    if (series.isEmpty()) return null
    return series.count { it >= rate }.toFloat().times(100f)
        .div(series.size).finiteOrNull()?.coerceIn(0f, 100f)
}

private fun sceneSmoothness(session: SessionRecord): Float? =
    session.sceneSmoothnessPercent.finiteOrNull()?.coerceIn(0f, 100f)
        ?: shareAtLeast(steadyFps(session), 45)

private fun fpsColor(session: SessionRecord, threshold: Int): Int = when {
    (session.avgFps.finiteOrNull() ?: 0f) >= threshold -> GREEN
    (shareAtLeast(steadyFps(session), threshold) ?: 0f) >= 90f -> AMBER
    else -> RED
}

private fun duration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** First and last real reading only - no graph for power, chip temperature or battery. */
private fun beforeAfterValue(values: List<Float>, decimals: Int, unit: String): String {
    val clean = values.mapNotNull { it.finiteOrNull() }
    if (clean.isEmpty()) return "--"
    val first = fmt(clean.first(), decimals)
    val last = fmt(clean.last(), decimals)
    return if (clean.size == 1) "$first$unit" else "$first$unit → $last$unit"
}

/**
 * Draws the same Scene-style breakdown the detail screen shows onto bitmap pages.
 * The very same pages become the PNG images and the PDF, so neither format can
 * be missing a section.
 */
private class ReportPainter {
    val pages = mutableListOf<Bitmap>()
    private lateinit var canvas: Canvas
    private var y = 0f

    private fun paint(size: Float, style: Int, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
        this.color = color
    }

    private val titlePaint = paint(46f, Typeface.BOLD, PRIMARY)
    private val subPaint = paint(22f, Typeface.NORMAL, MUTED)
    private val sectionPaint = paint(27f, Typeface.BOLD, GREEN)
    private val labelPaint = paint(20f, Typeface.NORMAL, MUTED)
    private val valuePaint = paint(32f, Typeface.BOLD, PRIMARY)
    private val metricLabelPaint = paint(21f, Typeface.NORMAL, MUTED)
    private val metricValuePaint = paint(21f, Typeface.BOLD, PRIMARY).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val chartTitlePaint = paint(21f, Typeface.BOLD, PRIMARY)
    private val smallPaint = paint(18f, Typeface.NORMAL, MUTED)
    private val footPaint = paint(17f, Typeface.NORMAL, MUTED)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER
        strokeWidth = 2f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        newPage()
    }

    private fun newPage() {
        val bitmap = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
        canvas.drawColor(BG)
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 8f, barPaint)
        y = MARGIN.toFloat()
        pages.add(bitmap)
    }

    private fun ensure(space: Float) {
        if (y + space > PAGE_H - MARGIN) newPage()
    }

    private fun text(value: String, x: Float, baseline: Float, paint: Paint) {
        canvas.drawText(value, x, baseline, paint)
    }

    fun header(appName: String, dateLine: String) {
        ensure(170f)
        text(appName, MARGIN.toFloat(), y + 50, titlePaint)
        y += 82f
        text(dateLine, MARGIN.toFloat(), y, subPaint)
        y += 26f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, dividerPaint)
        y += 40f
    }

    fun section(title: String) {
        ensure(70f)
        canvas.drawRect(MARGIN.toFloat(), y - 22f, (MARGIN + 6).toFloat(), y + 6f, barPaint)
        text(title, (MARGIN + 22).toFloat(), y + 4, sectionPaint)
        y += 46f
    }

    fun statGrid(items: List<ShareStat>, columns: Int = 3) {
        val rows = (items.size + columns - 1) / columns
        val cellH = 116f
        val gap = 12f
        ensure(rows * cellH + 8f)
        val cw = (CONTENT_W - gap * (columns - 1)) / columns
        items.forEachIndexed { index, item ->
            val x = MARGIN + (index % columns) * (cw + gap)
            val cy = y + (index / columns) * cellH
            canvas.drawRoundRect(
                RectF(x, cy, x + cw, cy + cellH - gap), 14f, 14f, cardPaint
            )
            labelPaint.color = MUTED
            text(item.label, x + 16f, cy + 30f, labelPaint)
            valuePaint.color = item.color
            valuePaint.textSize = if (item.value.length > 8) 25f else 32f
            text(item.value, x + 16f, cy + 78f, valuePaint)
            valuePaint.textSize = 32f
        }
        y += rows * cellH
    }

    fun metricRow(label: String, value: String, color: Int = PRIMARY) {
        ensure(48f)
        metricLabelPaint.color = MUTED
        text(label, (MARGIN + 8).toFloat(), y + 16, metricLabelPaint)
        metricValuePaint.color = color
        text(value, (PAGE_W - MARGIN - 8).toFloat(), y + 16, metricValuePaint)
        y += 24f
        canvas.drawLine(
            (MARGIN + 8).toFloat(), y, (PAGE_W - MARGIN - 8).toFloat(), y, dividerPaint
        )
        y += 14f
    }

    fun paragraph(value: String) {
        labelPaint.color = PRIMARY
        val maxW = CONTENT_W - 16f
        var line = ""
        for (word in value.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (labelPaint.measureText(trial) <= maxW) {
                line = trial
            } else {
                ensure(30f)
                text(line, (MARGIN + 8).toFloat(), y + 16, labelPaint)
                y += 26f
                line = word
            }
        }
        if (line.isNotEmpty()) {
            ensure(30f)
            text(line, (MARGIN + 8).toFloat(), y + 16, labelPaint)
            y += 26f
        }
        y += 8f
    }

    fun chart(
        title: String,
        values: List<Float>,
        color: Int,
        unit: String,
        target: Float? = null
    ) {
        val height = 300f
        ensure(height)
        val top = y
        canvas.drawRoundRect(
            RectF(MARGIN.toFloat(), top, (PAGE_W - MARGIN).toFloat(), top + height - 12f),
            16f, 16f, cardPaint
        )
        chartTitlePaint.color = PRIMARY
        text(title, (MARGIN + 20).toFloat(), top + 34, chartTitlePaint)

        val x0 = (MARGIN + 24).toFloat()
        val x1 = (PAGE_W - MARGIN - 24).toFloat()
        val cy0 = top + 56f
        val cy1 = top + 208f
        for (i in 0..3) {
            val gy = cy0 + (cy1 - cy0) * i / 3f
            canvas.drawLine(x0, gy, x1, gy, gridPaint)
        }

        val points = values.mapNotNull { it.finiteOrNull() }
        if (points.size >= 2) {
            val low: Float
            val high: Float
            if (target != null) {
                low = minOf(points.minOrNull()!!, target)
                high = maxOf(points.maxOrNull()!!, target)
            } else {
                val span = (points.maxOrNull()!! - points.minOrNull()!!).coerceAtLeast(1f)
                low = points.minOrNull()!! - span * 0.1f
                high = points.maxOrNull()!! + span * 0.1f
            }
            val range = (high - low).coerceAtLeast(1f)
            fun px(index: Int) = x0 + (x1 - x0) * index / (points.size - 1).toFloat()
            fun py(value: Float) = cy1 - (cy1 - cy0) * (value - low) / range

            if (target != null) {
                val ty = py(target)
                canvas.drawLine(x0, ty, x1, ty, targetPaint)
            }
            linePaint.color = color
            var prevX = px(0)
            var prevY = py(points[0])
            for (i in 1 until points.size) {
                val nextX = px(i)
                val nextY = py(points[i])
                canvas.drawLine(prevX, prevY, nextX, nextY, linePaint)
                prevX = nextX
                prevY = nextY
            }
        } else {
            smallPaint.color = MUTED
            text("No samples recorded", x0, (cy0 + cy1) / 2, smallPaint)
        }

        val average = if (points.isEmpty()) null else points.average().toFloat().finiteOrNull()
        smallPaint.color = MUTED
        text(
            "MIN ${fmt(points.minOrNull(), 1)}   AVG ${fmt(average, 1)}   " +
                "MAX ${fmt(points.maxOrNull(), 1)} $unit",
            (MARGIN + 20).toFloat(), top + 252, smallPaint
        )
        y += height
    }

    fun footer() {
        ensure(60f)
        y += 8f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, dividerPaint)
        y += 28f
        text(
            "FrameScope  ·  real recorded data only  ·  unavailable values shown as --",
            MARGIN.toFloat(), y, footPaint
        )
    }
}

private fun renderPages(session: SessionRecord, threshold: Int): List<Bitmap> {
    val painter = ReportPainter()
    val formatter = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    val appName = session.appLabel.ifBlank {
        session.appPackage.ifBlank { "Unknown app" }
    }
    val color = fpsColor(session, threshold)

    painter.header(
        appName,
        "${formatter.format(Date(session.startedAt))}  ·  ${duration(session.durationMs)}"
    )

    painter.section("Match stats")
    painter.statGrid(
        listOf(
            ShareStat("Average", "${fmt(session.avgFps, 1)} fps", color),
            ShareStat("Peak", "${fmt(session.maxFps, 0)} fps", PRIMARY),
            ShareStat("Lowest", "${fmt(session.minFps, 0)} fps", RED),
            ShareStat("5% low", "${fmt(session.low5Percent, 1)} fps", AMBER),
            ShareStat("Variance", fmt(session.fpsVariance, 1), PRIMARY),
            ShareStat(">=45 smooth", "${fmt(sceneSmoothness(session), 1)} %", GREEN)
        )
    )

    painter.section("Frame pacing")
    painter.metricRow("Frames", session.totalFrames.toString())
    painter.metricRow(
        "Jank frames", session.jankFrames.toString(),
        if (session.jankFrames > 0) AMBER else PRIMARY
    )
    painter.metricRow("Jank-free frames", fmt(session.smoothness, 1) + " %")
    painter.metricRow("1% low", fmt(session.low1Percent, 1) + " fps", AMBER)
    painter.metricRow("Average frame time", fmt(session.avgFrameTimeMs, 2) + " ms")
    painter.metricRow(
        "Longest frame", fmt(session.longestFrameMs, 1) + " ms",
        if (session.longestFrameMs > 33f) RED else PRIMARY
    )
    session.jitterMs?.let { painter.metricRow("Jitter", fmt(it, 2) + " ms") }

    painter.section("Target & cap")
    painter.metricRow(
        "Held $threshold fps target", fmt(shareAtLeast(steadyFps(session), threshold), 1) + " %"
    )
    val cap = session.capSeries.lastOrNull()?.takeIf { it > 0f }
    painter.metricRow(
        "Detected FPS cap",
        if (cap == null) "--" else fmt(cap, 0) + " fps" +
            if (session.thermalCapSeconds > 0) " (heat)" else ""
    )
    if (session.capChanges.isNotEmpty()) {
        painter.metricRow("Cap changes", session.capChanges.size.toString())
    }

    painter.section("Heat & power")
    session.maxTempC.plausibleTempC(MAX_HOTSPOT_TEMP_C)?.let {
        painter.metricRow("Max temperature", fmt(it, 1) + " °C", AMBER)
    }
    session.peakThermalStatus?.let { status ->
        painter.metricRow(
            "Thermal state", thermalStatusLabel(status),
            if (status > 0) AMBER else PRIMARY
        )
    }
    if (session.throttledSeconds > 0) {
        painter.metricRow("Throttled time", duration(session.throttledSeconds * 1000L), AMBER)
    }
    session.avgPowerW?.let { painter.metricRow("Average power", fmt(it, 2) + " W") }

    painter.section("Device load")
    session.avgCpu?.let { painter.metricRow("Average CPU", fmt(it, 1) + " %") }
    if (session.batterySeries.isNotEmpty()) {
        painter.metricRow(
            "Battery",
            "${fmt(session.batterySeries.first(), 0)} → ${fmt(session.batterySeries.last(), 0)} %",
            GREEN
        )
    }

    painter.section("Test setup")
    painter.metricRow("Device", session.device.model ?: "--")
    painter.metricRow("Platform", session.device.platform ?: "--")
    painter.metricRow("Resolution", session.device.resolution ?: "--")
    painter.metricRow("OS", session.device.osVersion ?: "--")
    painter.metricRow("Target used", "$threshold fps")

    if (session.note.isNotBlank()) {
        painter.section("Session note")
        painter.paragraph(session.note)
    }

    painter.section("Graphs")
    painter.chart(
        "Frame rate (fps)",
        session.fpsSeries.filter { it.finiteOrNull() != null && it > 0f },
        color, "fps", threshold.toFloat()
    )
    painter.chart("Frame time (ms)", session.frameTimeSeries, AMBER, "ms")
    painter.chart(
        "CPU load (%)", session.cpuSeries.mapNotNull { it.finiteOrNull() }, CYAN, "%"
    )
    painter.chart(
        "Temperature (°C)",
        session.tempSeries.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) },
        RED, "°C"
    )
    if (session.cpuFreqSeries.isNotEmpty()) {
        painter.chart(
            "CPU frequency (MHz)",
            session.cpuFreqSeries.filter { it.finiteOrNull() != null && it > 0f },
            CYAN, "MHz"
        )
    }

    painter.section("Before & after")
    painter.metricRow("Power draw", beforeAfterValue(session.powerSeries, 2, " W"))
    painter.metricRow(
        "CPU temperature",
        beforeAfterValue(
            session.cpuTempSeries.ifEmpty { session.socTempSeries }
                .mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) },
            1, "°C"
        )
    )
    painter.metricRow("Battery", beforeAfterValue(session.batterySeries, 0, "%"))

    painter.footer()
    return painter.pages
}

private fun safeName(session: SessionRecord): String =
    session.appLabel.replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_').take(24).ifBlank { "session" }

/** Builds and shares the full session report. No CSV, no dependency needed. */
object SessionShare {
    fun shareAsPdf(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val pages = renderPages(session, threshold)
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val file = File(dir, "FrameScope_${safeName(session)}_${session.id}.pdf")
            val document = PdfDocument()
            try {
                pages.forEachIndexed { index, bitmap ->
                    val info = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                    val page = document.startPage(info)
                    page.canvas.drawBitmap(bitmap, null, Rect(0, 0, 595, 842), null)
                    document.finishPage(page)
                }
                FileOutputStream(file).use { output -> document.writeTo(output) }
            } finally {
                document.close()
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.files", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "FrameScope - ${safeName(session)} session")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share session PDF"))
        }
    }

    fun shareAsImage(context: Context, session: SessionRecord, threshold: Int) {
        runCatching {
            val pages = renderPages(session, threshold)
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val uris = ArrayList<Uri>()
            pages.forEachIndexed { index, bitmap ->
                val file = File(
                    dir, "FrameScope_${safeName(session)}_${session.id}_p${index + 1}.png"
                )
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                uris.add(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            }
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "FrameScope - ${safeName(session)} session")
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
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share session") },
        text = {
            Text(
                "Share the full Scene-style breakdown with charts for this match. " +
                    "Pick PDF for one document, or Image for the same pages as pictures."
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
