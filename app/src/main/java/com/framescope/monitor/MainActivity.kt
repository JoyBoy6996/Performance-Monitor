package com.framescope.monitor

import android.Manifest
import android.content.Intent
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
import com.framescope.monitor.ui.FrameScopeRoot
import com.framescope.monitor.ui.collectAsStateValue
import com.framescope.monitor.ui.theme.FrameScopeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * The container, or null when the application failed to build it.
     *
     * A failure there used to take the whole app down and leave nothing but a
     * black screen, with no way to find out what went wrong.
     */
    private val container get() = runCatching { (application as FrameScopeApp).container }.getOrNull()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Respect the system "remove animations" setting for the decorative motion.
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
                    onRequestOverlayPermission = { requestOverlayPermission() }
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
}
