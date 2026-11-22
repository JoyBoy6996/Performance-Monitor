package com.framescope.monitor.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import androidx.core.content.FileProvider
import com.framescope.monitor.core.Backup
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.ThemeMode
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.AdbAuthState
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.Diagnostics
import com.framescope.monitor.ui.components.MetricLine
import com.framescope.monitor.ui.components.MonoField
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.PanelDivider
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.SegmentedOptions
import com.framescope.monitor.ui.components.SettingRow
import com.framescope.monitor.ui.components.StatusPill
import com.framescope.monitor.ui.components.TerminalBlock
import com.framescope.monitor.ui.components.ValueSlider
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import kotlinx.coroutines.launch

/** A failure that would otherwise close the app is shown where the user tapped. */
private fun Throwable.describe(): String {
    val detail = message?.takeIf { it.isNotBlank() }
    return if (detail != null) "$detail (${javaClass.simpleName})" else javaClass.name
}

@Composable
fun SettingsScreen(
    container: AppContainer,
    bottomPadding: Dp = 24.dp
) {
    val context = LocalContext.current
    val settings = container.settings
    val status by container.hub.status.collectAsState()
    val setupCompleted by settings.setupCompleted.flow.collectAsState()
    val diagnostic by container.engine.diagnostic.collectAsState()
    val themeMode by settings.themeMode.flow.collectAsState()
    val dynamicColor by settings.dynamicColor.flow.collectAsState()
    val protectApps by settings.protectBankApps.flow.collectAsState()
    val measuring by settings.measurementEnabled.flow.collectAsState()
    val threshold by settings.fpsThreshold.flow.collectAsState()
    val interval by settings.sampleIntervalMs.flow.collectAsState()
    val extraProtected by settings.protectedApps.flow.collectAsState()

    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val pkg = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    var checking by remember { mutableStateOf(false) }
    var connectMessage by remember { mutableStateOf<String?>(null) }
    var adbMessage by remember { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val adbAuth by container.hub.adbAuthState.collectAsState()
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            backupMessage = "That file could not be read."
        } else {
            val count = runCatching { Backup.import(text, container.store, settings) }
                .getOrElse { 0 }
            backupMessage = if (count > 0) {
                "Restored $count ${if (count == 1) "session" else "sessions"} and your settings."
            } else {
                "Nothing to restore from that file."
            }
        }
    }

    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        installed = runCatching {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .filter { ProtectedApps.isProtected(it.packageName) }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }

    FrameScopeScreen(
        title = "Settings",
        subtitle = "Access, privacy and monitoring",
        bottomPadding = bottomPadding
    ) {
        item {
            Panel(contentPadding = 0.dp) {
                SettingRow(
                    title = "Frame rate measurement",
                    supporting = if (measuring) {
                        "Running: the frame rate is sampled and the overlay follows."
                    } else {
                        "Off: nothing is sampled and the overlay is hidden."
                    },
                    trailing = {
                        if (measuring) {
                            OutlinedButton(onClick = { settings.measurementEnabled.value = false }) {
                                Text("Pause")
                            }
                        } else {
                            FilledTonalButton(onClick = { settings.measurementEnabled.value = true }) {
                                Text("Resume")
                            }
                        }
                    }
                )
            }
        }

        item { SectionHeader("Privileged access") }

        item {
            Panel(contentPadding = 0.dp) {
                SettingRow(
                    title = "Shizuku service",
                    supporting = status.detail.ifBlank {
                        if (setupCompleted) {
                            "Setup was verified previously. Shell access is temporary and is available only while Shizuku is running."
                        } else {
                            diagnostic ?: "Shizuku is preferred; the local ADB bridge is used only when Shizuku is not answering."
                        }
                    },
                    trailing = {
                        StatusPill(
                            text = when {
                                status.commandVerified -> "Verified"
                                !status.permissionGranted -> "Permission needed"
                                !status.binderAvailable -> "Shizuku stopped"
                                status.state == AccessState.UNAVAILABLE -> "Connection error"
                                status.userServiceConnected -> "Verifying"
                                else -> "Connecting"
                            },
                            color = if (status.state == AccessState.READY) {
                                MaterialTheme.status.good
                            } else {
                                MaterialTheme.status.warn
                            }
                        )
                    }
                )
                PanelDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    FilledTonalButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            connectMessage = "Checking the connection..."
                            scope.launch {
                                runCatching { container.hub.refresh(forceReconnect = true) }
                                    .onFailure { connectMessage = it.describe() }
                                    .onSuccess { connectMessage = it.detail.ifBlank { "Connection checked." } }
                                checking = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Check status", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            connectMessage = "Asking for permission..."
                            scope.launch {
                                runCatching {
                                    container.hub.requestPermission()
                                    container.hub.refresh(forceReconnect = true)
                                }.onFailure { connectMessage = it.describe() }
                                    .onSuccess { connectMessage = it.detail.ifBlank { "Connection checked." } }
                                checking = false
                            }
                        }
                    ) {
                        Text(
                            when {
                                status.permissionGranted -> "Reconnect"
                                setupCompleted -> "Grant permission again"
                                else -> "Request permission"
                            }
                        )
                    }
                }
                val message = connectMessage
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                    )
                }
                OutlinedButton(
                    onClick = {
                        val crash = com.framescope.monitor.core.CrashLog.last(context)
                        Diagnostics.copy(
                            context,
                            crash ?: "No crash was recorded on this device."
                        )
                        connectMessage = if (crash == null) {
                            "No crash was recorded on this device."
                        } else {
                            "Last crash copied - paste it here."
                        }
                    },
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                ) {
                    Text("Copy last crash")
                }
            }
        }

        item { SectionHeader("Shizuku setup") }

        item {
            Panel(contentPadding = 0.dp) {
                SetupOption(
                    title = "Start Shizuku",
                    badge = "Required",
                    description = "For phones without Wi-Fi, use ShizukuNext: start it from USB debugging, no PC needed. Grant Qstats permission above. Official Shizuku and wireless debugging work too. Qstats checks a real UserService command before it reports Connected.",
                    context = context
                )
            }
        }

        item { SectionHeader("Local ADB bridge (optional)") }

        item {
            Panel(contentPadding = 0.dp) {
                Column(Modifier.padding(Space.lg)) {
                    Text(
                        text = "One-time setup, then it reconnects by itself",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "Easiest: keep Shizuku-Next running with Qstats granted permission. It gives " +
                            "live FPS with no ADB key and no Wi-Fi. Turn on “Start on boot” so it comes back " +
                            "after every restart — that is the whole one-time setup. No Shizuku? Connect the " +
                            "phone to a PC with USB once, run 'adb tcpip 5555', unplug, then tap Set up bridge " +
                            "here and approve the “Allow USB debugging?” prompt. Wireless debugging is another " +
                            "way to open the same port.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.md))
                    val shizukuActive = status.state == AccessState.READY &&
                        status.shellSource == "Shizuku"
                    val adbLine = when {
                        shizukuActive ->
                            "Shizuku is active — Qstats has shell access and reads live FPS. No ADB key is " +
                                "needed. Keep Shizuku “Start on boot” ON; it reconnects by itself after every " +
                                "restart."
                        status.adbReady && status.adbAuthorised ->
                            "Bridge: connected on 127.0.0.1:5555. Key saved — auto-reconnect is on."
                        status.adbReady ->
                            "Bridge: connected on 127.0.0.1:5555 — live FPS works now. Key not saved. " +
                                "Start Shizuku-Next and grant Qstats permission; then no key is needed."
                        adbAuth == AdbAuthState.PROMPT_SENT ->
                            "Bridge: waiting for the “Allow USB debugging?” prompt on your phone. " +
                                "Tick “Always allow”, tap Allow, then Reconnect."
                        adbAuth == AdbAuthState.STORED ->
                            "Bridge: key is trusted. Keep Shizuku running (or Wireless debugging ON); " +
                                "the port opens and reconnects by itself."
                        status.commandVerified ->
                            "Shizuku is active. Grant Qstats permission in Shizuku; the bridge then " +
                                "opens by itself. If it stays here, tap Set up bridge once."
                        status.detail.contains("permission", ignoreCase = true) ->
                            "Grant Qstats permission in Shizuku, then the bridge opens by itself."
                        else -> "Bridge: not set up yet. Start Shizuku (USB debugging works, no Wi-Fi needed), " +
                            "grant Qstats permission, then tap Set up bridge if needed."
                    }
                    Text(
                        text = adbLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!shizukuActive) {
                        Spacer(Modifier.height(Space.xs))
                        val fingerprint = status.adbFingerprint
                        Text(
                            text = if (fingerprint.isBlank()) {
                                "Key fingerprint: --"
                            } else {
                                "Key fingerprint: $fingerprint"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val message = adbMessage
                    if (message != null) {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilledTonalButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                adbMessage = "Opening the local ADB bridge..."
                                scope.launch {
                                    val result = runCatching { container.hub.setupAdbBridge() }
                                        .getOrElse { it.describe() }
                                    val refreshed = runCatching { container.hub.refresh() }
                                        .getOrNull()
                                    if (refreshed?.commandVerified == true) {
                                        settings.setupCompleted.value = true
                                    }
                                    adbMessage = result
                                    checking = false
                                }
                            }
                        ) {
                            Text("Set up bridge")
                        }
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                adbMessage = "Reconnecting to the local ADB bridge..."
                                scope.launch {
                                    val result = runCatching {
                                        container.hub.refresh(forceReconnect = true)
                                    }.getOrNull()
                                    adbMessage = result?.detail?.ifBlank { "Bridge rechecked." }
                                        ?: "Bridge recheck failed."
                                    checking = false
                                }
                            }
                        ) {
                            Text("Reconnect")
                        }
                    }
                    if (!shizukuActive && status.adbReady && !status.adbAuthorised) {
                        Spacer(Modifier.height(Space.xs))
                        FilledTonalButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                adbMessage = "Installing Qstats' key with Shizuku..."
                                scope.launch {
                                    val result = runCatching { container.hub.installKeyWithShizuku() }
                                        .getOrElse { it.describe() }
                                    val refreshed = runCatching { container.hub.refresh() }
                                        .getOrNull()
                                    if (refreshed?.adbAuthorised == true) {
                                        settings.setupCompleted.value = true
                                    }
                                    adbMessage = result
                                    checking = false
                                }
                            }
                        ) {
                            Text("Install key")
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure { adbMessage = it.describe() }
                            }
                        ) {
                            Text("Developer options")
                        }
                        OutlinedButton(
                            onClick = {
                                Diagnostics.copy(
                                    context,
                                    "Qstats local ADB setup - pick ONE way to open port 5555\n" +
                                        "\n" +
                                        "A) NO Wi-Fi, NO Shizuku (PC + USB cable, one time):\n" +
                                        "   1. Developer options -> USB debugging ON.\n" +
                                        "   2. Connect phone to PC with USB cable.\n" +
                                        "   3. On the PC run: adb tcpip 5555\n" +
                                        "   4. Unplug the cable.\n" +
                                        "   5. In Qstats tap Set up bridge.\n" +
                                        "   6. On the phone prompt tick “Always allow” and match: ${status.adbFingerprint}\n" +
                                        "\n" +
                                        "B) NO PC: install ShizukuNext, start it from USB debugging, grant Qstats permission, then tap Set up bridge.\n" +
                                        "\n" +
                                        "After the key is saved once, Qstats reconnects by itself on 127.0.0.1 - offline, no PC and no Wi-Fi needed."
                                )
                                adbMessage = "Setup steps copied - paste them where needed."
                            }
                        ) {
                            Text("Copy steps")
                        }
                    }
                }
            }
        }

        item {
            Panel(contentPadding = 0.dp) {
                SettingRow(
                    title = "Usage access",
                    supporting = if (hasUsageAccess(context)) {
                        "On. Qstats can use it when the window manager cannot name the foreground app."
                    } else {
                        "Off. Open settings, find Qstats and switch it on."
                    },
                    trailing = {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    usageAccessLauncher.launch(
                                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    )
                                }
                            }
                        ) {
                            Text("Open settings")
                        }
                    }
                )
            }
        }

        item { SectionHeader("Permanent shell access") }

        item {
            Panel(contentPadding = 0.dp) {
                Column(Modifier.padding(Space.lg)) {
                    Text(
                        text = "Grant the app the right to run dumpsys itself",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "With this permission the frame rate is read in the app's own " +
                            "shell: the answer is never cut short and the reading of a game " +
                            "is the same one the device itself gives. It is granted once and " +
                            "lasts until the app is removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.md))
                    val dumpGranted = container.hub.hasDumpPermission()
                    Text(
                        text = if (dumpGranted) {
                            "Granted - the app reads the frame timings by itself."
                        } else {
                            "Not granted yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilledTonalButton(
                            enabled = !dumpGranted,
                            onClick = {
                                scope.launch {
                                    container.hub.tryGrantDumpPermission()
                                    container.hub.refresh()
                                }
                            }
                        ) {
                            Text("Grant it now")
                        }
                        OutlinedButton(onClick = {
                            Diagnostics.copy(
                                context,
                                "pm grant ${context.packageName} android.permission.DUMP"
                            )
                        }) {
                            Text("Copy the command")
                        }
                    }
                }
            }
        }

        item { SectionHeader("Banking app protection") }

        item {
            Panel(contentPadding = 0.dp) {
                SettingRow(
                    title = "Hide overlay in banking apps",
                    supporting = "No root, no accessibility, no device admin. The overlay steps " +
                        "aside for banking and payment apps.",
                    trailing = {
                        Switch(
                            checked = protectApps,
                            onCheckedChange = { settings.protectBankApps.value = it }
                        )
                    }
                )
                if (installed.isEmpty()) {
                    Text(
                        text = "No banking app detected on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Space.lg)
                    )
                } else {
                    PanelDivider()
                    installed.forEach { (pkg, label) ->
                        SettingRow(
                            title = label,
                            supporting = pkg,
                            trailing = {
                                val extra = extraProtected.split(",").map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val enabled = pkg in ProtectedApps.KNOWN || pkg in extra
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        val current = extra.toMutableSet()
                                        if (checked) current.add(pkg) else current.remove(pkg)
                                        settings.setProtectedPackages(current)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        item { SectionHeader("Monitoring") }

        item {
            Panel(contentPadding = 0.dp) {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    ValueSlider(
                        label = "Sampling interval",
                        valueText = "$interval ms",
                        value = interval.toFloat(),
                        onValueChange = { settings.sampleIntervalMs.value = it.toInt() },
                        valueRange = 200f..2000f,
                        steps = 8
                    )
                    ValueSlider(
                        label = "Smoothness target",
                        valueText = "$threshold fps",
                        value = threshold.toFloat(),
                        onValueChange = { settings.fpsThreshold.value = it.toInt() },
                        valueRange = 24f..120f,
                        steps = 19
                    )
                }
            }
        }

        item { SectionHeader("Appearance") }

        item {
            Panel(contentPadding = 0.dp) {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    SegmentedOptions(
                        options = listOf("System", "Light", "Dark"),
                        selected = themeMode.lowercase().replaceFirstChar { it.uppercase() },
                        onSelect = { picked ->
                            val mode = ThemeMode.entries.firstOrNull {
                                it.name.equals(picked, ignoreCase = true)
                            }
                            settings.themeMode.value = (mode ?: ThemeMode.SYSTEM).name
                        }
                    )
                }
                PanelDivider()
                SettingRow(
                    title = "Material You colours",
                    supporting = "Follow the wallpaper palette instead of the built-in one.",
                    trailing = {
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { settings.dynamicColor.value = it }
                        )
                    }
                )
            }
        }

        item { SectionHeader("Backup") }

        item {
            Panel(contentPadding = 0.dp) {
                SettingRow(
                    title = "Export backup",
                    supporting = "Writes every recorded session and all settings into one JSON file.",
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.Upload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        runCatching {
                            val file = Backup.export(context, container.store, settings)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Export Qstats backup"))
                            backupMessage = "Backup ready to share."
                        }.onFailure {
                            backupMessage = "The backup could not be created."
                        }
                    }
                )
                PanelDivider()
                SettingRow(
                    title = "Import backup",
                    supporting = "Reads a backup file and puts your sessions and settings back.",
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                )
                backupMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Space.lg)
                    )
                }
            }
        }

        item { SectionHeader("About") }

        item {
            Panel {
                MetricLine(
                    label = "Version",
                    value = buildString {
                        append(pkg?.versionName ?: "unknown")
                        append(" (")
                        append(pkg?.let { info ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                info.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                info.versionCode.toLong()
                            }
                        } ?: 0L)
                        append(")")
                    },
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Android",
                    value = "API ${Build.VERSION.SDK_INT}",
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Privilege model",
                    value = "shell, no root required",
                    accent = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.status.good,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "No accessibility, no device admin, no hooking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.sm)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupOption(
    title: String,
    description: String,
    context: Context,
    command: String? = null,
    footnote: String? = null,
    badge: String? = null
) {
    Column(Modifier.padding(Space.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (badge != null) {
                Spacer(Modifier.width(Space.sm))
                // A requirement is not a health reading, so it stays a neutral chip.
                StatusPill(
                    text = badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xs)
        )
        if (command != null) {
            Spacer(Modifier.size(Space.md))
            TerminalBlock(
                text = command,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Qstats command", command))
                }
            )
        }
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
    }
}
