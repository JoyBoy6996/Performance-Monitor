package com.framescope.monitor.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.OverlayStyle
import com.framescope.monitor.core.SystemMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
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

        if (!Settings.canDrawOverlays(this)) {
            (applicationContext as? FrameScopeApp)?.container?.overlay?.syncRunning(false)
            stopSelf()
            return
        }

        (applicationContext as? FrameScopeApp)?.container?.overlay?.syncRunning(true)
        buildOverlay()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (applicationContext as? FrameScopeApp)?.container?.overlay?.syncRunning(false)
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (applicationContext as? FrameScopeApp)?.container?.overlay?.syncRunning(false)
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

    private fun buildOverlay() {
        graphView = null
        fpsText = null
        unitText = null
        frameText = null
        titleText = null

        if (!Settings.canDrawOverlays(this)) return

        val app = applicationContext as? FrameScopeApp ?: return
        val settings = app.container.settings
        val engine = app.container.engine

        currentStyle = runCatching {
            OverlayStyle.valueOf(settings.overlayStyle.value)
        }.getOrDefault(OverlayStyle.CLASSIC)
        currentScale = settings.overlayScale.value.coerceIn(0.7f, 1.6f)
        currentOpacity = settings.overlayOpacity.value.coerceIn(0.3f, 1.0f)
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
        updateValues(engine.fps.value, engine.system.value, settings)
        root?.visibility = if (engine.protectedForeground.value) View.GONE else View.VISIBLE
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
        ).apply { marginStart = OverlayViews.dp(this@OverlayService, 3f * currentScale) })
        layout.addView(frameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = OverlayViews.dp(this@OverlayService, 6f * currentScale) })

        attachTouchAndWindow(layout, settings)
    }

    private fun buildRtss(settings: AppSettings) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = OverlayViews.dp(this@OverlayService, 125f * currentScale)
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
        header.addView(fpsText)
        header.addView(frameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = OverlayViews.dp(this@OverlayService, 8f * currentScale) })
        layout.addView(header)

        populateRows(layout, settings, isRtss = true)
        attachTouchAndWindow(layout, settings)
    }

    private fun buildStandard(settings: AppSettings) {
        val isCompact = currentStyle == OverlayStyle.COMPACT
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!isCompact) {
                minimumWidth = OverlayViews.dp(this@OverlayService, 132f * currentScale)
            }
            val pad = OverlayViews.dp(this@OverlayService, (if (isCompact) 8f else 10f) * currentScale)
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
        header.addView(fpsText)

        if (!isCompact) {
            frameText = OverlayViews.value(this, "", Color.parseColor("#CBD5E1"), 12f * currentScale)
            header.addView(frameText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = OverlayViews.dp(this@OverlayService, 8f * currentScale) })
        }
        layout.addView(header)

        if (showGraph && !isCompact) {
            graphView = GraphView(this).apply {
                minimumWidth = OverlayViews.dp(this@OverlayService, 112f * currentScale)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    OverlayViews.dp(this@OverlayService, graphHeight.toFloat() * currentScale)
                ).apply { topMargin = OverlayViews.dp(this@OverlayService, 4f * currentScale) }
            }
            layout.addView(graphView)
        }

        if (!isCompact) {
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
            rowView.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = OverlayViews.dp(this@OverlayService, 8f * currentScale)
            })

            val value = OverlayViews.value(this, "--", valColor, 12f * currentScale)
            rowView.addView(value)

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

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
        layout.post { clampToScreen() }
    }

    private fun clampToScreen() {
        val layout = root ?: return
        val lp = params ?: return
        val dm = resources.displayMetrics
        val viewW = layout.width.takeIf { it > 0 } ?: return
        val viewH = layout.height.takeIf { it > 0 } ?: return
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH).coerceAtLeast(0)
        val clampedX = lp.x.coerceIn(0, maxX)
        val clampedY = lp.y.coerceIn(0, maxY)
        if (clampedX != lp.x || clampedY != lp.y) {
            lp.x = clampedX
            lp.y = clampedY
            runCatching { windowManager.updateViewLayout(layout, lp) }
        }
    }

    private fun applyExpandedState() {
        val visibility = if (expanded) View.VISIBLE else View.GONE
        if (currentStyle == OverlayStyle.PILL) {
            frameText?.visibility = visibility
        } else {
            graphView?.visibility = if (expanded && showGraph) View.VISIBLE else View.GONE
            rowViews.values.forEach { it.parent?.let { p -> (p as? View)?.visibility = visibility } }
        }
        root?.post { clampToScreen() }
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

        // Rebuild overlay cleanly whenever layout/size/graph settings change
        scope.launch {
            combine(
                settings.overlayStyle.flow,
                settings.overlayRows.flow,
                settings.overlayScale.flow,
                settings.overlayShowGraph.flow,
                settings.overlayGraphHeight.flow
            ) { _, _, _, _, _ -> }.drop(1).collect {
                buildOverlay()
            }
        }

        // Update opacity and click-through in place without rebuilding view
        scope.launch {
            combine(
                settings.overlayOpacity.flow,
                settings.overlayClickThrough.flow
            ) { opacity, clickThrough ->
                opacity to clickThrough
            }.drop(1).collect { (opacity, clickThrough) ->
                val rootLayout = root ?: return@collect
                val lp = params ?: return@collect
                currentOpacity = opacity.coerceIn(0.3f, 1.0f)
                lp.alpha = currentOpacity
                if (clickThrough) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                runCatching { windowManager.updateViewLayout(rootLayout, lp) }
            }
        }
    }

    private fun updateValues(fpsState: FpsState, system: SystemMetrics, settings: AppSettings) {
        val target = settings.fpsThreshold.value
        val fpsVal = if (fpsState.valid) fpsState.fps else 0f

        fpsText?.text = if (fpsState.valid) String.format(Locale.US, "%.1f", fpsVal) else "--"
        fpsText?.setTextColor(OverlayViews.fpsColor(fpsVal, target))

        frameText?.text = if (fpsState.valid) String.format(Locale.US, "%.1f ms", fpsState.frameTimeMs) else ""

        if (fpsState.history.isNotEmpty()) {
            graphView?.setValues(fpsState.history, target.toFloat())
        }

        val appName = (applicationContext as? FrameScopeApp)?.container?.engine?.foregroundApp?.value.orEmpty()
        titleText?.text = if (currentStyle == OverlayStyle.RTSS && appName.isNotBlank()) {
            appName.substringAfterLast('.').uppercase()
        } else {
            "FRAMESCOPE"
        }

        // Support all 11 rows: FPS, FRAME, CPU, CPUF, GPU, TEMP, BAT, POWER, MEM, REFRESH, APP
        rowViews["FPS"]?.text = if (fpsState.valid) String.format(Locale.US, "%.1f", fpsVal) else "--"
        rowViews["FRAME"]?.text = if (fpsState.valid) String.format(Locale.US, "%.1f ms", fpsState.frameTimeMs) else "--"
        rowViews["CPU"]?.text = system.cpuPercent?.let { "${it.roundToInt()}%" } ?: "--"
        rowViews["CPUF"]?.text = system.cpuFreqMHz?.let { "$it MHz" } ?: "--"
        rowViews["GPU"]?.text = system.gpuFreqMHz?.let { "$it MHz" } ?: (system.gpuName?.take(14) ?: "--")
        rowViews["TEMP"]?.text = system.thermalMaxC?.let { String.format(Locale.US, "%.1f°C", it) }
            ?: (system.batteryTempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "--")
        rowViews["BAT"]?.text = system.batteryLevel?.let { "$it%" } ?: "--"
        rowViews["POWER"]?.text = system.powerWatts?.let { String.format(Locale.US, "%.2f W", it) } ?: "--"
        rowViews["MEM"]?.text = if (system.ramUsedGb != null && system.ramTotalGb != null) {
            String.format(Locale.US, "%.1f/%.0fG", system.ramUsedGb, system.ramTotalGb)
        } else system.ramPercent?.let { "$it%" } ?: "--"
        rowViews["REFRESH"]?.text = system.refreshHz?.let { "${it.roundToInt()} Hz" } ?: "--"
        rowViews["APP"]?.text = if (appName.isNotBlank()) appName.substringAfterLast('.') else "--"
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
        private const val CHANNEL_ID = "framescope_overlay_channel"
        private const val NOTIFICATION_ID = 4040
    }
}
