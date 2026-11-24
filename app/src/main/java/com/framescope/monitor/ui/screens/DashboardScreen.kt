package com.framescope.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.framescope.monitor.ui.Diagnostics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.BuildConfig
import com.framescope.monitor.core.CrashLog
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.thermalStatusLabel
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.BarMeter
import com.framescope.monitor.ui.components.FpsGauge
import com.framescope.monitor.ui.components.InfoRow
import com.framescope.monitor.ui.components.MetricLine
import com.framescope.monitor.ui.components.MiniStat
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.StatCell
import com.framescope.monitor.ui.components.StatusPill
import com.framescope.monitor.ui.components.TerminalBlock
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.components.rememberPulseAlpha
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.metric
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    bottomPadding: Dp = 24.dp,
    onRequestScreenRecord: () -> Unit = {}
) {
    val fps by container.engine.fps.collectAsState()
    val system by container.engine.system.collectAsState()
    val live by container.engine.live.collectAsState()
    val recording by container.engine.recording.collectAsState()
    val foreground by container.engine.foregroundApp.collectAsState()
    val status by container.hub.status.collectAsState()
    val diagnostic by container.engine.diagnostic.collectAsState()
    val liveSeries by container.engine.liveSeries.collectAsState()
    val liveHistory by container.engine.liveHistory.collectAsState()
    var customising by remember { mutableStateOf(false) }
    val cardKeys = container.settings.dashboardCards.flow.collectAsState().value
        .let { DashboardMetric.keysOf(it) }
    val graphKeys = container.settings.dashboardGraphs.flow.collectAsState().value
        .let { DashboardMetric.keysOf(it) }
    val scope = rememberCoroutineScope()
    val layer by container.engine.measuredLayer.collectAsState()
    val threshold = container.settings.fpsThreshold.flow.collectAsState().value
    val context = LocalContext.current
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { crash = CrashLog.last(context) }
    val measuring by container.settings.measurementEnabled.flow.collectAsState()
    val setupDone by container.settings.setupCompleted.flow.collectAsState()

    val accessColor = if (status.state == AccessState.READY) {
        MaterialTheme.status.good
    } else {
        MaterialTheme.status.warn
    }

    FrameScopeScreen(
        title = "Monitor",
        subtitle = foreground?.let { "Monitoring $it" } ?: "Waiting for a foreground app",
        bottomPadding = bottomPadding,
        actions = {
            StatusPill(
                text = when {
                    status.commandVerified -> "Verified"
                    !status.permissionGranted -> "Permission needed"
                    !status.binderAvailable -> "Shizuku stopped"
                    status.state == AccessState.UNAVAILABLE -> "Connection error"
                    status.userServiceConnected -> "Verifying"
                    else -> "Connecting"
                },
                color = accessColor,
                modifier = Modifier.padding(end = Space.lg)
            )
        }
    ) {
        if (crash != null) {
            item { CrashPanel(crash.orEmpty(), context) }
        }

        if (status.state != AccessState.READY) {
            // Shizuku only gates the readings that genuinely need a privileged shell.
            // The native ones - battery, memory, refresh rate - keep working, so this
            // is a note about what is missing rather than a blocker for everything.
            item {
                AccessBanner(
                    title = if (setupDone) "Frame timing needs Shizuku" else "Privileged access needed",
                    detail = if (setupDone) {
                        status.detail.ifBlank {
                            "Shizuku is not answering, so the frame rate and the CPU and GPU " +
                                "readings are paused. Battery, memory and refresh rate keep working."
                        }
                    } else {
                        status.detail.ifBlank {
                            "Run the one-time setup to unlock the frame rate. Battery, memory " +
                                "and refresh rate work without it."
                        }
                    },
                    onOpenSettings = onOpenSettings
                )
            }
        } else if (!fps.valid && !diagnostic.isNullOrBlank()) {
            item {
                AccessBanner(
                    title = "No frame data yet",
                    detail = diagnostic.orEmpty(),
                    onOpenSettings = onOpenSettings
                )
            }
        }

        // The reading comes first. Everything below it explains the reading.
        item {
            HeroPanel(
                fps = if (measuring) fps else FpsState(),
                layer = if (measuring) layer else null,
                threshold = threshold,
                system = system
            )
        }

        item {
            MeasurementPanel(
                measuring = measuring,
                onToggle = { container.settings.measurementEnabled.value = !measuring }
            )
        }

        if (foreground == BuildConfig.APPLICATION_ID) {
            item {
                SelfMeasureNotice()
            }
        }

        item {
            SectionHeader("Live metrics") {
                TextButton(
                    onClick = { customising = !customising },
                    modifier = Modifier.padding(end = Space.sm)
                ) {
                    Text(if (customising) "Done" else "Customise")
                }
            }
        }

        if (customising) {
            item {
                Panel {
                    Text(
                        text = "Choose what the dashboard shows",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.sm))
                    DashboardCustomisation(
                        cards = cardKeys,
                        graphs = graphKeys,
                        onCardsChange = { keys ->
                            container.settings.dashboardCards.value =
                                DashboardMetric.cards.filter { it.key in keys }.joinToString(",") { it.key }
                        },
                        onGraphsChange = { keys ->
                            container.settings.dashboardGraphs.value =
                                DashboardMetric.graphs.filter { it.key in keys }.joinToString(",") { it.key }
                        }
                    )
                }
            }
        }

        item {
            LiveMetricCards(
                fps = if (measuring) fps else FpsState(),
                system = system,
                history = liveHistory,
                threshold = threshold,
                metrics = DashboardMetric.fromKeys(cardKeys, DashboardMetric.cards)
            )
        }

        // The readings the grid above does not carry. They used to live in a
        // second grid of their own, which showed the CPU, the temperature, the
        // memory and the power a second time.
        item { DeviceExtrasPanel(system) }

        // A graph without readings is left out, and so is the title of the
        // section: an empty section says less than no section.
        val chosenGraphs = DashboardMetric.fromKeys(graphKeys, DashboardMetric.graphs)
            .filter { livePointsOf(it, liveHistory).size >= 2 }
        if (chosenGraphs.isNotEmpty()) {
            item { SectionHeader("Live graphs") }

            item {
                LiveGraphs(
                    fps = if (measuring) fps else FpsState(),
                    system = system,
                    history = liveHistory,
                    threshold = threshold,
                    metrics = chosenGraphs
                )
            }
        }

        item { SectionHeader("Frame rate history") }

        item { HistoryPanel(fps = fps, series = liveSeries, layer = layer, threshold = threshold) }

        item { SectionHeader("Session") }

        item {
            SessionPanel(
                recording = recording,
                avgFps = live.avgFps,
                maxFps = live.maxFps,
                minFps = live.minFps,
                low1 = live.low1Percent,
                low5 = live.low5Percent,
                smoothness = live.smoothness,
                totalFrames = live.totalFrames,
                jankFrames = live.jankFrames,
                durationMs = live.durationMs,
                maxTempC = live.maxTempC,
                avgCpu = live.avgCpu,
                peakThermalStatus = live.peakThermalStatus,
                throttledSeconds = live.throttledSeconds,
                threshold = threshold,
                onToggleRecording = {
                    if (recording) {
                        container.engine.stopRecordingManually()
                        val stopIntent = android.content.Intent(context, com.framescope.monitor.recorder.ScreenRecorderService::class.java).apply {
                            action = com.framescope.monitor.recorder.ScreenRecorderService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } else {
                        onRequestScreenRecord()
                    }
                }
            )
        }

        item { SectionHeader("Device") }

        item { DevicePanel(system) }

        item { DiagnosticsRow(container = container) }
    }
}

@Composable
private fun AccessBanner(title: String, detail: String, onOpenSettings: () -> Unit) {
    val warn = MaterialTheme.status.warn
    Panel(
        containerColor = MaterialTheme.status.warnContainer,
        borderColor = MaterialTheme.status.warnContainer,
        contentColor = MaterialTheme.status.onWarnContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = warn,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.padding(start = Space.md).weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.status.onWarnContainer
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.status.onWarnContainer.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(Space.sm))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = warn,
                    contentColor = MaterialTheme.status.onWarn
                )
            ) {
                Text("Set up")
            }
        }
    }
}

@Composable
private fun HeroPanel(
    fps: FpsState,
    layer: String?,
    threshold: Int,
    system: SystemMetrics
) {
    val stateColor = frameRateStatusColor(fps, threshold)
    val ceiling = maxOf(threshold * 1.6f, 90f)
    val stateText = frameRateStatus(fps, threshold)
    val palette = MaterialTheme.metric

    Panel(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        BoxWithConstraints {
            // The gauge and the numbers only sit side by side when there is room for both.
            // On a narrow phone the reading stays square and the numbers go under it.
            val stacked = maxWidth < 320.dp

            val details: @Composable ColumnScope.() -> Unit = {
                // The state of the reading, in words. The gauge already carries the
                // colour, so this chip is only the name of the state.
                StatusPill(
                    text = stateText,
                    color = stateColor,
                    textColor = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Space.md))

                // The numbers of the reading, each on its own scale. The frame rate
                // of the panel, the frame rate the game asks for and the frame rate
                // that was measured are three different things and are told apart.
                Row(Modifier.fillMaxWidth()) {
                    MiniStat(
                        // The smoothness floor of the app, not the rate of the
                        // panel. The panel is told apart further down.
                        label = "Smooth floor",
                        value = "$threshold",
                        unit = "fps",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStat(
                        label = "Cap",
                        value = fps.detectedCapFps?.toString() ?: "--",
                        unit = "fps",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStat(
                        label = "Frame time",
                        value = if (fps.valid) String.format("%.1f", fps.frameTimeMs) else "--",
                        unit = "ms",
                        color = palette.frameTime,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth()) {
                    MiniStat(
                        label = "1% low",
                        value = fps.low1PercentFps?.let { String.format("%.0f", it) } ?: "--",
                        unit = "fps",
                        color = palette.fps,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStat(
                        label = "5% low",
                        value = fps.low5PercentFps?.let { String.format("%.0f", it) } ?: "--",
                        unit = "fps",
                        color = palette.fps,
                        modifier = Modifier.weight(1f)
                    )
                    MiniStat(
                        // The rate of the panel itself. It is not the target of
                        // the game, and saying so keeps the two apart.
                        label = "Display",
                        value = system.refreshHz?.let { "$it" } ?: "--",
                        unit = "Hz",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(Space.sm))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(Space.sm))

                // Where the number comes from and how old it is. A frame rate
                // without these cannot be judged.
                InfoRow(
                    label = "Source",
                    value = if (fps.valid) fps.source else "--",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InfoRow(
                    label = "Sample age",
                    value = if (fps.valid) "${fps.sampleAgeMs} ms" else "--",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InfoRow(
                    label = "Measured over",
                    value = if (fps.valid) "${fps.measurementWindowMs} ms" else "--",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!fps.capCause.isNullOrBlank()) {
                    InfoRow(
                        label = "Cap cause",
                        value = fps.capCause,
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!fps.valid && !fps.reason.isNullOrBlank()) {
                    Text(
                        text = fps.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.xs)
                    )
                }
                if (layer != null && fps.source.startsWith("SurfaceFlinger")) {
                    Text(
                        text = layer,
                        style = MonoInline.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (stacked) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FpsGauge(
                        value = fps.fps,
                        target = threshold,
                        maxValue = ceiling,
                        diameter = 184.dp
                    )
                    Spacer(Modifier.height(Space.md))
                    Column(Modifier.fillMaxWidth()) { details() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FpsGauge(
                        value = fps.fps,
                        target = threshold,
                        maxValue = ceiling,
                        diameter = 152.dp
                    )
                    Spacer(Modifier.width(Space.lg))
                    Column(Modifier.weight(1f)) { details() }
                }
            }
        }
    }
}

/**
 * FrameScope measures the app in front of it. When that app is FrameScope the
 * numbers describe the monitor itself, not the game.
 */
@Composable
private fun SelfMeasureNotice() {
    Panel(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        Text(
            text = "Measuring FrameScope itself",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Open the app or game you want to measure. The overlay keeps the numbers " +
                "visible while you play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * The master switch of the measurement.
 *
 * With it off nothing is sampled and no overlay is drawn, so the state of the
 * app is visible at a glance instead of having to be guessed from a stale
 * reading.
 */
@Composable
private fun MeasurementPanel(measuring: Boolean, onToggle: () -> Unit) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (measuring) "Measurement is running" else "Measurement is off",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (measuring) {
                        "The frame rate is sampled and the overlay follows."
                    } else {
                        "Nothing is sampled and the overlay is hidden."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(Space.md))
            if (measuring) {
                FilledTonalButton(
                    onClick = onToggle,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.status.criticalContainer,
                        contentColor = MaterialTheme.status.onCriticalContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Pause", modifier = Modifier.padding(start = 6.dp))
                }
            } else {
                FilledTonalButton(onClick = onToggle) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Resume", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/**
 * The last crash, if the app died on the previous run.
 *
 * Without this the only sign of a crash is an app that does not open.
 */
@Composable
private fun CrashPanel(text: String, context: Context) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Panel {
        Text(
            text = "The app crashed on the last run",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.status.critical
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            OutlinedButton(onClick = { Diagnostics.copy(context, text) }) {
                Text("Copy")
            }
            OutlinedButton(
                onClick = {
                    CrashLog.clear(context)
                    dismissed = true
                }
            ) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * What the last sample attempt saw, layer by layer.
 *
 * When a reading is wrong this is the evidence: which layers the app owned,
 * how many frames each returned and why one was kept and the others dropped.
 */
/** Makes copied evidence harmless if it is pasted into a shell by mistake. */
private fun shellSafeText(text: String): String =
    text.lines().joinToString("\n") { line -> if (line.isBlank()) "#" else "# $line" }

/**
 * One button that copies the evidence for a wrong reading.
 *
 * The output goes to the clipboard so it can be pasted straight into a chat,
 * which is far easier than reading it off a phone screen.
 */
@Composable
private fun DiagnosticsRow(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by container.hub.status.collectAsState()
    val lastMeasured by container.engine.lastMeasuredPackage.collectAsState()
    val layer by container.engine.measuredLayer.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var probeOutput by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    // The raw output is folded away until it is asked for.
    var showRawOutput by remember { mutableStateOf(false) }

    Panel {
        Text(
            text = "Wrong numbers? Copy the diagnostics and send them over. It shows exactly " +
                "what FrameScope saw.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            FilledTonalButton(
                enabled = !probing,
                onClick = {
                    probing = true
                    probeOutput = "Reading the layers..."
                    scope.launch {
                        val pkg = lastMeasured
                        val result = container.hub.execOrNull(
                            Diagnostics.probeCommand(pkg, layer, container.engine.debugFrameLogText()),
                            20_000
                        )
                        probeOutput = if (result == null) {
                            "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
                        } else {
                            (result.out + "\n" + result.err).trim()
                        }
                        probing = false
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    if (lastMeasured.isNullOrBlank()) "Raw probe" else "Raw probe (${lastMeasured})",
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            FilledTonalButton(
                enabled = !running,
                onClick = {
                    running = true
                    message = null
                    scope.launch {
                        // One button means one clipboard: the report and the raw
                        // probe for the last measured game are copied together.
                        val pkg = lastMeasured
                        val probe = container.hub.execOrNull(
                            Diagnostics.probeCommand(
                                pkg,
                                layer,
                                container.engine.debugFrameLogText()
                            ),
                            20_000
                        )
                        val probeText = probe?.let { (it.out + "\n" + it.err).trim() }
                            ?.takeIf { it.isNotEmpty() }
                            ?: "The raw probe could not run."
                        probeOutput = probeText
                        val result = container.hub.execOrNull(Diagnostics.command, 20_000)
                        val shell = result?.let { (it.out + "\n" + it.err).trim() }
                            ?.takeIf { it.isNotEmpty() }
                            ?: "Shizuku is unavailable. Start it and complete setup first."
                        val report = com.framescope.monitor.metrics.DiagnosticsReport.build(
                            context = context,
                            hub = container.hub,
                            engine = container.engine,
                            settings = container.settings,
                            shellOutput = shell
                        )
                        val text = report + "\n\n--- raw probe for " +
                            (pkg ?: "the last app") + " ---\n" + probeText
                        Diagnostics.copy(context, shellSafeText(text))
                        message = "Copied report + raw probe - paste it in the chat."
                        running = false
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text("Copy all", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (message != null) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
        // The probe runs by itself when the screen opens so the answer is ready to
        // read, but it never touches the clipboard. Copy all already includes the
        // raw probe, and an automatic copy here would overwrite the full report the
        // user just copied as soon as this screen opens again.
        LaunchedEffect(Unit) {
            val pkg = lastMeasured
            val result = container.hub.execOrNull(
                Diagnostics.probeCommand(pkg, layer, container.engine.debugFrameLogText()),
                20_000
            )
            probeOutput = if (result == null) {
                "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
            } else {
                (result.out + "\n" + result.err).trim()
            }
        }

        if (probeOutput.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            // The raw answer of the device is forty lines of terminal output.
            // It is the evidence for a wrong reading, so it belongs to the
            // diagnostics - but it is not read on the screen, and it is folded
            // away until it is asked for.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRawOutput = !showRawOutput },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showRawOutput) {
                        "Hide raw SurfaceFlinger output"
                    } else {
                        "Show raw SurfaceFlinger output"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showRawOutput) {
                        Icons.Rounded.ExpandLess
                    } else {
                        Icons.Rounded.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (showRawOutput) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "What SurfaceFlinger answers for " +
                        (lastMeasured ?: "the last app") + " - a screenshot of this is enough to fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Space.sm))
                // The same marker is printed by the probe, but it is easy to miss in
                // a long terminal block. Showing it here means the capture is always
                // identifiable by eye, even before the text is copied anywhere.
                Text(
                    text = "captured_from=${lastMeasured ?: "none"} at=" +
                        (container.engine.debugFrameLog().lastOrNull()?.first?.toString() ?: "0"),
                    style = MonoInline.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Space.xs))
                TerminalBlock(text = probeOutput, maxLines = 40)
            }
        }
    }
}

/**
 * The readings the telemetry grid does not carry.
 *
 * The grid above shows the CPU, the GPU, the temperature, the memory and the
 * power. What is left - the clock of the GPU, the battery, the thermal state
 * and how far the phone has cooled - belongs here, so no number is shown
 * twice on the same screen.
 */
@Composable
private fun DeviceExtrasPanel(system: SystemMetrics) {
    val temp = system.deviceTemperatureC()
    val tempColor = when {
        temp == null -> MaterialTheme.colorScheme.onSurface
        temp >= 45 -> MaterialTheme.status.critical
        temp >= 38 -> MaterialTheme.status.warn
        else -> MaterialTheme.status.good
    }

    Panel {
        Row {
            StatCell(
                label = "GPU clock",
                value = system.gpuFreqMHz?.toString() ?: "--",
                unit = system.gpuFreqMHz?.let { "MHz" } ?: "",
                accent = MaterialTheme.colorScheme.onSurface,
                availability = system.availabilityOf(SystemMetric.GPU_FREQ_MHZ),
                modifier = Modifier.weight(1f).padding(end = Space.sm)
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatCell(
                label = "Battery",
                value = system.batteryLevel?.toString() ?: "--",
                unit = "%",
                accent = MaterialTheme.status.good,
                meter = system.batteryLevel?.let { it / 100f },
                availability = system.availabilityOf(SystemMetric.BATTERY_LEVEL),
                modifier = Modifier.weight(1f).padding(start = Space.sm)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = Space.md),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        system.thermalStatus?.let { status ->
            MetricLine(
                label = "Thermal state",
                value = thermalStatusLabel(status),
                accent = if (status > 0) MaterialTheme.status.warn else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MetricLine(
            label = "Temperature now",
            value = temp?.let { String.format("%.1f°C", it) } ?: "--",
            accent = tempColor
        )
        MetricLine(
            label = "Peak since the run",
            value = system.tempPeakC?.let { String.format("%.1f°C", it) } ?: "--",
            accent = MaterialTheme.status.warn
        )
        MetricLine(
            label = "Cooled from peak",
            value = system.tempCooledC?.let { String.format("%.1f°C", it) } ?: "--",
            accent = if ((system.tempCooledC ?: 0f) >= 0.1f) {
                MaterialTheme.status.good
            } else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryPanel(fps: FpsState, series: List<Float>, layer: String?, threshold: Int) {
    val history = series.ifEmpty { fps.history }.filter { it.finiteOrNull() != null }
    val stateColor = fpsStateColor(fps.fps, threshold)
    val min = history.minOrNull()
    val avg = if (history.isEmpty()) null else history.average().toFloat().finiteOrNull()
    val max = history.maxOrNull()

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${history.size} seconds",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Smoothness floor: $threshold fps",
                style = MonoInline,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Sparkline(
            values = history,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(top = Space.md),
            color = stateColor,
            target = threshold.toFloat()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.sm),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HistoryStat("Min", min?.let { String.format("%.0f", it) } ?: "--")
            HistoryStat("Average", avg?.let { String.format("%.0f", it) } ?: "--")
            HistoryStat("Max", max?.let { String.format("%.0f", it) } ?: "--")
        }
    }
}

@Composable
private fun HistoryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoInline,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SessionPanel(
    recording: Boolean,
    avgFps: Float,
    maxFps: Float,
    minFps: Float,
    low1: Float,
    low5: Float,
    smoothness: Float,
    totalFrames: Int,
    jankFrames: Int,
    durationMs: Long,
    maxTempC: Float?,
    avgCpu: Float?,
    peakThermalStatus: Int?,
    throttledSeconds: Int,
    threshold: Int,
    onToggleRecording: () -> Unit
) {
    val dotAlpha = rememberPulseAlpha(durationMs = 900)

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.status.critical.copy(alpha = dotAlpha))
                    )
                    Spacer(Modifier.width(Space.sm))
                }
                Text(
                    text = if (recording) "Recording" else "Ready to record",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (recording) {
                FilledTonalButton(
                    onClick = onToggleRecording,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.status.criticalContainer,
                        contentColor = MaterialTheme.status.onCriticalContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Stop", modifier = Modifier.padding(start = 6.dp))
                }
            } else {
                FilledTonalButton(onClick = onToggleRecording) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("Record", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (totalFrames == 0) {
            Text(
                text = "Averages, lows and jank appear once the recording has samples.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.md)
            )
        } else {
            Row(Modifier.padding(top = Space.sm)) {
            StatCell(
                label = "Average",
                value = String.format("%.1f", avgFps),
                unit = "fps",
                accent = MaterialTheme.status.good,
                modifier = Modifier.weight(1f).padding(end = Space.sm)
            )
            StatCell(
                label = "Max / min",
                value = "${maxFps.roundToInt()} / ${minFps.roundToInt()}",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
            )
            StatCell(
                label = "Smooth",
                value = smoothness.roundToInt().toString(),
                unit = "%",
                accent = MaterialTheme.status.good,
                modifier = Modifier.weight(1f).padding(start = Space.sm)
            )
        }
            Row(Modifier.padding(top = Space.md)) {
                StatCell(
                    label = "1% low",
                    value = String.format("%.1f", low1),
                    accent = MaterialTheme.status.warn,
                    modifier = Modifier.weight(1f).padding(end = Space.sm)
                )
                StatCell(
                    label = "5% low",
                    value = String.format("%.1f", low5),
                    accent = MaterialTheme.status.warn,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                )
                StatCell(
                    label = "Jank",
                    value = "$jankFrames / $totalFrames",
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = Space.sm)
                )
            }
        }

        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        MetricLine(
            label = "Duration",
            value = String.format("%d:%02d", durationMs / 60000, (durationMs / 1000) % 60),
            accent = MaterialTheme.colorScheme.onSurface
        )
        if (maxTempC != null) {
            MetricLine(
                label = "Peak temperature",
                value = "${maxTempC.roundToInt()}°C",
                accent = MaterialTheme.status.warn
            )
        }
        if (peakThermalStatus != null) {
            MetricLine(
                label = "Thermal state",
                value = thermalStatusLabel(peakThermalStatus),
                accent = if (peakThermalStatus > 0) {
                    MaterialTheme.status.warn
                } else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (throttledSeconds > 0) {
            MetricLine(
                label = "Throttled time",
                value = String.format("%d:%02d", throttledSeconds / 60, throttledSeconds % 60),
                accent = MaterialTheme.status.warn
            )
        }
        if (avgCpu != null) {
            MetricLine(
                label = "Average CPU",
                value = "${avgCpu.roundToInt()}%",
                accent = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MetricLine(
            label = "Smoothness target",
            value = "$threshold fps",
            accent = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DevicePanel(system: SystemMetrics) {
    Panel {
        MetricLine(
            label = "GPU",
            value = system.gpuName?.take(34) ?: "--",
            accent = MaterialTheme.colorScheme.onSurface
        )
        MetricLine(
            label = "Refresh rate",
            value = system.refreshHz?.let { "${it.roundToInt()} Hz" } ?: "--",
            accent = MaterialTheme.status.good
        )
        MetricLine(
            label = "CPU clock",
            value = system.cpuFreqMHz?.let { "$it MHz" } ?: "--",
            accent = MaterialTheme.colorScheme.onSurface
        )
        MetricLine(
            label = "Memory",
            value = system.ramTotalGb?.let { total ->
                "${String.format("%.1f", system.ramUsedGb ?: 0f)} / ${String.format("%.1f", total)} GB"
            } ?: "--",
            accent = MaterialTheme.colorScheme.onSurface
        )
        system.cpuPercent?.let { cpu ->
            Spacer(Modifier.height(Space.sm))
            BarMeter(
                value = cpu / 100f,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
