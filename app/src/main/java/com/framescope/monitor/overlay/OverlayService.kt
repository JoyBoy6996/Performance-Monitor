package com.framescope.monitor.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.framescope.monitor.FrameScopeApp
import com.framescope.monitor.MainActivity
import com.framescope.monitor.R
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.OverlayStyle
import com.framescope.monitor.core.SystemMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Floating overlay that shows live FPS and device telemetry on top of any app.
 *
 * Plain Android views are used on purpose: they cost almost nothing to draw,
 * which matters when the phone is already rendering a game at 120 fps.
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
    private var builtRows = ""
    private var currentStyle = OverlayStyle.CLASSIC
    private var currentScale = 1f
    private var graphVisible = true
    private var graphHeight = 54

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        buildOverlay()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOverlay() {
        val app = applicationContext as FrameScopeApp
        val settings = app.container.settings
        val style = runCatching {
            OverlayStyle.valueOf(settings.overlayStyle.value)
        }.getOrDefault(OverlayStyle.CLASSIC)

        if (style == OverlayStyle.PILL) {
            buildPill(settings)
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = OverlayViews.dp(this@OverlayService, 10f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = OverlayViews.dp(this@OverlayService, 14f).toFloat()
                setColor(Color.parseColor("#E60B1020"))
                setStroke(OverlayViews.dp(this@OverlayService, 1f), Color.parseColor("#33FFFFFF"))
            }
        }

        titleText = OverlayViews.label(this, "FRAMESCOPE", Color.parseColor("#64748B")).apply {
            textSize = 8f
        }
        layout.addView(titleText)

        val header = OverlayViews.row(this)
        fpsText = OverlayViews.value(this, "--", Color.parseColor("#4ADE80"), 26f)
        frameText = OverlayViews.value(this, "", Color.parseColor("#CBD5E1"), 12f)
        header.addView(fpsText)
        header.addView(
            frameText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        layout.addView(header)

        graphView = GraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                OverlayViews.dp(this@OverlayService, graphHeight.toFloat())
            ).apply { topMargin = OverlayViews.dp(this@OverlayService, 4f) }
        }
        layout.addView(graphView)

        val rows = settings.overlayRows.value.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        builtRows = settings.overlayRows.value
        for (row in rows) {
            val rowView = OverlayViews.row(this)
            val label = OverlayViews.label(this, row, Color.parseColor("#7DD3FC"))
            rowView.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            val value = OverlayViews.value(this, "--", Color.parseColor("#E2E8F0"), 12f)
            rowView.addView(value)
            rowViews[row] = value
            rowLabels[row] = label
            layout.addView(rowView)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (settings.overlayX.value * resources.displayMetrics.widthPixels).toInt()
            y = (settings.overlayY.value * resources.displayMetrics.heightPixels).toInt()
            alpha = settings.overlayOpacity.value
        }
        params = lp

        layout.setOnTouchListener { _, event ->
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
                    if (!dragging && (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(layout, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        val app = applicationContext as FrameScopeApp
                        app.container.settings.overlayX.value =
                            (lp.x.toFloat() / metrics.widthPixels).coerceIn(0f, 1f)
                        app.container.settings.overlayY.value =
                            (lp.y.toFloat() / metrics.heightPixels).coerceIn(0f, 1f)
                    } else {
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }

        root = layout
        runCatching { windowManager.addView(layout, lp) }
    }

    /**
     * The pill: one number, one unit, nothing else.
     *
     * This is the meter you want on top of a game - small enough to ignore,
     * big enough to read at a glance. A tap adds the frame time next to it.
     */
    private fun buildPill(settings: com.framescope.monitor.core.AppSettings) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = OverlayViews.dp(this@OverlayService, 12f)
            val padV = OverlayViews.dp(this@OverlayService, 5f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = OverlayViews.dp(this@OverlayService, 100f).toFloat()
                setColor(Color.parseColor("#D9070C16"))
                setStroke(OverlayViews.dp(this@OverlayService, 1f), Color.parseColor("#33FFFFFF"))
            }
        }

        fpsText = OverlayViews.value(this, "--", Color.parseColor("#4ADE80"), 15f)
        frameText = OverlayViews.value(this, "", Color.parseColor("#94A3B8"), 10f)
        unitText = OverlayViews.label(this, "FPS", Color.parseColor("#64748B")).apply {
            textSize = 8f
            includeFontPadding = false
        }

        layout.addView(fpsText)
        layout.addView(
            unitText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = OverlayViews.dp(this@OverlayService, 3f) }
        )
        layout.addView(
            frameText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = OverlayViews.dp(this@OverlayService, 6f) }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (settings.overlayX.value * resources.displayMetrics.widthPixels).toInt()
            y = (settings.overlayY.value * resources.displayMetrics.heightPixels).toInt()
            alpha = settings.overlayOpacity.value
        }
        params = lp

        layout.setOnTouchListener { _, event ->
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
                    if (!dragging && (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(layout, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        val app = applicationContext as FrameScopeApp
                        app.container.settings.overlayX.value =
                            (lp.x.toFloat() / metrics.widthPixels).coerceIn(0f, 1f)
                        app.container.settings.overlayY.value =
                            (lp.y.toFloat() / metrics.heightPixels).coerceIn(0f, 1f)
                    } else {
                        toggleExpanded()
                    }
                    true
                }
                else -> false
            }
        }

        root = layout
        runCatching { windowManager.addView(layout, lp) }
    }

    /** Rebuilds the overlay so row changes are applied without a restart. */
    private fun rebuild() {
        val app = applicationContext as FrameScopeApp
        builtRows = app.container.settings.overlayRows.value
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        rowViews.clear()
        rowLabels.clear()
        unitText = null
        buildOverlay()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        applyStyle()
    }

    private fun observe() {
        val app = applicationContext as FrameScopeApp
        val engine = app.container.engine
        val settings = app.container.settings

        val extra = combine(settings.overlayRows.flow, settings.overlayOpacity.flow) { rows, opacity ->
            rows to opacity
        }
        val prefs = combine(
            settings.overlayStyle.flow.map { runCatching { OverlayStyle.valueOf(it) }.getOrDefault(OverlayStyle.CLASSIC) },
            settings.overlayScale.flow,
            settings.overlayShowGraph.flow,
            settings.overlayGraphHeight.flow,
            extra
        ) { style, scale, showGraph, height, extras ->
            OverlayPrefs(style, scale, showGraph, height, extras.first, extras.second)
        }

        scope.launch {
            combine(engine.fps, engine.system, prefs, engine.protectedForeground) { fps, system, p, protected ->
                Quad(fps, system, p, protected)
            }.collect { quad ->
                if (quad.protected) {
                    root?.visibility = View.GONE
                } else {
                    root?.visibility = View.VISIBLE
                    params?.alpha = quad.prefs.opacity
                    if (quad.prefs.rows != builtRows) {
                        rebuild()
                    }
                    if (quad.prefs.style != currentStyle) {
                        currentStyle = quad.prefs.style
                        rebuild()
                        return@collect
                    }
                    currentScale = quad.prefs.scale
                    graphVisible = quad.prefs.showGraph
                    graphHeight = quad.prefs.height
                    applyStyle()
                    render(quad.fps, quad.system)
                }
            }
        }
    }

    /** Applies style, scale and graph preferences to the overlay widgets. */
    private fun applyStyle() {
        if (currentStyle == OverlayStyle.PILL) {
            titleText?.visibility = View.GONE
            frameText?.visibility = if (expanded) View.VISIBLE else View.GONE
            graphView?.visibility = View.GONE
            unitText?.visibility = View.VISIBLE
            rowViews.values.forEach { it.visibility = View.GONE }
            rowLabels.values.forEach { it.visibility = View.GONE }
            fpsText?.textSize = 15f * currentScale
            frameText?.textSize = 10f * currentScale
            unitText?.textSize = 8f * currentScale
            return
        }

        val showRows = expanded && currentStyle != OverlayStyle.COMPACT
        val showFrame = expanded && currentStyle != OverlayStyle.COMPACT
        val showGraph = expanded && graphVisible && currentStyle != OverlayStyle.RTSS
        val showTitle = currentStyle == OverlayStyle.RTSS || currentStyle == OverlayStyle.DETAILED

        titleText?.visibility = if (showTitle) View.VISIBLE else View.GONE
        frameText?.visibility = if (showFrame) View.VISIBLE else View.GONE
        graphView?.visibility = if (showGraph) View.VISIBLE else View.GONE
        rowViews.values.forEach { it.visibility = if (showRows) View.VISIBLE else View.GONE }
        rowLabels.forEach { (key, view) ->
            view.visibility = if (showRows) View.VISIBLE else View.GONE
            if (currentStyle == OverlayStyle.RTSS) {
                view.text = key
                view.setTextColor(RTSS_COLORS[key] ?: Color.parseColor("#7DD3FC"))
            }
        }

        fpsText?.textSize = 26f * currentScale
        frameText?.textSize = 12f * currentScale
        rowViews.values.forEach { it.textSize = 12f * currentScale }
        rowLabels.values.forEach { it.textSize = 9f * currentScale }

        graphView?.layoutParams = graphView?.layoutParams?.apply {
            height = OverlayViews.dp(this@OverlayService, graphHeight.toFloat())
        }
    }

    private fun render(fps: FpsState, system: SystemMetrics) {
        val target = (applicationContext as FrameScopeApp).container.settings.fpsThreshold.value
        fpsText?.text = if (fps.valid) String.format("%.0f", fps.fps) else "--"
        fpsText?.setTextColor(OverlayViews.fpsColor(fps.fps, target))
        frameText?.text = if (fps.valid) "${String.format("%.1f", fps.frameTimeMs)} ms" else ""

        graphView?.setValues(fps.history, target.toFloat())

        for ((key, view) in rowViews) {
            view.text = when (key) {
                "FPS" -> if (fps.valid) String.format("%.0f", fps.fps) else "--"
                "FRAME" -> if (fps.valid) "${String.format("%.1f", fps.frameTimeMs)} ms" else "--"
                "CPU" -> system.cpuPercent?.let { "${it.toInt()}%" } ?: "--"
                "CPUF" -> system.cpuFreqMHz?.let { "$it MHz" } ?: "--"
                "GPU" -> system.gpuFreqMHz?.let { "$it MHz" } ?: system.gpuName?.let { it.take(14) } ?: "--"
                "TEMP" -> system.thermalMaxC?.let { "${it.toInt()}°" } ?: "--"
                "BAT" -> system.batteryLevel?.let { "$it%" } ?: "--"
                "POWER" -> system.powerWatts?.let { "${String.format("%.2f", it)} W" } ?: "--"
                "MEM" -> system.ramPercent?.let { "$it%" } ?: "--"
                "REFRESH" -> system.refreshHz?.let { "${it.toInt()} Hz" } ?: "--"
                "APP" -> appLabel()
                else -> "--"
            }
        }
        titleText?.text = if (currentStyle == OverlayStyle.RTSS) appLabel().uppercase() else "FRAMESCOPE"
    }

    private fun appLabel(): String {
        val pkg = (applicationContext as FrameScopeApp).container.engine.foregroundApp.value
        if (pkg.isNullOrBlank()) return "--"
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.overlay_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Live FPS overlay is running")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private data class OverlayPrefs(
        val style: OverlayStyle,
        val scale: Float,
        val showGraph: Boolean,
        val height: Int,
        val rows: String,
        val opacity: Float
    )

    private data class Quad(
        val fps: FpsState,
        val system: SystemMetrics,
        val prefs: OverlayPrefs,
        val protected: Boolean
    )

    companion object {
        const val ACTION_STOP = "com.framescope.monitor.STOP_OVERLAY"
        private const val CHANNEL_ID = "framescope_overlay"
        private const val NOTIFICATION_ID = 4711

        private val RTSS_COLORS = mapOf(
            "GPU" to Color.parseColor("#4ADE80"),
            "CPU" to Color.parseColor("#38BDF8"),
            "TEMP" to Color.parseColor("#22D3EE"),
            "BAT" to Color.parseColor("#FBBF24"),
            "MEM" to Color.parseColor("#A78BFA"),
            "FPS" to Color.parseColor("#FB7185")
        )
    }
}
