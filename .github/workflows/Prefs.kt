package com.example.queststats

import android.content.Context

/** Every metric that can be toggled on/off in the overlay. */
enum class Metric(val label: String, val group: String, val defaultOn: Boolean) {
    FPS("Average FPS", "Performance", true),
    FRAME_TIME("Frame time (ms)", "Performance", true),
    DROPPED("Late / dropped frames", "Performance", true),
    CPU_UTIL("CPU utilization", "CPU", true),
    CPU_FREQ("CPU frequency", "CPU", false),
    CPU_LEVEL("CPU level (approx.)", "CPU", true),
    GPU_UTIL("GPU utilization", "GPU", true),
    GPU_FREQ("GPU frequency", "GPU", false),
    GPU_LEVEL("GPU level (approx.)", "GPU", true),
    MEMORY("Available memory", "System", true),
    BATT_LEVEL("Battery level", "Battery & thermal", true),
    BATT_TEMP("Battery temperature", "Battery & thermal", true),
    BATT_CURRENT("Battery current (mA)", "Battery & thermal", false),
    THERMAL("Thermal status", "Battery & thermal", false)
}

/** Prediction panels (the new feature). */
enum class Prediction(val label: String) {
    THERMAL("Thermal throttle forecast"),
    BATTERY("Battery life forecast"),
    FPS("FPS / frame-drop forecast")
}

enum class OverlayMode(val label: String) { TEXT("Text only"), GRAPH("Graph only"), BOTH("Text + graph") }

enum class Corner(val label: String) {
    TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right")
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("queststats", Context.MODE_PRIVATE)

    fun metricOn(c: Context, m: Metric) = sp(c).getBoolean("m_" + m.name, m.defaultOn)
    fun setMetric(c: Context, m: Metric, v: Boolean) = sp(c).edit().putBoolean("m_" + m.name, v).apply()

    fun predOn(c: Context, p: Prediction) = sp(c).getBoolean("p_" + p.name, true)
    fun setPred(c: Context, p: Prediction, v: Boolean) = sp(c).edit().putBoolean("p_" + p.name, v).apply()

    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()

    fun float(c: Context, k: String, d: Float) = sp(c).getFloat(k, d)
    fun setFloat(c: Context, k: String, v: Float) = sp(c).edit().putFloat(k, v).apply()

    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    /** Custom FPS (0 = off) wins over the 72/90/120 dropdown. */
    fun targetFps(c: Context): Int {
        val custom = int(c, K_CUSTOM_FPS, 0)
        return if (custom > 0) custom else int(c, K_TARGET_FPS, 72)
    }

    // Keys
    const val K_CUSTOM_FPS = "custom_fps"      // 0 = off
    const val K_MODE = "overlay_mode"          // ordinal of OverlayMode
    const val K_CORNER = "overlay_corner"      // ordinal of Corner
    const val K_OPACITY = "overlay_opacity"    // 20..100
    const val K_INTERVAL = "interval_ms"       // 500 / 1000 / 2000
    const val K_TARGET_PKG = "target_pkg"      // app to measure FPS for
    const val K_TARGET_FPS = "target_fps"      // 72 / 90 / 120
    const val K_TEMP_LIMIT = "temp_limit_c"    // thermal forecast threshold
    const val K_CSV = "csv_logging"
    const val K_SERVER = "http_server"
    const val K_SHOW_PRED_OVERLAY = "pred_in_overlay"
    const val K_CUSTOM_FPS_ON = "custom_fps_on"    // display-only FPS override
    const val K_CUSTOM_FPS = "custom_fps_value"
}
