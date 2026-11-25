package com.framescope.monitor.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framescope.monitor.core.MAX_BATTERY_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.ShellLogs
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.chartTemperatures
import com.framescope.monitor.core.chipTemperatureSeries
import com.framescope.monitor.core.hardwareCharts
import com.framescope.monitor.core.frameTimeCeiling
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.ui.theme.MonoInline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The palette of the detailed report.
 *
 * The colours are the ones of the app, so the report of a run looks like the
 * rest of the screen instead of like a page out of another program. The PDF
 * that the app exports keeps its own colours and is drawn by its own renderer.
 */
private object Scene {
    val background = Color.Transparent
    val card = Color(0xFF121620)
    val border = Color(0xFF293241)
    val text = Color(0xFFF1F4FA)
    val muted = Color(0xFF929BAA)
    val blue = Color(0xFF56C8F0)
    val fps = Color(0xFF55D6A1)
    val temp = Color(0xFFFFAA63)
    val cpu = Color(0xFFB18CFF)
    val gpu = Color(0xFF55B9D8)
    val frame = Color(0xFF62A9FF)
    val total = Color(0xFF35D6E8)
    val power = Color(0xFFE86FA8)
    val capacity = Color(0xFF7A8CFF)
    val chipTemp = Color(0xFF9FD3C7)
    val warnBack = Color(0xFF2A211B)
    val warn = Color(0xFFFF8C42)
    val platform = Color(0xFFB18CFF)
    val host = Color(0xFF62A9FF)
    val os = Color(0xFF55D6A1)
    val profile = Color(0xFFFFAA63)
    val cluster = listOf(
        Color(0xFFB18CFF), Color(0xFF55D6A1), Color(0xFF62A9FF), Color(0xFFFFAA63)
    )
}

private fun sfmt(value: Float?, decimals: Int): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

private fun sDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
    else String.format(Locale.US, "%dm %02ds", m, s)
}

/** Six evenly spaced ticks: 0, 20%, 40%, 60%, 80%, 100% of the run. */
private fun sTick(ms: Long): String {
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

private fun timeTicks(session: SessionRecord): List<String> =
    (0..5).map { index -> sTick(session.durationMs * index / 5L) }

private fun sPositive(values: List<Float>): List<Float> =
    values.mapNotNull { it.finiteOrNull()?.takeIf { v -> v > 0f } }

private fun sClamped(values: List<Float>, min: Float, max: Float): List<Float> =
    values.mapNotNull { it.finiteOrNull() }.map { it.coerceIn(min, max) }

private fun sColumns(values: List<List<Float?>>, index: Int): List<Float> =
    values.mapNotNull { it.getOrNull(index)?.finiteOrNull() }

private fun List<Float>.avgOrNull(): Float? =
    if (isEmpty()) null else average().toFloat().finiteOrNull()

/**
 * Chip/SoC sensor, 0..110 °C. Battery skin temperature is never used here.
 *
 * The list has one value for every second of the run, so the line of the
 * temperature sits above the line of the frame rate at the same second. A
 * second without a reading is a gap and not a drop to zero.
 */
private fun socTemps(session: SessionRecord): List<Float> =
    session.chartTemperatures().map { it.takeIf { value -> value.isFinite() && value > 0f && value <= 110f } ?: Float.NaN }

/**
 * The temperature of the chip, on every second of the run.
 *
 * The battery is never part of it, and a second without a reading is a gap.
 */
private fun chipTempSeries(session: SessionRecord): List<Float> =
    session.chipTemperatureSeries().map { it.takeIf { value -> value.isFinite() && value > 0f && value <= 110f } ?: Float.NaN }

private fun batteryTemps(session: SessionRecord): List<Float> =
    session.batteryTempSeries.mapNotNull { it.plausibleTempC(MAX_BATTERY_TEMP_C) }

/**
 * Mean chip temperature; an old run may only have the recorded session peak.
 *
 * The chip alone: the chart of the frame rate may fall back to the battery of
 * an old run, and the mean of the processor may not.
 */
private fun avgSocTemp(session: SessionRecord): Float? =
    chipTempSeries(session).mapNotNull { it.finiteOrNull() }.avgOrNull()
        ?: session.maxTempC?.plausibleTempC(110f)

/** Peak BatteryManager reading; a legacy run may only carry the session peak. */
private fun maxBatteryTemp(session: SessionRecord): Float? =
    batteryTemps(session).maxOrNull()
        ?: session.maxTempC?.plausibleTempC(MAX_BATTERY_TEMP_C)

/**
 * The highest temperature of the chip.
 *
 * A warning about a hot chip that was triggered by the temperature of the
 * battery is a false warning.
 */
private fun peakSoc(session: SessionRecord): Float? =
    listOfNotNull(
        session.maxTempC,
        chipTempSeries(session).mapNotNull { it.finiteOrNull() }.maxOrNull()
    )
        .mapNotNull { it.plausibleTempC(110f) }
        .maxOrNull()

private val tnumSmall = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 10.sp, color = Scene.muted
)
private val tnumAxis = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 9.sp, color = Scene.muted
)
private val tnumTick = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 8.sp, color = Scene.muted
)
private val tnumValue = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 18.sp, fontWeight = FontWeight.Bold,
    color = Scene.blue
)
private val tnumBody = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 12.sp, color = Scene.text
)

@Composable
private fun SceneCard(
    modifier: Modifier = Modifier,
    radius: Int = 12,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Scene.card, RoundedCornerShape(radius.dp))
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun Legend(vararg entries: Pair<String, Color>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.size(10.dp))
            Box(Modifier.size(7.dp).background(entry.second, RoundedCornerShape(2.dp)))
            Spacer(Modifier.size(4.dp))
            Text(text = entry.first, style = tnumAxis)
        }
    }
}

private data class ChartLineUi(
    val values: List<Float>,
    val color: Color,
    val min: Float,
    val max: Float,
    val spikes: Boolean = false,
    val strokeWidth: Float = 2f
)

/**
 * Full-size vector chart. Grid, all six time ticks and the legend/footer area are
 * always drawn, even for a legacy session with no samples for a series.
 */
@Composable
private fun SceneLineChart(
    lines: List<ChartLineUi>,
    leftLabels: List<String>,
    rightLabels: List<String> = emptyList(),
    timeLabels: List<String>,
    height: Dp = 160.dp
) {
    val plotHeight = height - 24.dp
    val leftWidth = 34.dp
    val rightWidth = if (rightLabels.isEmpty()) 6.dp else 38.dp
    // The last sample must never touch the right axis numbers. Keep a full 12dp
    // gutter between the final gridline and the column that holds those labels.
    val plotEndPad = rightWidth + 12.dp
    Box(Modifier.fillMaxWidth().height(height)) {
        Canvas(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(plotHeight)
                .padding(start = leftWidth, end = plotEndPad)
        ) {
            val bottom = size.height
            val lastTick = (leftLabels.size - 1).coerceAtLeast(1)
            leftLabels.forEachIndexed { index, _ ->
                val y = bottom * index / lastTick
                drawLine(Scene.border, Offset(0f, y), Offset(size.width, y), 1f)
            }
            repeat(6) { index ->
                val x = size.width * index / 5f
                drawLine(Scene.border, Offset(x, 0f), Offset(x, bottom), 1f)
            }
            lines.forEach { line ->
                // The x of a reading is the moment it was taken. A second
                // without a reading is a gap in the line and not a point that
                // pulls the rest of the run to the left.
                val values = line.values
                if (values.count { it.finiteOrNull() != null } < 2) return@forEach
                val span = (line.max - line.min).takeIf { it > 0f } ?: 1f
                val step = size.width / (values.size - 1).coerceAtLeast(1)
                // Zero at the bottom, maximum at the top for every line.
                fun yOf(value: Float): Float =
                    bottom - ((value - line.min) / span).coerceIn(0f, 1f) * bottom
                if (line.spikes) {
                    values.forEachIndexed { index, value ->
                        val real = value.finiteOrNull() ?: return@forEachIndexed
                        val x = index * step
                        drawLine(
                            line.color, Offset(x, bottom), Offset(x, yOf(real)),
                            line.strokeWidth
                        )
                    }
                } else {
                    val path = Path()
                    var started = false
                    values.forEachIndexed { index, value ->
                        val real = value.finiteOrNull()
                        if (real == null) {
                            // The line starts again after the gap.
                            started = false
                            return@forEachIndexed
                        }
                        val x = index * step
                        if (!started) {
                            path.moveTo(x, yOf(real))
                            started = true
                        } else {
                            path.lineTo(x, yOf(real))
                        }
                    }
                    if (started) {
                        drawPath(path, line.color, style = Stroke(width = line.strokeWidth))
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopStart).width(leftWidth).height(plotHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            leftLabels.forEach { Text(text = it, style = tnumAxis, maxLines = 1) }
        }
        if (rightLabels.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.TopEnd).width(rightWidth).height(plotHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                rightLabels.forEach { Text(text = it, style = tnumAxis, maxLines = 1) }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(24.dp)
                .padding(start = leftWidth, end = plotEndPad),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            timeLabels.take(6).forEach { Text(text = it, style = tnumTick, maxLines = 1) }
        }
    }
}

/** Crisp 22dp Material vectors, never coloured squares. */
@Composable
private fun DeviceIcon(kind: Int, tint: Color) {
    val vector = when (kind) {
        0 -> Icons.Filled.Memory
        1 -> Icons.Filled.Smartphone
        2 -> Icons.Filled.Android
        else -> Icons.Filled.Speed
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(22.dp)
    )
}

private fun Drawable.toSquareBitmap(size: Int): Bitmap? = runCatching {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    bitmap
}.getOrNull()

/** The version of an installed app, so the report says which build was measured. */
@Suppress("DEPRECATION")
internal fun appVersion(context: Context, packageName: String): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(0L)
        ).versionName
    } else {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }
}.getOrNull()

/** Real launcher icon at 38dp with 8dp corners, and a gamepad when unavailable. */
@Composable
fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()?.toSquareBitmap(76)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            Modifier.size(38.dp).background(Scene.card, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = Scene.blue,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** The eleven Scene cards. UI and PDF draw exactly the same values and order. */
@Composable
fun SceneSessionCards(
    session: SessionRecord,
    threshold: Int,
    onUpdateNote: (String) -> Unit
) {
    // The temperature of the chip: the battery is never part of it.
    val temps = chipTempSeries(session)
    val peak = peakSoc(session)
    val secondsAbove90 = temps.count { it >= 90f }
    // The chip temperature, on the scale of its chart.
    val chipTemps = temps.map { it.coerceIn(0f, 110f) }

    Column(
        Modifier.fillMaxWidth().background(Scene.background),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1 - a warning only, never a cool "0 seconds" status strip.
        val hot = peak != null && peak >= 85f
        if (hot) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Scene.warnBack, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Soc temperature too high MAX:${sfmt(peak, 1)}°C",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        "≥90°C ${secondsAbove90}S",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }

        // Card 2 - device info with vector icons.
        SceneCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DeviceColumn(
                    "Platform", session.device.platform?.uppercase(Locale.US) ?: "--",
                    0, Scene.platform
                )
                DeviceColumn(
                    "Host",
                    session.device.model?.replace(
                        Regex("^vivo\\s+", RegexOption.IGNORE_CASE), ""
                    ) ?: "--",
                    1, Scene.host
                )
                DeviceColumn(
                    "OS", session.device.osVersion?.substringBefore(" (") ?: "--",
                    2, Scene.os
                )
                DeviceColumn("Profile", session.device.profile ?: "Balanced", 3, Scene.profile)
            }
        }

        // Card 3 - editable note.
        SceneNoteCard(session, onUpdateNote)

        // Card 4 - launcher icon and the exact 2 x 4 KPI grid.
        SceneCard {
            val context = LocalContext.current
            val version = remember(session.appPackage) { appVersion(context, session.appPackage) }
            val timestamp = remember(session.id) {
                SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
            }.format(Date(session.startedAt))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(session.appPackage)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    val name = session.appLabel.ifBlank {
                        session.appPackage.substringAfterLast('.')
                    }
                    Text(
                        if (version.isNullOrBlank()) name else "$name($version)",
                        style = tnumBody.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text("Long: ${sDuration(session.durationMs)}", style = tnumSmall)
                }
                Text(timestamp, style = tnumSmall, maxLines = 2)
            }
            Spacer(Modifier.height(14.dp))
            val jitter = session.jitterMs?.let { value ->
                session.avgFrameTimeMs.takeIf { it > 0f }?.let { value / it * 100f }
            }
            Row(Modifier.fillMaxWidth()) {
                KpiCell("MAX", sfmt(session.maxFps, 1), "FPS")
                KpiCell("MIN", sfmt(session.minFps, 1), "FPS")
                KpiCell("AVG", sfmt(session.avgFps, 1), "FPS")
                // The jitter is a percentage of the frame time, not a frame
                // rate. The unit under it says what it is.
                KpiCell("JITTER", sfmt(jitter, 1) + "%", "Variance")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                KpiCell("1% LOW", sfmt(session.low1Percent, 1), "FPS")
                KpiCell("AVG", sfmt(avgSocTemp(session), 1), "SOC(°C)")
                KpiCell("MAX", sfmt(maxBatteryTemp(session), 1), "Battery(°C)")
                KpiCell("AVG", sfmt(session.avgPowerW, 2), "Power(W)")
            }
        }

        // A chart without readings is an empty box. The charts of the
        // hardware are only drawn when the run really measured them, and the
        // ones that are missing are named in one line instead.
        // One answer for the app and for the report: which charts of the
        // hardware this run really measured.
        val charts = session.hardwareCharts()
        val cpuHasData = charts.cpuUsage
        val freqHasData = charts.cpuFrequency
        val tempHasData = charts.cpuTemperature
        val missingCharts = charts.missing
        var showEmptyCharts by remember(session.id) { mutableStateOf(false) }
        if (missingCharts.isNotEmpty()) {
            SceneCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CPU & GPU hardware counters were unavailable for this session",
                        style = tnumBody.copy(color = Scene.muted),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showEmptyCharts = !showEmptyCharts }) {
                        Text(
                            if (showEmptyCharts) "Hide empty charts" else "Show empty charts",
                            style = tnumSmall.copy(color = Scene.blue)
                        )
                    }
                }
            }
        }

        // Card 5 - the frame rate and the temperature of the chip on one
        // chart, the way the PDF shows it.
        SceneCard {
            Text("FPS & Temperature(°C)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val fpsCeiling = (sPositive(session.fpsSeries).maxOrNull() ?: 120f)
                .let { if (it > 130f) 144f else 120f }
            // A line without readings is not drawn, and it is not named in the
            // legend either: an empty series looks like a flat zero.
            val fpsLine = ChartLineUi(
                sClamped(session.fpsSeries, 0f, fpsCeiling), Scene.fps, 0f, fpsCeiling
            )
            val tempLine = ChartLineUi(socTemps(session), Scene.temp, 0f, 110f)
            val cpuLine = ChartLineUi(
                sClamped(session.cpuSeries, 0f, 100f), Scene.total, 0f, 100f
            )
            val gpuLine = ChartLineUi(
                sClamped(session.gpuPercentSeries, 0f, 100f), Scene.gpu, 0f, 100f
            )
            val drawn = listOf(
                "FPS" to Scene.fps to fpsLine,
                "TEMP(°C)" to Scene.temp to tempLine,
                "CPU(%)" to Scene.total to cpuLine,
                "GPU(%)" to Scene.gpu to gpuLine
            ).filter { it.second.values.count { value -> value.finiteOrNull() != null } >= 2 }
            val tempValues = socTemps(session).mapNotNull { it.finiteOrNull() }
            SceneLineChart(
                lines = drawn.map { it.second },
                leftLabels = (5 downTo 0).map { sfmt(fpsCeiling * it / 5f, 0) },
                rightLabels = listOf("110", "88", "66", "44", "22", "0"),
                timeLabels = timeTicks(session)
            )
            if (drawn.isNotEmpty()) {
                Legend(*drawn.map { it.first }.toTypedArray())
            }
            Text(
                "TEMP MAX: ${sfmt(tempValues.maxOrNull(), 1)}°C   " +
                    "MIN: ${sfmt(tempValues.minOrNull(), 1)}°C   " +
                    "AVG: ${sfmt(tempValues.avgOrNull(), 1)}°C",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Card 6 - one spike per frame time, so a stall is a spike and not a
        // slow line.
        SceneCard {
            Text("Frame Time(ms)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            // The scale follows the run: a 10 ms run in a 100 ms scale would
            // hide every stutter in the bottom tenth of the chart.
            val maxFrame = frameTimeCeiling(session.frameTimeSeries, session.maxFrameTimeMs)
            SceneLineChart(
                lines = listOf(
                    ChartLineUi(
                        sClamped(session.frameTimeSeries, 0f, maxFrame),
                        Scene.frame, 0f, maxFrame, spikes = true, strokeWidth = 2.5f
                    )
                ),
                leftLabels = (5 downTo 0).map { sfmt(maxFrame * it / 5f, 0) },
                timeLabels = timeTicks(session),
                height = 150.dp
            )
            Text(
                "MAX: ${sfmt(session.maxFrameTimeMs, 0)}ms",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // The load of the processor, cluster by cluster. The total has its own
        // chart above, next to the frame rate.
        if (cpuHasData || showEmptyCharts) {
        SceneCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CPU Usage(%)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
                Text("Chart Options", style = tnumSmall)
            }
            Spacer(Modifier.height(8.dp))
            val groupLines = session.cpuGroupNames.mapIndexed { index, _ ->
                ChartLineUi(
                    sClamped(sColumns(session.cpuGroupSeries, index), 0f, 100f),
                    Scene.cluster[index % Scene.cluster.size], 0f, 100f
                )
            }
            SceneLineChart(
                lines = listOf(
                    ChartLineUi(sClamped(session.cpuSeries, 0f, 100f), Scene.total, 0f, 100f)
                ) + groupLines,
                leftLabels = listOf("100", "80", "60", "40", "20", "0"),
                timeLabels = timeTicks(session)
            )
            Legend(
                *((listOf("Total" to Scene.total) + session.cpuGroupNames.mapIndexed { index, name ->
                    name to Scene.cluster[index % Scene.cluster.size]
                }).toTypedArray())
            )
        }
        }

        // Card 8 - full grid even when an old run has no frequency samples.
        if (freqHasData || showEmptyCharts) {
        SceneCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CPU Frequency(MHz)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
                Text("Chart Options", style = tnumSmall)
            }
            Spacer(Modifier.height(8.dp))
            val allFreq = sPositive(
                session.cpuGroupFreqSeries.flatMap { row -> row.mapNotNull { it?.finiteOrNull() } }
            ).ifEmpty { sPositive(session.cpuFreqSeries) }
            val ceiling = maxOf(4000f, (allFreq.maxOrNull() ?: 0f) * 1.05f)
            val groupLines = if (session.cpuGroupNames.isEmpty()) {
                listOf(ChartLineUi(sClamped(session.cpuFreqSeries, 0f, ceiling),
                    Scene.cluster[0], 0f, ceiling))
            } else {
                session.cpuGroupNames.mapIndexed { index, _ ->
                    ChartLineUi(
                        sClamped(sColumns(session.cpuGroupFreqSeries, index), 0f, ceiling),
                        Scene.cluster[index % Scene.cluster.size], 0f, ceiling
                    )
                }
            }
            SceneLineChart(
                lines = groupLines,
                leftLabels = (5 downTo 0).map { sfmt(ceiling * it / 5f, 0) },
                timeLabels = timeTicks(session)
            )
            Legend(
                *(session.cpuGroupNames.ifEmpty { listOf("CPU") }
                    .mapIndexed { index, name -> name to Scene.cluster[index % Scene.cluster.size] }
                    .toTypedArray())
            )
            Text(
                "MAX: ${sfmt(allFreq.maxOrNull(), 0)}MHz   " +
                    "MIN: ${sfmt(allFreq.minOrNull(), 0)}MHz   " +
                    "AVG: ${sfmt(allFreq.avgOrNull(), 0)}MHz",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        }

        // Card 9 - Power(W) left axis 0..8, Capacity(%) right axis 0..100.
        SceneCard {
            Text("Power(W) & Capacity(%)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val power = sClamped(session.powerSeries, 0f, 8f)
            val capacity = sClamped(session.batterySeries, 0f, 100f)
            SceneLineChart(
                lines = listOf(
                    ChartLineUi(power, Scene.power, 0f, 8f),
                    ChartLineUi(capacity, Scene.capacity, 0f, 100f)
                ),
                leftLabels = listOf("8", "6.4", "4.8", "3.2", "1.6", "0"),
                rightLabels = listOf("100", "80", "60", "40", "20", "0"),
                timeLabels = timeTicks(session),
                height = 150.dp
            )
            Legend("Power(W)" to Scene.power, "Capacity(%)" to Scene.capacity)
            val powerValues = sPositive(session.powerSeries)
            Text(
                "MAX: ${sfmt(powerValues.maxOrNull(), 2)}W   " +
                    "MIN: ${sfmt(powerValues.minOrNull(), 2)}W   " +
                    "AVG: ${sfmt(powerValues.avgOrNull(), 2)}W",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Card 10 - dedicated CPU/SoC temperature line, 0..110 °C.
        if (tempHasData || showEmptyCharts) {
        SceneCard {
            Text("CPU Temperature(°C)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val tempValues = temps.mapNotNull { it.finiteOrNull() }
            SceneLineChart(
                lines = listOf(
                    ChartLineUi(temps.map { it.coerceIn(0f, 110f) }, Scene.chipTemp, 0f, 110f)
                ),
                leftLabels = listOf("110", "88", "66", "44", "22", "0"),
                timeLabels = timeTicks(session),
                height = 150.dp
            )
            Text(
                "MAX: ${sfmt(tempValues.maxOrNull(), 1)}°C   " +
                    "MIN: ${sfmt(tempValues.minOrNull(), 1)}°C   " +
                    "AVG: ${sfmt(tempValues.avgOrNull(), 1)}°C",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        }

        // Card 11 - one compact Device Detail card, then the real Shell Logs.
        SceneCard {
            Text("Device Detail", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val freqs = sPositive(
                session.cpuGroupFreqSeries.flatMap { row -> row.mapNotNull { it?.finiteOrNull() } }
            ).ifEmpty { sPositive(session.cpuFreqSeries) }
            val gpuLoad = sPositive(session.gpuPercentSeries)
            val gpuClock = sPositive(session.gpuFreqSeries)
            // A row without a reading is not printed. A legacy run without
            // counters gets one clean line instead of a page of "--".
            val detailRows = listOf(
                "CPU CLOCK AVG" to sfmt(freqs.avgOrNull(), 0),
                "CPU CLOCK MAX" to sfmt(freqs.maxOrNull(), 0),
                "GPU LOAD AVG" to sfmt(gpuLoad.avgOrNull(), 1),
                "GPU LOAD MAX" to sfmt(gpuLoad.maxOrNull(), 1),
                "GPU CLOCK AVG" to sfmt(gpuClock.avgOrNull(), 0),
                "GPU CLOCK MAX" to sfmt(gpuClock.maxOrNull(), 0)
            ).filter { it.second != "--" }
            if (detailRows.isEmpty()) {
                Text(
                    "Hardware counters unavailable in this legacy session",
                    style = tnumSmall.copy(color = Scene.muted)
                )
            } else {
                detailRows.forEach { row -> DetailRow(row.first, row.second) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Per-core (0-7)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val loads = session.perCoreLoadSeries
            val clocks = session.perCoreFreqSeries
            val cores = maxOf(loads.size, clocks.size)
            if (cores == 0) {
                Text("Per-core: --", style = tnumSmall)
            } else {
                repeat(cores) { core ->
                    val load = sColumns(loads, core).avgOrNull()
                    val clock = sPositive(
                        sColumns(clocks.map { row -> row.map { it?.toFloat() } }, core)
                    ).avgOrNull()
                    DetailRow("CPU $core", "${sfmt(load, 0)}%  ${sfmt(clock, 0)} MHz")
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Top threads", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            if (session.threadNames.isEmpty()) {
                Text("--", style = tnumSmall)
            } else {
                session.threadNames.take(6).forEachIndexed { index, name ->
                    val load = session.threadAvgLoads.getOrNull(index)
                    DetailRow(name, if (load == null) "--" else sfmt(load, 1) + "%")
                }
            }

            Spacer(Modifier.height(12.dp))
            ShellLogsCard(session)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = tnumSmall, maxLines = 1)
        Text(value, style = tnumSmall.copy(color = Scene.text))
    }
}

@Composable
private fun ShellLogsCard(session: SessionRecord) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    // The first line says how the run was measured: the privileged shell
    // answered, or the app read what it could by itself. A line full of "--"
    // is not a reading, and printing it next to the other numbers makes a run
    // look measured when it was not.
    val head = ShellLogs.head(session.shellLogs)
    val details = ShellLogs.body(session.shellLogs)
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = details.isNotEmpty()) { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shell Logs", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            // A button that opens nothing is a button that does nothing.
            if (details.isNotEmpty()) {
                Text(if (expanded) "Hide" else "Expand", style = tnumSmall, color = Scene.blue)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (head != null) {
            Text(head, style = tnumSmall.copy(color = Scene.blue))
        } else {
            Text(
                "No shell output was captured for this run.",
                style = tnumSmall.copy(color = Scene.muted)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            // The identity is already the cyan line above. Printing it again here
            // made every diagnostic block start with the same row twice.
            Text(
                details.joinToString("\n").ifBlank {
                    "The privileged shell did not answer for this run."
                },
                style = tnumSmall.copy(fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun RowScope.DeviceColumn(label: String, value: String, icon: Int, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = tnumSmall, maxLines = 1)
        Spacer(Modifier.size(6.dp))
        DeviceIcon(icon, color)
        Spacer(Modifier.size(6.dp))
        Text(text = value, style = tnumBody.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun RowScope.KpiCell(label: String, value: String, unit: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = tnumSmall.copy(fontSize = 9.sp), maxLines = 1)
        Text(text = value, style = tnumValue, maxLines = 1)
        Text(text = unit, style = tnumSmall, maxLines = 1)
    }
}

@Composable
private fun SceneNoteCard(session: SessionRecord, onUpdateNote: (String) -> Unit) {
    var note by remember(session.id) { mutableStateOf(session.note) }
    var editing by remember(session.id) { mutableStateOf(false) }
    SceneCard {
        Row(
            Modifier.fillMaxWidth().clickable { editing = true },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                note.ifBlank { "Note not set, click here to enter" },
                style = tnumBody.copy(color = if (note.isBlank()) Scene.muted else Scene.text)
            )
            Text("Edit", style = tnumSmall, color = Scene.blue)
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Session note") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2_000) },
                    label = { Text("Preset, cooler, ambient temperature...") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateNote(note.trim())
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }
}
