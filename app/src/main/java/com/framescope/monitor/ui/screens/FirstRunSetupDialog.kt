package com.framescope.monitor.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.framescope.monitor.AppContainer
import kotlinx.coroutines.launch

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

// ShizukuNext keeps the same package and API, and can start without Wi-Fi.
// Send users here when no Shizuku app is installed yet.
private const val SHIZUKU_NEXT_RELEASES = "https://github.com/rushiranpise/Shizuku-Next/releases"

/**
 * A one-screen setup for first-time users.
 *
 * Nothing here grants a permission by itself. It only calls the same Shizuku
 * request the existing Settings button uses, and opens Shizuku when it is not
 * installed. The frame-rate code is untouched.
 */
@Composable
fun FirstRunSetupDialog(
    container: AppContainer,
    onRequestOverlayPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by container.hub.status.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var adbMessage by remember { mutableStateOf<String?>(null) }
    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val usageGranted = hasUsageAccess(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up Qstats") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Qstats needs one-time access to read your game's FPS. " +
                        "ShizukuNext starts without Wi-Fi (USB debugging). Install it, tap " +
                        "Grant access, then choose Allow in its popup. The app does the rest."
                )
                Text(message ?: adbMessage ?: status.detail.orEmptyIfBlank())
                Text(
                    if (usageGranted) {
                        "Usage access: On. No action needed."
                    } else {
                        "Usage access: Off. Tap Usage access → find Qstats → turn it on."
                    }
                )
                Text(
                    "No Wi-Fi? ShizukuNext runs from USB debugging with no PC and no Wi-Fi. " +
                        "Start it, grant Qstats permission, and turn on “Start on boot” — that is the " +
                        "whole one-time setup. No ADB key is needed. " +
                        "If you prefer no Shizuku: connect to a PC with USB once, run 'adb tcpip 5555', " +
                        "then tap Set up ADB bridge here."
                )
                Column {
                    TextButton(onClick = { openShizuku(context) }) {
                        Text("Open Shizuku / Get Next")
                    }
                    TextButton(onClick = onRequestOverlayPermission) {
                        Text("Overlay permission")
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                usageAccessLauncher.launch(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                )
                            }
                        }
                    ) {
                        Text("Usage access")
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { adbMessage = it.message ?: "Could not open Developer options." }
                        }
                    ) {
                        Text("Developer options")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                adbMessage = "Opening the local ADB bridge..."
                                val result = runCatching { container.hub.setupAdbBridge() }
                                    .getOrElse { it.message ?: "Could not set up the bridge." }
                                val refreshed = runCatching { container.hub.refresh() }.getOrNull()
                                if (refreshed?.commandVerified == true) {
                                    container.settings.setupCompleted.value = true
                                }
                                adbMessage = result
                            }
                        }
                    ) {
                        Text("Set up ADB bridge")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        message = "Asking for access..."
                        runCatching {
                            container.hub.requestPermission()
                            container.hub.refresh(forceReconnect = true)
                        }.onFailure {
                            message = it.message ?: "Could not ask for access."
                        }.onSuccess {
                            message = if (container.hub.status.value.commandVerified) {
                                "Access ready."
                            } else {
                                "Allow the Shizuku popup, then wait a moment."
                            }
                        }
                    }
                }
            ) {
                Text("Grant access")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}

private fun String.orEmptyIfBlank(): String = if (isBlank()) {
    "If Shizuku is missing, tap Open Shizuku to get ShizukuNext (no Wi-Fi needed)."
} else {
    this
}

internal fun hasUsageAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.PACKAGE_USAGE_STATS) ==
        PackageManager.PERMISSION_GRANTED

private fun openShizuku(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    val intent = launchIntent ?: Intent(
        Intent.ACTION_VIEW,
        Uri.parse(SHIZUKU_NEXT_RELEASES)
    )
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
