package com.framescope.monitor.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.framescope.monitor.core.AccessMode
import com.framescope.monitor.core.Backup
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.ThemeMode
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.AdbAuthState
import com.framescope.monitor.ui.FrameScopeScreen
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

@Composable
fun SettingsScreen(
    container: AppContainer,
    bottomPadding: Dp = 24.dp
) {
    val context = LocalContext.current
    val settings = container.settings
    val status by container.hub.status.collectAsState()
    val authState by container.hub.adbAuthState.collectAsState()
    val fingerprint = container.hub.adbFingerprint
    val diagnostic by container.engine.diagnostic.collectAsState()
    val accessMode by settings.accessMode.flow.collectAsState()
    val themeMode by settings.themeMode.flow.collectAsState()
    val dynamicColor by settings.dynamicColor.flow.collectAsState()
    val protectApps by settings.protectBankApps.flow.collectAsState()
    val measuring by settings.measurementEnabled.flow.collectAsState()
    val threshold by settings.fpsThreshold.flow.collectAsState()
    val interval by settings.sampleIntervalMs.flow.collectAsState()
    val autoRecord by settings.autoRecord.flow.collectAsState()
    val extraProtected by settings.protectedApps.flow.collectAsState()

    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val pkg = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    var checking by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var adbHostText by remember { mutableStateOf(settings.adbHost.value) }
    var adbPortText by remember { mutableStateOf(settings.adbPort.value.toString()) }
    var keepBridge by remember { mutableStateOf(settings.keepAdbBridge.value) }
    var bridgeMessage by remember { mutableStateOf<String?>(null) }
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
            Panel {
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
            Panel {
                SettingRow(
                    title = "Backend",
                    supporting = status.detail.ifBlank {
                        diagnostic ?: "Shell level access is required for real frame rate data."
                    },
                    trailing = {
                        StatusPill(
                            text = if (status.state == AccessState.READY) "Connected" else status.backendLabel,
                            color = if (status.state == AccessState.READY) {
                                MaterialTheme.status.good
                            } else {
                                MaterialTheme.status.warn
                            }
                        )
                    }
                )
                PanelDivider()
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Text(
                        text = "Access mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(Space.sm))
                    SegmentedOptions(
                        options = listOf("Auto", "Shizuku", "Adb", "Root"),
                        selected = accessMode.lowercase().replaceFirstChar { it.uppercase() },
                        onSelect = { picked ->
                            val mode = AccessMode.entries.firstOrNull {
                                it.name.equals(picked, ignoreCase = true)
                            }
                            settings.accessMode.value = (mode ?: AccessMode.AUTO).name
                        }
                    )
                }
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
                            scope.launch {
                                container.hub.applyAdbEndpoint(
                                    settings.adbHost.value,
                                    settings.adbPort.value
                                )
                                container.hub.forceRecheck(
                                    preferred = settings.accessMode.value,
                                    adbPort = settings.adbPort.value,
                                    allowAdbBootstrap = settings.keepAdbBridge.value
                                )
                                checking = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Re-check", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = { scope.launch { container.hub.shizukuRunner?.requestPermission() } }
                    ) {
                        Text("Request Shizuku")
                    }
                }
            }
        }

        item {
            Panel {
                SettingRow(
                    title = "Debugging authorisation",
                    supporting = when (authState) {
                        AdbAuthState.STORED ->
                            "The phone keeps this key, so the \"Allow USB debugging?\" prompt " +
                                "will not come back - not after a reconnect and not after a reboot."
                        AdbAuthState.PROMPT_SENT ->
                            "Waiting for you: unlock the phone, tick \"Always allow from this " +
                                "computer\" and confirm the prompt. FrameScope keeps the key on " +
                                "the phone afterwards."
                        AdbAuthState.UNKNOWN ->
                            "Not confirmed yet. The prompt appears once, on the phone."
                    },
                    trailing = {
                        StatusPill(
                            text = when (authState) {
                                AdbAuthState.STORED -> "Permanent"
                                AdbAuthState.PROMPT_SENT -> "Pending"
                                AdbAuthState.UNKNOWN -> "Unknown"
                            },
                            color = if (authState == AdbAuthState.STORED) {
                                MaterialTheme.status.good
                            } else {
                                MaterialTheme.status.warn
                            }
                        )
                    }
                )
                PanelDivider()
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Text(
                        text = "This phone's fingerprint",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(Space.sm))
                    MonoField(
                        value = fingerprint,
                        onValueChange = {},
                        label = "Fingerprint",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(Space.sm))
                    Text(
                        text = "The phone prints the same fingerprint in its prompt. If they " +
                            "match, the prompt belongs to FrameScope.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Panel {
                SettingRow(
                    title = "ADB endpoint",
                    supporting = "Where the phone's own ADB bridge is listening. Leave it at " +
                        "127.0.0.1 and 5555 unless you are pointing FrameScope at another " +
                        "machine."
                )
                PanelDivider()
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        MonoField(
                            value = adbHostText,
                            onValueChange = {
                                adbHostText = it.trim()
                                settings.adbHost.value = it.trim()
                            },
                            label = "Host, for example 127.0.0.1",
                            modifier = Modifier.weight(1f)
                        )
                        MonoField(
                            value = adbPortText,
                            onValueChange = {
                                adbPortText = it.filter { c -> c.isDigit() }.take(5)
                                adbPortText.toIntOrNull()?.let { port ->
                                    settings.adbPort.value = port
                                }
                            },
                            label = "Port",
                            modifier = Modifier.width(104.dp)
                        )
                    }
                }
            }
        }

        item {
            Panel {
                SettingRow(
                    title = "Find the bridge on this phone",
                    supporting = "Looks for the ADB service that Wireless debugging started on " +
                        "this phone and moves it to a fixed port. Accept the \"Allow USB " +
                        "debugging?\" prompt when it appears."
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
                            bridgeMessage = "Looking for the ADB service on this phone..."
                            scope.launch {
                                bridgeMessage = container.hub.findBridgeOnPhone(settings.adbPort.value)
                                checking = false
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Find the bridge", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                val message = bridgeMessage
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.lg)
                    )
                }
            }
        }

        item {
            Panel {
                SettingRow(
                    title = "Keep the bridge alive",
                    supporting = "After a reboot the phone stops listening for ADB " +
                        "connections. With root or Shizuku, FrameScope switches it back on " +
                        "by itself, so the one-time setup stays done. The bridge runs on the " +
                        "phone itself, so it works with Wi-Fi, with mobile data, or with both off.",
                    trailing = {
                        Switch(
                            checked = keepBridge,
                            onCheckedChange = {
                                keepBridge = it
                                settings.keepAdbBridge.value = it
                            }
                        )
                    }
                )
            }
        }

        item { SectionHeader("One-time setup") }

        item {
            Panel {
                SetupOption(
                    title = "Option A - Shizuku",
                    badge = "Recommended",
                    description = "Start Shizuku once, then grant FrameScope the Shizuku permission. " +
                        "aShell and aShell You use the same Shizuku backend.",
                    command = "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/tools/start.sh",
                    context = context
                )
                PanelDivider()
                SetupOption(
                    title = "Option B - local ADB bridge, no computer needed",
                    description = "Turn on Wireless debugging in Developer options, then tap " +
                        "\"Find the bridge\" below. FrameScope looks for the ADB service the phone " +
                        "started itself and moves it to a fixed port, so the bridge lives on the " +
                        "phone and works with Wi-Fi off, on mobile data, or with both. Accept the " +
                        "\"Allow USB debugging?\" prompt once.",
                    footnote = "No computer, no root and no Shizuku needed. After a reboot, turn " +
                        "Wireless debugging on again and FrameScope reconnects by itself.",
                    context = context
                )
                PanelDivider()
                SetupOption(
                    title = "Option C - root",
                    description = "If the device is rooted, FrameScope detects su automatically. Rooting is " +
                        "the option most likely to be flagged by banking apps, so prefer Shizuku.",
                    context = context
                )
            }
        }

        item { SectionHeader("Banking app protection") }

        item {
            Panel {
                SettingRow(
                    title = "Hide overlay in banking apps",
                    supporting = "FrameScope needs no root, no accessibility and no device admin, and the " +
                        "overlay steps aside for banking and payment apps.",
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
            Panel {
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
                PanelDivider()
                SettingRow(
                    title = "Auto record",
                    supporting = "Start a recording as soon as a new app comes to the foreground.",
                    trailing = {
                        Switch(
                            checked = autoRecord,
                            onCheckedChange = { settings.autoRecord.value = it }
                        )
                    }
                )
            }
        }

        item { SectionHeader("Appearance") }

        item {
            Panel {
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
            Panel {
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
                            context.startActivity(Intent.createChooser(send, "Export FrameScope backup"))
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
                StatusPill(text = badge, color = MaterialTheme.status.good)
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("FrameScope command", command))
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
