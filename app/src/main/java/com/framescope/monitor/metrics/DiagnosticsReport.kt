package com.framescope.monitor.metrics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsSourceKind
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.ShellHub
import java.util.Locale

/**
 * The evidence needed to explain a wrong reading.
 *
 * Everything here is gathered locally and only leaves the device when the user asks for
 * it. Nothing about the device's identity beyond the model and the build is included:
 * no serial number, no advertising id, nothing that identifies the person holding it.
 */
object DiagnosticsReport {

    /** Builds the report. */
    fun build(
        context: Context,
        hub: ShellHub,
        engine: MetricsEngine,
        settings: AppSettings,
        shellOutput: String
    ): String {
        val sb = StringBuilder()

        sb.appendLine("=== FrameScope diagnostics ===")
        sb.appendLine("generated: ${java.util.Date()}")
        sb.appendLine(
            "captured_from=${engine.lastMeasuredPackage.value ?: "(none)"} at=${SystemClock.uptimeMillis()}"
        )

        // --- The device -----------------------------------------------------------
        sb.appendLine()
        sb.appendLine("--- device ---")
        sb.appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("build: ${Build.FINGERPRINT}")

        // --- Shizuku -------------------------------------------------------------
        val status = hub.status.value
        sb.appendLine()
        sb.appendLine("--- privileged access ---")
        sb.appendLine("state: ${status.state.name.lowercase()}")
        sb.appendLine("permission granted: ${status.permissionGranted}")
        sb.appendLine("binder available: ${status.binderAvailable}")
        sb.appendLine("user service connected: ${status.userServiceConnected}")
        sb.appendLine("command verified: ${status.commandVerified}")
        sb.appendLine("active shell: ${status.shellSource}")
        sb.appendLine("local adb bridge ready: ${status.adbReady}")
        sb.appendLine("local adb key trusted: ${status.adbAuthorised}")
        if (status.adbFingerprint.isNotBlank()) {
            sb.appendLine("local adb fingerprint: ${status.adbFingerprint}")
        }
        if (status.detail.isNotBlank()) {
            sb.appendLine("detail: ${status.detail}")
        }
        hub.lastError.value?.let { sb.appendLine("last error: $it") }

        // --- Foreground ----------------------------------------------------------
        sb.appendLine()
        sb.appendLine("--- foreground app ---")
        sb.appendLine("package: ${engine.foregroundApp.value ?: "(unknown)"}")
        sb.appendLine("detected by: ${engine.foregroundSourceName()}")
        sb.appendLine("exact: ${engine.foregroundIsExact()}")

        // --- What the device answered --------------------------------------------
        val foregroundText = engine.lastForegroundOutput()
        if (foregroundText.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- what the window manager answered ---")
            sb.append(foregroundText.trim())
            sb.appendLine()
        }

        val counterText = engine.lastCounterDump()
        if (counterText.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- the counters the device printed (first 6000 characters) ---")
            sb.append(counterText.take(6000).trim())
            sb.appendLine()
        }

        // --- The frame rate ------------------------------------------------------
        val fps = engine.fps.value
        sb.appendLine()
        sb.appendLine("--- frame rate ---")
        sb.appendLine("availability: ${fps.availability.name.lowercase()}")
        sb.appendLine("source: ${sourceName(fps.sourceKind)}")
        sb.appendLine("source label: ${fps.source}")
        sb.appendLine("layer: ${fps.surface ?: "(none)"}")
        sb.appendLine("target reason: ${engine.measuredLayerReason()}")
        sb.appendLine("sample age: ${fps.sampleAgeMs} ms")
        sb.appendLine("measurement window: ${fps.measurementWindowMs} ms")
        sb.appendLine("frames counted: ${fps.totalFrames}")
        sb.appendLine("jank frames: ${fps.jankFrames}")
        if (!fps.reason.isNullOrBlank()) {
            sb.appendLine("reason: ${fps.reason}")
        }

        // --- The device metrics --------------------------------------------------
        sb.appendLine()
        sb.appendLine("--- metric availability ---")
        val system = engine.system.value
        for (metric in SystemMetric.entries) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%-16s %-20s %s",
                    metric.name.lowercase(),
                    system.availabilityOf(metric).name.lowercase(),
                    system.sourceOf(metric) ?: "-"
                )
            )
        }

        // --- What the shell saw --------------------------------------------------
        if (shellOutput.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- shell output ---")
            sb.append(shellOutput.trim())
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    /** The short name of a frame rate provider. */
    private fun sourceName(kind: FpsSourceKind): String = when (kind) {
        FpsSourceKind.SURFACE_FLAINGER_LATENCY -> "SurfaceFlinger latency"
        FpsSourceKind.TIMESTATS -> "SurfaceFlinger counters"
        FpsSourceKind.GFXINFO -> "gfxinfo (app UI thread)"
        FpsSourceKind.NONE -> "none"
    }
}
