@file:OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)

package com.framescope.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.ContextCompat
import com.framescope.monitor.core.CrashLog
import com.framescope.monitor.core.ThemeMode
import com.framescope.monitor.recorder.ScreenRecorderService
import com.framescope.monitor.ui.FrameScopeRoot
import com.framescope.monitor.ui.collectAsStateValue
import com.framescope.monitor.ui.theme.FrameScopeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = runCatching { (application as FrameScopeApp).container }.getOrNull()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenRecorderService::class.java).apply {
                    action = ScreenRecorderService.ACTION_START
                    putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecorderService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                container?.engine?.startRecording()
            }
        }

    override fun onResume() {
        super.onResume()
        container?.let { app ->
            app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                app.hub.refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val motionEnabled = runCatching {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = container
        if (app == null) {
            setContent {
                Text(
                    text = "FrameScope could not start.\n\n" +
                        (CrashLog.last(this) ?: "No details were saved."),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
            return
        }

        setContent {
            val themeName = app.settings.themeMode.flow.collectAsStateValue()
            val theme = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.SYSTEM)
            val dynamic = app.settings.dynamicColor.flow.collectAsStateValue()
            FrameScopeTheme(
                themeMode = theme,
                dynamicColor = dynamic,
                motionEnabled = motionEnabled
            ) {
                val windowSize = calculateWindowSizeClass(this)
                FrameScopeRoot(
                    container = app,
                    widthSizeClass = windowSize.widthSizeClass,
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestScreenRecord = { requestScreenRecording() }
                )
            }
        }
    }

    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermission.launch(intent)
        }
    }

    fun requestScreenRecording() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }
}
