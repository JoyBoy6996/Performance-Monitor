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
    val usageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val usageGranted = hasUsageAccess(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up FrameScope") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "FrameScope needs one-time access to read your game's FPS. " +
                        "Tap Grant access, then choose Allow in the Shizuku popup. " +
                        "The app will do the rest."
                )
                Text(message ?: status.detail.orEmptyIfBlank())
                Text(
                    if (usageGranted) {
                        "Usage access: On. No action needed."
                    } else {
                        "Usage access: Off. Tap Usage access → find FrameScope → turn it on."
                    }
                )
                Column {
                    TextButton(onClick = { openShizuku(context) }) {
                        Text("Open Shizuku")
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
    "If Shizuku is missing, use Open Shizuku first."
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
        Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
    )
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
