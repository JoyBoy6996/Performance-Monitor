package com.framescope.monitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.framescope.monitor.ui.theme.LocalMotionEnabled
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.ThemeMode
import com.framescope.monitor.ui.theme.FrameScopeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Field

/**
 * Renders the real screens to PNG files on the JVM.
 *
 * The output is written to `app/build/screenshots` and is only used to review the interface
 * during development; it is not part of the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSnapshotTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun renderScreens() {
        seedSessions()
        val container = AppContainer(
            org.robolectric.RuntimeEnvironment.getApplication()
        )
        seed(container)

        capture("01-monitor-dark", dark = true) {
            com.framescope.monitor.ui.screens.DashboardScreen(container = container, onOpenSettings = {})
        }
        capture("02-monitor-light", dark = false) {
            com.framescope.monitor.ui.screens.DashboardScreen(container = container, onOpenSettings = {})
        }
        capture("03-overlay-dark", dark = true) {
            com.framescope.monitor.ui.screens.OverlayScreen(container = container, onRequestOverlayPermission = {})
        }
        capture("03b-overlay-pill", dark = true) {
            container.settings.overlayStyle.value = "PILL"
            com.framescope.monitor.ui.screens.OverlayScreen(container = container, onRequestOverlayPermission = {})
        }

        capture("04-settings-dark", dark = true) {
            com.framescope.monitor.ui.screens.SettingsScreen(container = container)
        }
        capture("05-tools-dark", dark = true) {
            com.framescope.monitor.ui.screens.ToolsScreen(container = container)
        }
        capture("06-sessions-dark", dark = true) {
            com.framescope.monitor.ui.screens.SessionsScreen(container = container)
        }
        capture("08-settings-backup", dark = true, heightDp = 2100) {
            com.framescope.monitor.ui.screens.SettingsScreen(container = container)
        }
        capture("09-session-detail", dark = true, heightDp = 2600) {
            val session = container.store.sessions.value.firstOrNull()
                ?: com.framescope.monitor.core.SessionRecord(
                    id = "preview", appPackage = "com.example.game", appLabel = "Pixel Rush",
                    startedAt = System.currentTimeMillis(), durationMs = 412000,
                    avgFps = 56.3f, maxFps = 61f, minFps = 44f, low1Percent = 45.2f,
                    low5Percent = 48.9f, smoothness = 84f, totalFrames = 23220, jankFrames = 214,
                    avgFrameTimeMs = 17.8f, maxFrameTimeMs = 24.9f, maxTempC = 43.8f, avgCpu = 51.4f,
                    fpsSeries = emptyList(), cpuSeries = emptyList(), tempSeries = emptyList()
                )
            com.framescope.monitor.ui.screens.SessionDetailScreen(
                session = session,
                threshold = 45,
                onBack = {}
            )
        }
        capture("07-shell-dark", dark = true) {
            FrameScopeRoot(
                container = container,
                widthSizeClass = androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact,
                onRequestOverlayPermission = {}
            )
        }
    }


    /** Writes two recorded sessions so the sessions screen shows a populated list. */
    private fun seedSessions() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val series = listOf(
            54.2, 56.1, 58.0, 57.4, 59.2, 60.0, 58.8, 55.1, 51.3, 49.8, 53.2, 57.9, 60.1,
            59.4, 58.0, 57.1, 59.8, 60.2, 58.4, 55.6, 52.2, 48.4, 51.8, 56.3, 59.1
        )
        val seriesJson = series.joinToString(",") { "{\"t\":\"fps\",\"v\":$it}" }
        val cpuSeries = listOf(38f, 41f, 44f, 52f, 61f, 48f, 43f, 40f, 45f, 47f, 39f, 36f)
        val tempSeries = listOf(36f, 37f, 38f, 40f, 43f, 44f, 42f, 41f, 39f, 38f, 37f, 36f)
        val frameSeries = listOf(16.7f, 16.6f, 16.8f, 17.2f, 28.4f, 18.1f, 16.7f, 16.9f, 16.6f, 17.0f, 16.8f, 16.7f)
        val batterySeries = listOf(96f, 96f, 96f, 95f, 95f, 95f, 95f, 94f, 94f, 94f, 94f, 93f)
        val first = """
            {"id":"s1","appPackage":"com.example.game","appLabel":"Pixel Rush",
            "startedAt":${now - 7_200_000},"durationMs":412000,"avgFps":56.3,"maxFps":61.0,
            "minFps":44.0,"low1Percent":45.2,"low5Percent":48.9,"smoothness":84.0,
            "totalFrames":23220,"jankFrames":214,"avgFrameTimeMs":17.8,"maxFrameTimeMs":24.9,
            "maxTempC":43.8,"avgCpu":51.4,"fpsSeries":[${series.joinToString(",")}],
            "cpuSeries":[${cpuSeries.joinToString(",")}],
            "tempSeries":[${tempSeries.joinToString(",")}],
            "frameTimeSeries":[${frameSeries.joinToString(",")}],
            "batterySeries":[${batterySeries.joinToString(",")}],
            "longestFrameMs":28.4,"dropSeconds":3}
        """.trimIndent()
        val second = """
            {"id":"s2","appPackage":"com.example.racer","appLabel":"Asphalt Legends",
            "startedAt":${now - 90_000_000},"durationMs":655000,"avgFps":59.1,"maxFps":61.0,
            "minFps":55.0,"low1Percent":55.4,"low5Percent":56.1,"smoothness":97.0,
            "totalFrames":38710,"jankFrames":48,"avgFrameTimeMs":16.9,"maxFrameTimeMs":19.4,
            "maxTempC":39.2,"avgCpu":44.8,"fpsSeries":[${series.joinToString(",")}],
            "cpuSeries":[],"tempSeries":[]}
        """.trimIndent()
        File(dir, "s1.json").writeText(first)
        File(dir, "s2.json").writeText(second)
    }

    /** Fills the engine flows with representative numbers so the screens are not empty. */
    private fun seed(container: AppContainer) {
        val history = listOf(
            58f, 59f, 57f, 60f, 61f, 60f, 59f, 58f, 56f, 52f, 48f, 45f, 51f, 57f, 60f,
            61f, 60f, 60f, 59f, 60f, 60f, 59f, 58f, 59f, 60f, 60f, 60f, 59f, 60f, 60f
        )
        val engine = container.engine
        setNestedFlow(
            engine, "fpsSampler", "_state",
            FpsState(
                fps = 59.4f,
                frameTimeMs = 16.8f,
                history = history,
                totalFrames = 18422,
                jankFrames = 37,
                source = "SurfaceFlinger",
                surface = "com.example.game/com.example.game.MainActivity",
                valid = true
            )
        )
        setDirectFlow(
            engine, "_system",
            SystemMetrics(
                cpuPercent = 43f,
                cpuFreqMHz = 2400,
                gpuName = "Adreno (TM) 740",
                gpuFreqMHz = 612,
                batteryLevel = 78,
                batteryTempC = 31.2f,
                powerWatts = 3.7f,
                ramUsedGb = 4.2f,
                ramTotalGb = 11.6f,
                ramPercent = 36,
                thermalMaxC = 41.3f,
                refreshHz = 120f
            )
        )
        setDirectFlow(
            engine, "_live",
            LiveStats(
                avgFps = 57.8f,
                maxFps = 61f,
                minFps = 44f,
                low1Percent = 45.2f,
                low5Percent = 48.9f,
                smoothness = 86f,
                totalFrames = 4120,
                jankFrames = 96,
                avgFrameTimeMs = 17.3f,
                maxFrameTimeMs = 22.7f,
                durationMs = 72_000,
                maxTempC = 43.8f,
                avgCpu = 51f
            )
        )
        setDirectFlow(engine, "_recording", true)
        setDirectFlow(engine, "_foregroundApp", "com.example.game")
    }

    /** Writes a value into a private MutableStateFlow that lives directly on [target]. */
    private fun <T> setDirectFlow(target: Any, fieldName: String, value: T) {
        val flowField: Field = target.javaClass.getDeclaredField(fieldName)
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = flowField.get(target) as kotlinx.coroutines.flow.MutableStateFlow<T>
        flow.value = value
    }

    /** Writes a value into a private MutableStateFlow of a nested collaborator. */
    private fun <T> setNestedFlow(target: Any, owner: String, fieldName: String, value: T) {
        val ownerField = target.javaClass.getDeclaredField(owner)
        ownerField.isAccessible = true
        val inner = ownerField.get(target)
        val flowField: Field = inner.javaClass.getDeclaredField(fieldName)
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = flowField.get(inner) as kotlinx.coroutines.flow.MutableStateFlow<T>
        flow.value = value
    }

    private fun capture(
        name: String,
        dark: Boolean = true,
        heightDp: Int = 891,
        content: @Composable () -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        // Apply the app theme before the window is created so no action bar shows up.
        activity.setTheme(com.framescope.monitor.R.style.Theme_FrameScope)
        controller.setup()
        activity.setContent {
            FrameScopeTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                // Freezing the motion scale keeps the repeating animations from asking for
                // frames, so the render finishes instead of looping.
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        content()
                    }
                }
            }
        }
        // Bounded frame stepping: enough for the first composition and the entry animations,
        // without letting the repeating animations run the looper into an endless loop.
        repeat(4) {
            ShadowLooper.idleMainLooper(32, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels
        val height = (metrics.density * heightDp).toInt()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
