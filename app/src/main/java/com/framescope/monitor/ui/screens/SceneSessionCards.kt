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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.ui.components.TelemetryChart
import com.framescope.monitor.ui.components.TelemetrySeries
import com.framescope.monitor.ui.components.TimeAxis
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
    val dim = Color(0xFF6E737D)
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

private fun sBeforeAfter(values: List<Float>, decimals: Int, unit: String): String {
    val clean = values.mapNotNull { it.finiteOrNull() }
    if (clean.isEmpty()) return "--"
    val first = sfmt(clean.first(), decimals) + unit
    val last = sfmt(clean.last(), decimals) + unit
    return if (clean.size == 1) first else "$first  →  $last"
}

private fun sPositive(values: List<Float>): List<Float> =
    values.mapNotNull { it.finiteOrNull()?.takeIf { v -> v > 0f } }

private fun sPlausible(values: List<Float>): List<Float> =
    values.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }

private fun sColumns(values: List<List<Float?>>, index: Int): List<Float> =
    values.mapNotNull { it.getOrNull(index)?.finiteOrNull() }

private val tnumSmall = MonoInline.copy(
    fontFeatureSettings = "tnum", fontSize = 10.sp, color = Scene.muted
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
        Column(
            Modifier.padding(12.dp),
            content = content
        )
    }
}

@Composable
private fun SceneNumber(value: String, unit: String? = null, color: Color = Scene.blue) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = tnumValue.copy(color = color), maxLines = 1)
        if (!unit.isNullOrBlank()) {
            Text(
                text = unit,
                style = tnumSmall,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)
            )
        }
    }
}

@Composable
private fun KpiCell(label: String, value: String, unit: String) {
    Column(
        Modifier.fillMaxWidth(),
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
        entries.forEachIndexed { index, (label, color) ->
            if (index > 0) Spacer(Modifier.size(10.dp))
            Box(Modifier.size(7.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.size(4.dp))
            Text(text = label, style = tnumSmall.copy(fontSize = 10.sp))
        }
    }
}

/** The five allowed graphs share one axis treatment and 10 dp card spacing. */
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
        Modifier
            .fillMaxWidth()
            .background(Scene.background),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1 - heat banner, only for a real hotspot above 85 °C.
        if (peakSoc != null && peakSoc >= 85f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Scene.warnBack, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Soc temperature too high MAX:${sfmt(peakSoc, 1)}°C",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "≥90°C ${secondsAbove90}S",
                        style = tnumBody.copy(color = Scene.warn, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }

        // Card 2 - device and environment.
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

        // Card 4 - game header and 2 x 4 KPI grid.
        SceneCard(radius = 12) {
            val timestamp = remember(session.id) {
                SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())
            }.format(Date(session.startedAt))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.appLabel.ifBlank {
                            session.appPackage.substringAfterLast('.')
                        },
                        style = tnumBody.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = "Long: ${sDuration(session.durationMs)}",
                        style = tnumSmall
                    )
                }
                Text(text = timestamp, style = tnumSmall, maxLines = 2)
            }
            Spacer(Modifier.height(14.dp))
            val jitterPct = session.jitterMs?.let { jitter ->
                session.avgFrameTimeMs.takeIf { it > 0f }?.let { jitter / it * 100f }
            }
            val battery = sPlausible(session.tempSeries)
            Row(Modifier.fillMaxWidth()) {
                KpiCell("MAX", sfmt(session.maxFps, 1), "FPS")
                KpiCell("MIN", sfmt(session.minFps, 1), "FPS")
                KpiCell("AVG", sfmt(session.avgFps, 1), "FPS")
                KpiCell("JITTER", sfmt(jitterPct, 1), "FPS")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                KpiCell("1% LOW", sfmt(session.low1Percent, 1), "FPS")
                KpiCell("AVG", sfmt(session.tempSeries.mapNotNull {
                    it.plausibleTempC(MAX_HOTSPOT_TEMP_C)
                }.averageOrNull(), 1), "SOC(°C)")
                KpiCell("MAX", sfmt(battery.maxOrNull(), 1), "Battery(°C)")
                KpiCell("AVG", sfmt(session.powerSeries.averageOrNull(), 2), "Power(W)")
            }
        }

        // Card 5 - FPS and temperature, with optional CPU/GPU overlays.
        SceneCard {
            Text("FPS & Temperature(°C)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val fpsCeil = (sPositive(session.fpsSeries).maxOrNull() ?: 120f)
                .let { if (it > 130f) 144f else if (it > 100f) 120f else 120f }
            val lines = mutableListOf(
                TelemetrySeries("FPS", session.fpsSeries, Scene.fps, "fps", 0f, fpsCeil),
                TelemetrySeries(
                    "TEMP(°C)", sPlausible(session.tempSeries), Scene.temp, "°C", 40f, 90f
                )
            )
            if (showCpuInOverview) {
                lines.add(TelemetrySeries("CPU(%)", sPositive(session.cpuSeries), Scene.cpu, "%", 0f, 100f))
            }
            if (showGpuInOverview) {
                lines.add(
                    TelemetrySeries(
                        "GPU(%)", sPositive(session.gpuPercentSeries), Scene.gpu, "%", 0f, 100f
                    )
                )
            }
            TelemetryChart(lines, height = 160.dp)
            TimeAxis(session.durationMs, session.fpsSeries.size)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { showCpuInOverview = !showCpuInOverview }) {
                    Text("CPU(%)", style = tnumSmall, color = Scene.cpu)
                }
                TextButton(onClick = { showGpuInOverview = !showGpuInOverview }) {
                    Text("GPU(%)", style = tnumSmall, color = Scene.gpu)
                }
            }
            Legend("FPS" to Scene.fps, "TEMP(°C)" to Scene.temp, "CPU(%)" to Scene.cpu, "GPU(%)" to Scene.gpu)
        }

        // Card 6 - frame time spikes.
        SceneCard {
            Text("Frame Time(ms)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            SpikeChart(session.frameTimeSeries)
            TimeAxis(session.durationMs, session.frameTimeSeries.size)
            Text(
                text = "MAX: ${sfmt(session.maxFrameTimeMs, 0)}ms",
                style = tnumSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // Card 7 - CPU usage, total plus every detected cluster.
        SceneCard {
            Text("CPU Usage(%)", style = tnumBody.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            val groups = session.cpuGroupNames.mapIndexed { index, name ->
                TelemetrySeries(
                    name, sColumns(session.cpuGroupSeries, index),
                    Scene.cluster[index % Scene.cluster.size], "%", 0f, 100f
                )
            }
            val lines = listOf(
                TelemetrySeries("Total", session.cpuSeries, Scene.total, "%", 0f, 100f)
            ) + groups
            TelemetryChart(lines, height = 160.dp)
            TimeAxis(session.durationMs, session.cpuSeries.size)
            Legend(
                *((listOf("Total" to Scene.total) + groups.map {
                    it.label to it.color
                }).toTypedArray())
            )
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
                TelemetrySeries(
                    name,
                    sPositive(sColumns(session.cpuGroupFreqSeries, index)),
                    Scene.cluster[index % Scene.cluster.size], "MHz", 400f, ceiling
                )
            }
            if (groups.isEmpty()) {
                Text("No samples recorded", style = tnumSmall)
            } else {
                TelemetryChart(groups, height = 160.dp)
            }
            TimeAxis(
                session.durationMs,
                session.cpuGroupFreqSeries.maxOfOrNull { it.size } ?: 0
            )
            Legend(*(groups.map { it.label to it.color }.toTypedArray()))
        }

        // Card 9 - power is text only, no graph.
        BeforeAfterSummaryCard(
            title = "Power(W) & Capacity(%)",
            rows = listOf(
                "Power" to sBeforeAfter(session.powerSeries.filter { it >= 0f }, 2, "W"),
                "Capacity" to sBeforeAfter(session.batterySeries, 0, "%")
            )
        )

        // Card 10 - CPU temperature is text only, no graph.
        BeforeAfterSummaryCard(
            title = "CPU Temperature(°C)",
            rows = listOf(
                "CPU temperature" to sBeforeAfter(
                    sPlausible(session.cpuTempSeries.ifEmpty { session.socTempSeries }), 1, "°C"
                )
            )
        )

        // Card 11 - device detail and expandable shell logs.
        DeviceDetailCard(session)
    }
}

@Composable
private fun RowScope.DeviceColumn(label: String, value: String, color: Color) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = tnumSmall, maxLines = 1)
        Spacer(Modifier.size(6.dp))
        Box(Modifier.size(20.dp).background(color, RoundedCornerShape(6.dp)))
        Spacer(Modifier.size(6.dp))
        Text(
            text = value,
            style = tnumBody.copy(fontWeight = FontWeight.Bold),
            maxLines = 1
        )
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
                text = note.ifBlank { "Note not set, click here to enter" },
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
private fun SpikeChart(values: List<Float>) {
    val clean = sPositive(values)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val max = (clean.maxOrNull() ?: 100f).coerceAtLeast(100f)
        for (i in 0..10) {
            val y = size.height * i / 10f
            drawLine(Scene.border, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (clean.size < 2) return@Canvas
        val step = size.width / (clean.size - 1)
        clean.forEachIndexed { index, value ->
            val x = index * step
            val top = size.height * (1f - (value / max).coerceIn(0f, 1f))
            drawLine(Scene.frame, Offset(x, size.height), Offset(x, top), 2f)
        }
    }
}

@Composable
private fun BeforeAfterSummaryCard(title: String, rows: List<Pair<String, String>>) {
    SceneCard {
        Text(text = title, style = tnumBody.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        rows.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = tnumSmall)
                Text(value, style = tnumSmall.copy(color = Scene.text, fontWeight = FontWeight.Medium))
            }
            Spacer(Modifier.height(4.dp))
        }
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
                "CPU CLOCK AVG" to sfmt(cpuClock.averageOrNull(), 0),
                "CPU CLOCK MAX" to sfmt(cpuClock.maxOrNull(), 0),
                "GPU LOAD AVG" to sfmt(gpuLoad.averageOrNull(), 1),
                "GPU LOAD MAX" to sfmt(gpuLoad.maxOrNull(), 1),
                "GPU CLOCK AVG" to sfmt(gpuClock.averageOrNull(), 0),
                "GPU CLOCK MAX" to sfmt(gpuClock.maxOrNull(), 0)
            ).forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = tnumSmall)
                    Text(value, style = tnumSmall.copy(color = Scene.text))
                }
            }
            val loads = session.perCoreLoadSeries
            val clocks = session.perCoreFreqSeries
            val cores = maxOf(loads.size, clocks.size)
            if (cores > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Per-core (0-${cores - 1})", style = tnumSmall)
                repeat(cores) { core ->
                    val load = sColumns(loads, core).averageOrNull()
                    val clock = sColumns(clocks.map { row -> row.map { it?.toFloat() } }, core).averageOrNull()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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
                session.threadNames.takeFirst(6).forEachIndexed { index, name ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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
                text = session.shellLogs.ifBlank { "--" },
                style = tnumSmall.copy(fontSize = 9.sp),
                maxLines = 12
            )
        }
    }
}

private fun <T> List<T>.takeFirst(n: Int): List<T> = take(n)

private fun List<Float>.averageOrNull(): Float? =
    if (isEmpty()) null else average().toFloat().finiteOrNull()
