package com.framescope.monitor.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.ui.theme.MonoInline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exact colours used by both the on-screen report and its PDF twin. */
private object Scene {
    val background = Color(0xFF0D0D0F)
    val card = Color(0xFF18191C)
    val border = Color(0xFF26282E)
    val text = Color(0xFFE6E8EC)
    val muted = Color(0xFF8E929B)
    val blue = Color(0xFF3897F0)
    val fps = Color(0xFFE8EAED)
    val temp = Color(0xFFD97736)
    val cpu = Color(0xFFE056FD)
    val gpu = Color(0xFF4FC3F7)
    val frame = Color(0xFF7CC4FA)
    val total = Color(0xFF00E5FF)
    val warnBack = Color(0xFF2A211B)
    val warn = Color(0xFFFF8C42)
    val platform = Color(0xFF7B61FF)
    val host = Color(0xFF4A90E2)
    val os = Color(0xFF3DDC84)
    val profile = Color(0xFFFF6B35)
    val power = Color(0xFF2962FF)
    val capacity = Color(0xFF90CAF9)
    val cpuTemp = Color(0xFF81D4FA)
    val cluster = listOf(
        Color(0xFF9C27B0), Color(0xFF00BFA5), Color(0xFF2979FF), Color(0xFFFF9100)
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

private fun sPositive(values: List<Float>): List<Float> =
    values.mapNotNull { it.finiteOrNull()?.takeIf { v -> v > 0f } }

private fun sPlausible(values: List<Float>): List<Float> =
    values.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }

private fun sChipTemperatures(values: List<Float>): List<Float> =
    values.mapNotNull { it.plausibleTempC(110f) }

private fun sColumns(values: List<List<Float?>>, index: Int): List<Float> =
    values.mapNotNull { it.getOrNull(index)?.finiteOrNull() }

private fun List<Float>.avgOrNull(): Float? =
    if (isEmpty()) null else average().toFloat().finiteOrNull()

private val tnumSmall = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 10.sp, color = Scene.muted
)
private val tnumAxis = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 9.sp, color = Scene.muted
)
private val tnumValue = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 18.sp, fontWeight = FontWeight.Bold,
    color = Scene.blue
)
private val tnumBody = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 12.sp, color = Scene.text
)

/** Cards use a fixed dark surface even when the app theme is light. */
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
private fun SceneNumber(value: String) {
    Text(text = value, style = tnumValue, maxLines = 1)
}

/** One of the eight equal cells of the 2 x 4 KPI grid. */
@Composable
private fun RowScope.KpiCell(label: String, value: String, unit: String) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = tnumSmall.copy(fontSize = 9.sp), maxLines = 1)
        SceneNumber(value)
        Text(text = unit, style = tnumSmall, maxLines = 1)
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
 * Scene-style vector chart. Axis labels are passed top-to-bottom, so the largest
 * value is always drawn at the top and the smallest at the bottom.
 */
@Composable
private fun SceneLineChart(
    lines: List<ChartLineUi>,
    leftLabels: List<String>,
    rightLabels: List<String> = emptyList(),
    height: Dp = 150.dp
) {
    val plotHeight = height - 20.dp
    val leftWidth = 34.dp
    val rightWidth = if (rightLabels.isEmpty()) 6.dp else 38.dp
    Box(Modifier.fillMaxWidth().height(height)) {
        Canvas(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(plotHeight)
                .padding(start = leftWidth, end = rightWidth)
        ) {
            val bottom = size.height
            val lastTick = (leftLabels.size - 1).coerceAtLeast(1)
            leftLabels.forEachIndexed { index, _ ->
                val y = bottom * index / lastTick
                drawLine(Scene.border, Offset(0f, y), Offset(size.width, y), 1f)
            }
            repeat(5) { index ->
                val x = size.width * index / 4f
                drawLine(Scene.border, Offset(x, 0f), Offset(x, bottom), 1f)
            }
            lines.forEach { line ->
                val values = line.values.mapNotNull { it.finiteOrNull() }
                if (values.size < 2) return@forEach
                val span = (line.max - line.min).takeIf { it > 0f } ?: 1f
                val step = size.width / (values.size - 1)
                fun yOf(value: Float): Float =
                    bottom - ((value - line.min) / span).coerceIn(0f, 1f) * bottom
                if (line.spikes) {
                    values.forEachIndexed { index, value ->
                        val x = index * step
                        drawLine(
                            line.color, Offset(x, bottom), Offset(x, yOf(value)),
                            line.strokeWidth
                        )
                    }
                } else {
                    val path = Path().apply {
                        moveTo(0f, yOf(values.first()))
                        values.forEachIndexed { index, value ->
                            if (index > 0) lineTo(index * step, yOf(value))
                        }
                    }
                    drawPath(path, line.color, style = Stroke(width = line.strokeWidth))
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .width(leftWidth)
                .height(plotHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            leftLabels.forEach { Text(text = it, style = tnumAxis, maxLines = 1) }
        }
        if (rightLabels.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(rightWidth)
                    .height(plotHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                rightLabels.forEach { Text(text = it, style = tnumAxis, maxLines = 1) }
            }
        }
    }
}

/** The eleven cards of a Scene-style report. Both UI and PDF draw this same data. */
@Composable
fun SceneSessionCards(
    session: SessionRecord,
    threshold: Int,
    onUpdateNote: (String) -> Unit
) {
    val peakSoc = listOfNotNull(session.maxTempC, session.tempSeries.maxOrNull())
        .mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }
        .maxOrNull()
    val secondsAbove90 = session.tempSeries.count {
        (it.plausibleTempC(MAX_HOTSPOT_TEMP_C) ?: 0f) >= 90f
    }
    var showCpuInOverview by remember(session.id) { mutableStateOf(true) }
    var showGpuInOverview by remember(session.id) { mutableStateOf(true) }

    Column(
        Modifier.fillMaxWidth().background(Scene.background),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1 - heat banner, only for a real hotspot above 85 C.
        if (peakSoc != null && peakSoc >= 85f) {
            Box(
                Modifier.fillMaxWidth().background(Scene.warnBack, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Soc temperature too high MAX:${sfmt(peakSoc, 1)}°C",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        "≥90°C ${secondsAbove90}S",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }

        // Card 2 - device info.
        SceneCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DeviceColumn("Platform", session.device.platform?.uppercase(Locale.US) ?: "--", Scene.platform)
                DeviceColumn("Host", session.device.model ?: "--", Scene.host)
                DeviceColumn(
                    "OS",
                    session.device.osVersion?.substringBefore(" (") ?: "--",
                    Scene.os
                )
                DeviceColumn("Profile", session.device.profile ?: "Balanced", Scene.profile)
            }
        }

        // Card 3 - editable note.
        SceneNoteCard(session, onUpdateNote)

        // Card 4 - game header and the complete 2 x 4 KPI grid.
        SceneCard {
            val timestamp = remember(session.id) {
                SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
            }.format(Date(session.startedAt))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.appLabel.ifBlank { session.appPackage.substringAfterLast('.') },
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
            val chargerTemp = sPlausible(session.tempSeries)
            Row(Modifier.fillMaxWidth()) {
                KpiCell("MAX", sfmt(session.maxFps, 1), "FPS")
                KpiCell("MIN", sfmt(session.minFps, 1), "FPS")
                KpiCell("AVG", sfmt(session.avgFps, 1), "FPS")
                KpiCell("JITTER", sfmt(jitter, 1) + "%", "FPS")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                KpiCell("1% LOW", sfmt(session.low1Percent, 1), "FPS")
                KpiCell(
                    "AVG",
                    sfmt(sPlausible(session.tempSeries).avgOrNull(), 1),
                    "SOC(°C)"
                )
                KpiCell("MAX", sfmt(chargerTemp.maxOrNull(), 1), "Battery(°C)")
                KpiCell("AVG", sfmt(session.powerSeries.avgOrNull(), 2), "Power(W)")
            }
        }

        // Card 5 - FPS and temperature.
        SceneCard {
            Text("FPS & Temperature(°C)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val fpsCeil = (sPositive(session.fpsSeries).maxOrNull() ?: 120f)
                .let { if (it > 130f) 144f else 120f }
            val lines = mutableListOf(
                ChartLineUi(session.fpsSeries, Scene.fps, 0f, fpsCeil),
                ChartLineUi(sChipTemperatures(session.tempSeries), Scene.temp, 40f, 110f)
            )
            if (showCpuInOverview) {
                lines.add(ChartLineUi(sPositive(session.cpuSeries), Scene.cpu, 0f, 100f))
            }
            if (showGpuInOverview) {
                lines.add(ChartLineUi(sPositive(session.gpuPercentSeries), Scene.gpu, 0f, 100f))
            }
            SceneLineChart(
                lines = lines,
                leftLabels = listOf(fpsCeil, fpsCeil * 0.8f, fpsCeil * 0.6f, fpsCeil * 0.4f,
                    fpsCeil * 0.2f, 0f).map { sfmt(it, 0) },
                rightLabels = listOf("110", "88", "66", "44", "22", "0")
            )
            Text(
                "0                                        ${sDuration(session.durationMs)}",
                style = tnumAxis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { showCpuInOverview = !showCpuInOverview }) {
                    Text("CPU(%)", style = tnumSmall, color = Scene.cpu)
                }
                TextButton(onClick = { showGpuInOverview = !showGpuInOverview }) {
                    Text("GPU(%)", style = tnumSmall, color = Scene.gpu)
                }
            }
            Legend("FPS" to Scene.fps, "TEMP" to Scene.temp, "CPU" to Scene.cpu, "GPU" to Scene.gpu)
        }

        // Card 6 - frame time spikes.
        SceneCard {
            Text("Frame Time(ms)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val maxFrame = (sPositive(session.frameTimeSeries).maxOrNull() ?: 100f)
                .coerceAtLeast(100f)
            SceneLineChart(
                lines = listOf(ChartLineUi(session.frameTimeSeries, Scene.frame, 0f, maxFrame,
                    spikes = true, strokeWidth = 1.4f)),
                leftLabels = (4 downTo 0).map { sfmt(maxFrame * it / 4f, 0) }
            )
            Text(
                "MAX: ${sfmt(session.maxFrameTimeMs, 0)}ms",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Card 7 - CPU usage, total plus every detected cluster.
        SceneCard {
            Text("CPU Usage(%)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val groups = session.cpuGroupNames.mapIndexed { index, name ->
                ChartLineUi(
                    sColumns(session.cpuGroupSeries, index),
                    Scene.cluster[index % Scene.cluster.size], 0f, 100f
                )
            }
            val lines = listOf(ChartLineUi(session.cpuSeries, Scene.total, 0f, 100f)) + groups
            SceneLineChart(
                lines = lines,
                leftLabels = listOf("100", "80", "60", "40", "20", "0")
            )
            Legend(*((listOf("Total" to Scene.total) +
                session.cpuGroupNames.mapIndexed { index, name ->
                    name to Scene.cluster[index % Scene.cluster.size]
                }).toTypedArray()))
        }

        // Card 8 - CPU frequency, one line per detected cluster.
        SceneCard {
            Text("CPU Frequency(MHz)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val ceiling = maxOf(
                4000f,
                (session.cpuGroupFreqSeries.flatMap { row -> row.mapNotNull { it?.finiteOrNull() } }
                    .maxOrNull() ?: 0f) * 1.05f
            )
            val groups = session.cpuGroupNames.mapIndexed { index, name ->
                ChartLineUi(
                    sPositive(sColumns(session.cpuGroupFreqSeries, index)),
                    Scene.cluster[index % Scene.cluster.size], 0f, ceiling
                )
            }
            if (groups.isEmpty()) {
                Text("No samples recorded", style = tnumSmall)
            } else {
                SceneLineChart(
                    lines = groups,
                    leftLabels = (4 downTo 0).map { sfmt(ceiling * it / 4f, 0) }
                )
            }
            Legend(*(session.cpuGroupNames.mapIndexed { index, name ->
                name to Scene.cluster[index % Scene.cluster.size]
            }.toTypedArray()))
        }

        // Card 9 - power and capacity, dual axis, with a real chart.
        SceneCard {
            Text("Power(W) & Capacity(%)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val power = sPositive(session.powerSeries)
            val capacity = sPositive(session.batterySeries)
            SceneLineChart(
                lines = listOf(
                    ChartLineUi(power, Scene.power, 0f, 8f),
                    ChartLineUi(capacity, Scene.capacity, 0f, 100f)
                ),
                leftLabels = (8 downTo 0).map { it.toString() },
                rightLabels = (100 downTo 0 step 20).map { it.toString() }
            )
            Legend("Power(W)" to Scene.power, "Capacity(%)" to Scene.capacity)
            Text(
                "MAX: ${sfmt(power.maxOrNull(), 2)}W   " +
                    "MIN: ${sfmt(power.minOrNull(), 2)}W   " +
                    "AVG: ${sfmt(power.avgOrNull(), 2)}W",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Card 10 - real chip temperature with a real chart.
        SceneCard {
            Text("CPU Temperature(°C)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val cpuTemps = sChipTemperatures(
                session.cpuTempSeries.ifEmpty { session.socTempSeries }
            )
            SceneLineChart(
                lines = listOf(ChartLineUi(cpuTemps, Scene.cpuTemp, 0f, 110f, strokeWidth = 1.4f)),
                leftLabels = (110 downTo 0 step 10).map { it.toString() }
            )
            Text(
                "MAX: ${sfmt(cpuTemps.maxOrNull(), 1)}°C   " +
                    "MIN: ${sfmt(cpuTemps.minOrNull(), 1)}°C   " +
                    "AVG: ${sfmt(cpuTemps.avgOrNull(), 1)}°C",
                style = tnumSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Card 11 - device detail and expandable shell logs.
        DeviceDetailCard(session)
    }
}

@Composable
private fun RowScope.DeviceColumn(label: String, value: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = tnumSmall, maxLines = 1)
        Spacer(Modifier.size(6.dp))
        Box(Modifier.size(20.dp).background(color, RoundedCornerShape(6.dp)))
        Spacer(Modifier.size(6.dp))
        Text(text = value, style = tnumBody.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun SceneNoteCard(session: SessionRecord, onUpdateNote: (String) -> Unit) {
    var note by remember(session.id) { mutableStateOf(session.note) }
    var editing by remember(session.id) { mutableStateOf(false) }
    SceneCard(radius = 10) {
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

@Composable
private fun DeviceDetailCard(session: SessionRecord) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    val cpuClock = sPositive(session.cpuFreqSeries)
    val gpuLoad = sPositive(session.gpuPercentSeries)
    val gpuClock = sPositive(session.gpuFreqSeries)
    SceneCard(radius = 10) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Device Detail", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Text(if (expanded) "Hide" else "Expand", style = tnumSmall, color = Scene.blue)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            listOf(
                "CPU CLOCK AVG" to sfmt(cpuClock.avgOrNull(), 0),
                "CPU CLOCK MAX" to sfmt(cpuClock.maxOrNull(), 0),
                "GPU LOAD AVG" to sfmt(gpuLoad.avgOrNull(), 1),
                "GPU LOAD MAX" to sfmt(gpuLoad.maxOrNull(), 1),
                "GPU CLOCK AVG" to sfmt(gpuClock.avgOrNull(), 0),
                "GPU CLOCK MAX" to sfmt(gpuClock.maxOrNull(), 0)
            ).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.first, style = tnumSmall)
                    Text(row.second, style = tnumSmall.copy(color = Scene.text))
                }
            }
            val loads = session.perCoreLoadSeries
            val clocks = session.perCoreFreqSeries
            val cores = maxOf(loads.size, clocks.size)
            if (cores > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Per-core (0-${cores - 1})", style = tnumSmall)
                repeat(cores) { core ->
                    val load = sColumns(loads, core).avgOrNull()
                    val clock = sColumns(clocks.map { row -> row.map { it?.toFloat() } }, core)
                        .avgOrNull()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("CPU $core", style = tnumSmall)
                        Text(
                            "${sfmt(load, 0)}%  ${sfmt(clock, 0)} MHz",
                            style = tnumSmall.copy(color = Scene.text)
                        )
                    }
                }
            }
            if (session.threadNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Top threads", style = tnumSmall)
                session.threadNames.take(6).forEachIndexed { index, name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = tnumSmall, maxLines = 1)
                        Text(
                            sfmt(session.threadAvgLoads.getOrElse(index) { 0f }, 1) + "%",
                            style = tnumSmall.copy(color = Scene.text)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Shell Logs", style = tnumSmall, color = Scene.muted)
            Text(
                session.shellLogs.ifBlank { "--" },
                style = tnumSmall.copy(fontSize = 9.sp),
                maxLines = 12
            )
        }
    }
}
