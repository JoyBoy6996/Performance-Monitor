package com.framescope.monitor.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.framescope.monitor.FrameScopeApp
import com.framescope.monitor.MainActivity
import com.framescope.monitor.R
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsSourceKind
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.OverlayStyle
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.core.finiteOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Floating telemetry overlay that displays real-time FPS and system stats on top of any app.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var fpsText: TextView? = null
    private var unitText: TextView? = null
    private var frameText: TextView? = null
    private var titleText: TextView? = null
    private var graphView: GraphView? = null
    private val rowViews = mutableMapOf<String, TextView>()
    private val rowLabels = mutableMapOf<String, TextView>()

    private var expanded = true
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    private var currentStyle = OverlayStyle.CLASSIC
    private var currentScale = 1.0f
    private var currentOpacity = 0.88f
    private var currentRows = ""
    private var showGraph = true
    private var graphHeight = 54

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        buildOverlay()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun removeOverlay() {
        root?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        root = null
    }

    /**
     * Asks the system for the two exemptions the overlay needs.
     *
     * The Settings app hides overlays it does not trust, and a privileged shell
     * can switch that off. Without it the overlay disappears the moment the
     * user opens the Settings app, which is where the overlay permission is.
     */
    private fun grantOverlayExemptions(app: FrameScopeApp) {
        val pkg = applicationContext.packageName
        app.container.scope.launch(Dispatchers.IO) {
            runCatching {
                app.container.hub.execOrNull(
                    "cmd appops set $pkg HIDE_OVERLAY_WINDOWS allow",
                    4_000
                )
                app.container.hub.execOrNull(
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                    4_000
                )
            }
        }
    }

    private fun buildOverlay() {
        graphView = null
        if (!Settings.canDrawOverlays(this)) return

        val app = applicationContext as? FrameScopeApp ?: return
        val settings = app.container.settings

        grantOverlayExemptions(app)

        currentStyle = runCatching {
            OverlayStyle.valueOf(settings.overlayStyle.value)
        }.getOrDefault(OverlayStyle.CLASSIC)
        currentScale = settings.overlayScale.value.coerceIn(0.7f, 1.6f)
        // Android does not let a window that covers more than this much of the
        // screen pass touches through, so the Settings app and its permission
        // dialogs would stop working with the overlay on top of them.
        currentOpacity = settings.overlayOpacity.value.coerceIn(0.3f, MAX_OVERLAY_OPACITY)
        currentRows = settings.overlayRows.value
        showGraph = settings.overlayShowGraph.value
        graphHeight = settings.overlayGraphHeight.value

        rowViews.clear()
        rowLabels.clear()
        removeOverlay()

        when (currentStyle) {
            OverlayStyle.PILL -> buildPill(settings)
            OverlayStyle.RTSS -> buildRtss(settings)
            else -> buildStandard(settings)
        }
        applyExpandedState()
    }

    /**
     * A fixed width is the whole fix for the "popup covers the game" reports.
     *
     * With WRAP_CONTENT the weighted frame-time text asked for all the space the
     * window could get, so Classic, Detailed and RTSS grew to the screen width.
     * Each style now gets a small, known width and the window is created with
     * that exact size.
     */
    private fun overlayWidthPx(): Int {
        val styleWidth = when (currentStyle) {
            OverlayStyle.PILL -> 132f
            OverlayStyle.COMPACT -> 108f
            OverlayStyle.CLASSIC -> 126f
            OverlayStyle.DETAILED -> 138f
            OverlayStyle.RTSS -> 116f
        }
        val wanted = OverlayViews.dp(this, styleWidth * currentScale)
        val max = (resources.displayMetrics.widthPixels - OverlayViews.dp(this, 12f))
            .coerceAtLeast(OverlayViews.dp(this, 80f))
        return wanted.coerceAtMost(max)
    }

    private fun buildPill(settings: AppSettings) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = OverlayViews.dp(this@OverlayService, 12f * currentScale)
            val padV = OverlayViews.dp(this@OverlayService, 6f * currentScale)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = OverlayViews.dp(this@OverlayService, 100f).toFloat()
                setColor(Color.parseColor("#E60B1020"))
                setStroke(OverlayViews.dp(this@OverlayService, 1f), Color.parseColor("#44FFFFFF"))
            }
        }

        fpsText = OverlayViews.value(this, "--", Color.parseColor("#4ADE80"), 16f * currentScale)
        unitText = OverlayViews.label(this, "FPS", Color.parseColor("#94A3B8")).apply {
            textSize = 9f * currentScale
        }
        frameText = OverlayViews.value(this, "", Color.parseColor("#CBD5E1"), 11f * currentScale)

        layout.addView(fpsText)
        layout.addView(unitText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = OverlayViews.dp(this@OverlayService, 3f) })
        layout.addView(frameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = OverlayViews.dp(this@OverlayService, 6f) })

        attachTouchAndWindow(layout, settings)
    }

    private fun buildRtss(settings: AppSettings) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = OverlayViews.dp(this@OverlayService, 8f * currentScale)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = OverlayViews.dp(this@OverlayService, 6f).toFloat()
                setColor(Color.parseColor("#D9000000"))
                setStroke(OverlayViews.dp(this@OverlayService, 1f), Color.parseColor("#55FFA500"))
            }
        }

        titleText = OverlayViews.label(this, "FRAMESCOPE", Color.parseColor("#FFA500")).apply {
            textSize = 9f * currentScale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        layout.addView(titleText)

        val header = OverlayViews.row(this)
        fpsText = OverlayViews.value(this, "--", Color.parseColor("#00FF66"), 20f * currentScale)
        frameText = OverlayViews.value(this, "", Color.parseColor("#FFA500"), 12f * currentScale)
        header.addView(
            fpsText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            frameText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        layout.addView(header)

        populateRows(layout, settings, isRtss = true)
        attachTouchAndWindow(layout, settings)
    }

    private fun buildStandard(settings: AppSettings) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = OverlayViews.dp(this@OverlayService, 10f * currentScale)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = OverlayViews.dp(this@OverlayService, 14f).toFloat()
                setColor(Color.parseColor("#E60B1020"))
                setStroke(OverlayViews.dp(this@OverlayService, 1f), Color.parseColor("#33FFFFFF"))
            }
        }

        if (currentStyle == OverlayStyle.DETAILED) {
            titleText = OverlayViews.label(this, "FRAMESCOPE", Color.parseColor("#64748B")).apply {
                textSize = 8f * currentScale
            }
            layout.addView(titleText)
        }

        val header = OverlayViews.row(this)
        fpsText = OverlayViews.value(this, "--", Color.parseColor("#4ADE80"), 24f * currentScale)
        frameText = OverlayViews.value(this, "", Color.parseColor("#CBD5E1"), 12f * currentScale)
        header.addView(
            fpsText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            frameText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        layout.addView(header)

        if (showGraph && currentStyle != OverlayStyle.COMPACT) {
            graphView = GraphView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    OverlayViews.dp(this@OverlayService, graphHeight.toFloat() * currentScale)
                ).apply { topMargin = OverlayViews.dp(this@OverlayService, 4f) }
            }
            layout.addView(graphView)
        }

        if (currentStyle != OverlayStyle.COMPACT) {
            populateRows(layout, settings, isRtss = false)
        }

        attachTouchAndWindow(layout, settings)
    }

    private fun populateRows(layout: LinearLayout, settings: AppSettings, isRtss: Boolean) {
        val rows = settings.overlayRows.value.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        val labelColor = if (isRtss) Color.parseColor("#FFA500") else Color.parseColor("#7DD3FC")
        val valColor = if (isRtss) Color.parseColor("#00FF66") else Color.parseColor("#E2E8F0")

        for (row in rows) {
            val rowView = OverlayViews.row(this)
            val label = OverlayViews.label(this, row, labelColor).apply {
                textSize = 9f * currentScale
            }
            rowView.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val value = OverlayViews.value(this, "--", valColor, 12f * currentScale)
            rowView.addView(
                value,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            rowViews[row] = value
            rowLabels[row] = label
            layout.addView(rowView)
        }
    }

    private fun attachTouchAndWindow(layout: LinearLayout, settings: AppSettings) {
        val dm = resources.displayMetrics
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (settings.overlayClickThrough.value) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val windowWidth = if (currentStyle == OverlayStyle.PILL) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else overlayWidthPx()
        val lp = WindowManager.LayoutParams(
            windowWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (settings.overlayX.value * dm.widthPixels).toInt().coerceIn(0, dm.widthPixels)
            y = (settings.overlayY.value * dm.heightPixels).toInt().coerceIn(0, dm.heightPixels)
            alpha = currentOpacity
        }
        params = lp

        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        layout.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    startX = lp.x.toFloat()
                    startY = lp.y.toFloat()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val viewW = layout.width.takeIf { it > 0 } ?: OverlayViews.dp(this, 120f)
                        val viewH = layout.height.takeIf { it > 0 } ?: OverlayViews.dp(this, 40f)
                        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - viewH).coerceAtLeast(0)
                        lp.x = (startX + dx).toInt().coerceIn(0, maxX)
                        lp.y = (startY + dy).toInt().coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(layout, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settings.overlayX.value = (lp.x.toFloat() / dm.widthPixels).coerceIn(0f, 1f)
                        settings.overlayY.value = (lp.y.toFloat() / dm.heightPixels).coerceIn(0f, 1f)
                    } else {
                        v.performClick()
                        expanded = !expanded
                        applyExpandedState()
                    }
                    true
                }
                else -> false
            }
        }

        root = layout
        runCatching { windowManager.addView(layout, lp) }
    }

    private fun applyExpandedState() {
        val visibility = if (expanded) View.VISIBLE else View.GONE
        graphView?.visibility = visibility
        rowViews.values.forEach { it.parent?.let { p -> (p as? View)?.visibility = visibility } }
    }

    private fun observe() {
        val app = applicationContext as? FrameScopeApp ?: return
        val engine = app.container.engine
        val settings = app.container.settings

        scope.launch {
            combine(engine.fps, engine.system, engine.protectedForeground) { fps, system, protected ->
                Triple(fps, system, protected)
            }.collect { (fps, system, protected) ->
                if (protected) {
                    root?.visibility = View.GONE
                    return@collect
                } else {
                    root?.visibility = View.VISIBLE
                }

                updateValues(fps, system, settings)
            }
        }

        // Rebuild only when structure or text size changes (style, rows, scale).
        // Scale changes the pixel width of the window, so it cannot be a restyle.
        scope.launch {
            combine(
                settings.overlayStyle.flow,
                settings.overlayRows.flow,
                settings.overlayScale.flow
            ) { style, rows, scale ->
                Triple(style, rows, scale)
            }.collect {
                buildOverlay()
            }
        }

        // Re-style in place for presentation properties
        scope.launch {
            combine(
                settings.overlayOpacity.flow,
                settings.overlayShowGraph.flow,
                settings.overlayGraphHeight.flow,
                settings.overlayClickThrough.flow
            ) { _, _, _, _ -> }.collect {
                restyleOverlay(settings)
            }
        }
    }

    private fun restyleOverlay(settings: AppSettings) {
        val rootLayout = root ?: return
        val lp = params ?: return
        currentOpacity = settings.overlayOpacity.value.coerceIn(0.3f, MAX_OVERLAY_OPACITY)
        showGraph = settings.overlayShowGraph.value
        graphHeight = settings.overlayGraphHeight.value

        lp.alpha = currentOpacity
        if (settings.overlayClickThrough.value) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

        graphView?.let { gv ->
            gv.visibility = if (showGraph && expanded) View.VISIBLE else View.GONE
            val lpGraph = gv.layoutParams
            lpGraph?.height = OverlayViews.dp(this, graphHeight.toFloat() * currentScale)
            gv.layoutParams = lpGraph
        }

        runCatching { windowManager.updateViewLayout(rootLayout, lp) }
    }

    private fun updateValues(fpsState: FpsState, system: SystemMetrics, settings: AppSettings) {
        // The display's own refresh rate is what a frame rate has to be judged
        // against. A phone that presents 144 frames a second has not drawn a
        // slow game at 100, and a phone that presents 60 has not drawn a fast
        // one at 45. The fixed 45 that used to be the target said nothing about
        // either screen; it only described one old setting.
        val target = system.refreshHz?.roundToInt()?.takeIf { it > 0 }
            ?: settings.fpsThreshold.value
        val fpsVal = fpsState.fps

        // A reading that is older than a couple of seconds is not a live reading, and
        // it says so instead of looking like a current number.
        val stale = fpsState.valid && fpsState.sampleAgeMs > STALE_AFTER_MS
        val capNote = fpsState.detectedCapFps?.let { cap ->
            " · $cap cap" + if (fpsState.capCause == "heat") " (heat)" else ""
        }.orEmpty()

        fpsText?.text = if (fpsState.valid) "%.1f".format(fpsVal) else "--"
        fpsText?.setTextColor(
            if (stale) STALE_COLOR else OverlayViews.fpsColor(fpsVal, target)
        )

        frameText?.text = when {
            !fpsState.valid -> ""
            stale -> "%.1f ms - %d ms old".format(fpsState.frameTimeMs, fpsState.sampleAgeMs) + capNote
            else -> "%.1f ms".format(fpsState.frameTimeMs) + capNote
        }

        if (fpsState.history.isNotEmpty()) {
            graphView?.setValues(fpsState.history, target.toFloat())
        }

        val appName = (applicationContext as? FrameScopeApp)?.container?.engine?.foregroundApp?.value.orEmpty()
        titleText?.text = if (currentStyle == OverlayStyle.RTSS && appName.isNotBlank()) {
            appName.substringAfterLast('.').uppercase()
        } else {
            "FRAMESCOPE"
        }

        // Support every selectable row, including the observed cap and heat pair.
        rowViews["FPS"]?.text = if (fpsState.valid) "%.1f".format(fpsVal) else "--"
        rowViews["FRAME"]?.text = if (fpsState.valid) "%.1f ms".format(fpsState.frameTimeMs) else "--"
        rowViews["CAP"]?.text = fpsState.detectedCapFps?.let { cap ->
            "$cap" + if (fpsState.capCause == "heat") " heat" else ""
        } ?: "--"
        rowViews["CPU"]?.text = system.cpuPercent.finiteOrNull()?.let { "${it.roundToInt()}%" } ?: "--"
        rowViews["CPUF"]?.text = system.cpuFreqMHz?.let { "$it MHz" } ?: "--"
        rowViews["GPU"]?.text = system.gpuFreqMHz?.let { "$it MHz" } ?: (system.gpuName ?: "--")
        val temperature = system.deviceTemperatureC().finiteOrNull()
        rowViews["TEMP"]?.text = temperature?.let { "%.1f°C".format(it) } ?: "--"
        // A warm phone throttles long before it is hot, so the temperature
        // carries its own warning colour.
        temperature?.let { rowViews["TEMP"]?.setTextColor(OverlayViews.temperatureColor(it)) }
        rowViews["HEAT"]?.text = system.tempPeakC.finiteOrNull()?.let { peak ->
            val cooled = system.tempCooledC.finiteOrNull() ?: 0f
            "↑%.1f°C ↓%.1f°C".format(peak, cooled)
        } ?: "--"
        rowViews["BAT"]?.text = system.batteryLevel?.let { "$it%" } ?: "--"
        rowViews["POWER"]?.text = system.powerWatts.finiteOrNull()?.let { "%.2f W".format(it) } ?: "--"
        val ramUsed = system.ramUsedGb.finiteOrNull()
        val ramTotal = system.ramTotalGb.finiteOrNull()
        rowViews["MEM"]?.text = if (ramUsed != null && ramTotal != null) {
            "%.1f/%.0fG".format(ramUsed, ramTotal)
        } else system.ramPercent?.let { "$it%" } ?: "--"
        rowViews["REFRESH"]?.text = system.refreshHz.finiteOrNull()?.let { "${it.roundToInt()} Hz" } ?: "--"
        rowViews["APP"]?.text = if (appName.isNotBlank()) appName.substringAfterLast('.') else "--"
        // Which provider the reading came from, so a number that is not the game's own
        // frame rate is not mistaken for one.
        rowViews["SRC"]?.text = sourceLabel(fpsState.sourceKind)
    }

    /** The short name of the provider a reading came from. */
    private fun sourceLabel(kind: FpsSourceKind): String = when (kind) {
        FpsSourceKind.SURFACE_FLAINGER_LATENCY -> "SF"
        FpsSourceKind.TIMESTATS -> "TS"
        FpsSourceKind.GFXINFO -> "GFX"
        FpsSourceKind.NONE -> "--"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FPS Overlay Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live performance statistics on top of screen"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FrameScope Overlay Active")
            .setContentText("Monitoring FPS and device telemetry")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.framescope.monitor.overlay.STOP"

        /**
         * The most the overlay may cover.
         *
         * Android refuses to pass touches through a window that hides more than
         * this much of the screen, so anything above it would block the
         * Settings app and its permission dialogs.
         */
        private const val MAX_OVERLAY_OPACITY = 0.8f
        private const val CHANNEL_ID = "framescope_overlay_channel"
        private const val NOTIFICATION_ID = 4040

        /** A reading older than this is shown as out of date rather than as live. */
        private const val STALE_AFTER_MS = 2_000L

        /** The colour of a reading that is too old to be live. */
        private val STALE_COLOR = Color.parseColor("#94A3B8")
    }
}
