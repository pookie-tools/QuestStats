package com.example.queststats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var textView: TextView? = null
    private var graph: GraphView? = null
    private var exec: ScheduledExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var collector: MetricsCollector
    private val predictor = Predictor()
    private var server: MiniServer? = null
    private var csv: File? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        collector = MetricsCollector(this)
        startForeground(1, buildNotification())
        if (Settings.canDrawOverlays(this)) buildOverlay()
        if (Prefs.bool(this, Prefs.K_SERVER, false)) {
            server = MiniServer().also { it.start() }
        }
        if (Prefs.bool(this, Prefs.K_CSV, false)) {
            val name = "queststats_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
            csv = File(getExternalFilesDir(null), name).also {
                it.writeText("time_ms,fps,frame_ms,late_frames,cpu_util,cpu_mhz,gpu_util,gpu_mhz,mem_avail_mb,batt_pct,batt_temp_c,batt_ma\n")
            }
        }
        SharedState.running = true
        val interval = Prefs.int(this, Prefs.K_INTERVAL, 1000).toLong()
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ tick() }, 0, interval, TimeUnit.MILLISECONDS)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("qs", "QuestStats", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "qs")
            .setContentTitle("QuestStats running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun buildOverlay() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            val alpha = Prefs.int(this@OverlayService, Prefs.K_OPACITY, 70)
            setBackgroundColor(Color.argb(alpha * 255 / 100, 0, 0, 0))
        }
        val mode = OverlayMode.values()[Prefs.int(this, Prefs.K_MODE, OverlayMode.BOTH.ordinal)]
        if (mode != OverlayMode.GRAPH) {
            textView = TextView(this).apply {
                setTextColor(Color.GREEN)
                textSize = 13f
                typeface = Typeface.MONOSPACE
            }
            box.addView(textView)
        }
        if (mode != OverlayMode.TEXT) {
            graph = GraphView(this).apply {
                maxValue = Prefs.targetFps(this@OverlayService) * 1.25f
            }
            box.addView(graph, LinearLayout.LayoutParams(420, 110))
        }

        val corner = Corner.values()[Prefs.int(this, Prefs.K_CORNER, Corner.TOP_LEFT.ordinal)]
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (corner) {
                Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
                Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            x = 16; y = 16
        }
        wm.addView(box, lp)
        root = box
    }

    private fun fmt(v: Double?, f: String, unit: String = ""): String =
        if (v == null) "N/A" else f.format(v) + unit

    /** Display-only override from the Custom tab. Predictions, CSV and JSON keep the real value. */
    private fun displayFps(real: Double?): Double? =
        if (Prefs.bool(this, Prefs.K_CUSTOM_FPS_ON, false))
            Prefs.float(this, Prefs.K_CUSTOM_FPS, 72f).toDouble()
        else real

    private fun lines(s: Snapshot): List<String> {
        val out = ArrayList<String>()
        for (m in Metric.values()) {
            if (!Prefs.metricOn(this, m)) continue
            out.add(
                when (m) {
                    Metric.FPS -> "FPS: " + fmt(displayFps(s.fps), "%.1f")
                    Metric.FRAME_TIME -> "Frame time: " + fmt(s.frameMs, "%.1f", " ms")
                    Metric.DROPPED -> "Late frames: " + (s.lateFrames?.toString() ?: "N/A")
                    Metric.CPU_UTIL -> "CPU util: " + fmt(s.cpuUtil, "%.0f", "%")
                    Metric.CPU_FREQ -> "CPU freq: " + fmt(s.cpuFreqMhz, "%.0f", " MHz")
                    Metric.CPU_LEVEL -> "CPU level: " + (s.cpuLevel?.toString() ?: "N/A")
                    Metric.GPU_UTIL -> "GPU util: " + fmt(s.gpuUtil, "%.0f", "%")
                    Metric.GPU_FREQ -> "GPU freq: " + fmt(s.gpuFreqMhz, "%.0f", " MHz")
                    Metric.GPU_LEVEL -> "GPU level: " + (s.gpuLevel?.toString() ?: "N/A")
                    Metric.MEMORY -> "Mem free: " + (s.memAvailMb?.let { "$it MB" } ?: "N/A")
                    Metric.BATT_LEVEL -> "Battery: " + (s.battPct?.let { "$it%" + if (s.charging) " (chg)" else "" } ?: "N/A")
                    Metric.BATT_TEMP -> "Batt temp: " + fmt(s.battTempC, "%.1f", " °C")
                    Metric.BATT_CURRENT -> "Batt current: " + fmt(s.battCurrentMa, "%.0f", " mA")
                    Metric.THERMAL -> "Thermal: " + (s.thermalStatus?.toString() ?: "N/A")
                }
            )
        }
        return out
    }

    private fun tick() {
        try {
            val s = collector.sample()
            predictor.add(s)
            val statLines = lines(s)
            val preds = predictor.report(this)
            val stats = statLines.joinToString("\n")
            val predText = if (preds.isEmpty()) "" else "-- Predictions --\n" + preds.joinToString("\n")
            SharedState.statsText = stats
            SharedState.predictionText = predText
            SharedState.json = toJson(s)
            csv?.appendText(
                listOf(
                    s.timeMs, s.fps, s.frameMs, s.lateFrames, s.cpuUtil, s.cpuFreqMhz, s.gpuUtil,
                    s.gpuFreqMhz, s.memAvailMb, s.battPct, s.battTempC, s.battCurrentMa
                ).joinToString(",") { it?.toString() ?: "" } + "\n"
            )
            main.post {
                val overlayPred = Prefs.bool(this, Prefs.K_SHOW_PRED_OVERLAY, true)
                textView?.text = if (overlayPred && predText.isNotEmpty()) stats + "\n\n" + predText else stats
                displayFps(s.fps)?.let { graph?.push(it.toFloat()) }
            }
        } catch (e: Throwable) {
            // keep the loop alive
        }
    }

    private fun toJson(s: Snapshot): String {
        fun n(v: Any?) = v?.toString() ?: "null"
        return "{\"time\":${s.timeMs},\"fps\":${n(s.fps)},\"frameMs\":${n(s.frameMs)}," +
            "\"lateFrames\":${n(s.lateFrames)},\"cpuUtil\":${n(s.cpuUtil)},\"cpuMhz\":${n(s.cpuFreqMhz)}," +
            "\"gpuUtil\":${n(s.gpuUtil)},\"gpuMhz\":${n(s.gpuFreqMhz)},\"memMb\":${n(s.memAvailMb)}," +
            "\"battPct\":${n(s.battPct)},\"battTempC\":${n(s.battTempC)},\"battMa\":${n(s.battCurrentMa)}}"
    }

    override fun onDestroy() {
        SharedState.running = false
        exec?.shutdownNow()
        server?.stop()
        root?.let { try { wm.removeView(it) } catch (e: Throwable) {} }
        super.onDestroy()
    }
}
