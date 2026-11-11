package com.example.queststats

import android.content.Context
import android.os.PowerManager

/**
 * Trend-based forecasts from the rolling sample history (least-squares line fit).
 * These are estimates: real sessions are bursty, so treat them as a heads-up, not a guarantee.
 */
class Predictor(private val maxSamples: Int = 240) {

    private class Pt(val t: Double, val v: Double)

    private val temp = ArrayList<Pt>()
    private val batt = ArrayList<Pt>()
    private val fps = ArrayList<Pt>()
    private val util = ArrayList<Pt>()
    private var charging = false
    private var lastTemp: Double? = null
    private var lastBatt: Int? = null
    private var lastFps: Double? = null
    private var thermalStatus: Int? = null

    @Synchronized
    fun add(s: Snapshot) {
        val t = s.timeMs / 1000.0
        charging = s.charging
        thermalStatus = s.thermalStatus
        s.battTempC?.let { push(temp, Pt(t, it)); lastTemp = it }
        s.battPct?.let { push(batt, Pt(t, it.toDouble())); lastBatt = it }
        s.fps?.let { push(fps, Pt(t, it)); lastFps = it }
        val u = listOfNotNull(s.cpuUtil, s.gpuUtil).maxOrNull()
        if (u != null) push(util, Pt(t, u))
    }

    private fun push(list: ArrayList<Pt>, p: Pt) {
        list.add(p)
        while (list.size > maxSamples) list.removeAt(0)
    }

    /** slope (units/sec) and intercept, or null when there isn't enough data yet. */
    private fun fit(pts: List<Pt>): Pair<Double, Double>? {
        if (pts.size < 8) return null
        val mx = pts.map { it.t }.average()
        val my = pts.map { it.v }.average()
        var sxx = 0.0
        var sxy = 0.0
        for (p in pts) {
            sxx += (p.t - mx) * (p.t - mx)
            sxy += (p.t - mx) * (p.v - my)
        }
        if (sxx < 1e-9) return null
        val m = sxy / sxx
        return Pair(m, my - m * mx)
    }

    private fun eta(seconds: Double): String {
        val mins = (seconds / 60.0).toInt()
        return when {
            mins >= 240 -> "more than 4 h"
            mins >= 60 -> "${mins / 60} h ${mins % 60} min"
            mins >= 1 -> "$mins min"
            else -> "under a minute"
        }
    }

    @Synchronized
    fun report(ctx: Context): List<String> {
        val out = ArrayList<String>()
        if (Prefs.predOn(ctx, Prediction.THERMAL)) out.add(thermal(ctx))
        if (Prefs.predOn(ctx, Prediction.BATTERY)) out.add(battery())
        if (Prefs.predOn(ctx, Prediction.FPS)) out.add(fpsForecast(ctx))
        return out
    }

    private fun thermal(ctx: Context): String {
        val limit = Prefs.float(ctx, Prefs.K_TEMP_LIMIT, 43f).toDouble()
        val cur = lastTemp ?: return "Thermal: no temperature data"
        val f = fit(temp) ?: return "Thermal: collecting data..."
        val slopePerMin = f.first * 60.0
        val status = when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ""
            null -> ""
            else -> " (system reports throttling)"
        }
        return when {
            cur >= limit -> "Thermal: already at/over %.1f°C limit%s".format(limit, status)
            slopePerMin > 0.02 -> {
                val secs = (limit - cur) / f.first
                "Thermal: %.1f°C, +%.2f°C/min -> %.0f°C in ~%s%s".format(cur, slopePerMin, limit, eta(secs), status)
            }
            slopePerMin < -0.02 -> "Thermal: %.1f°C and cooling (%.2f°C/min)%s".format(cur, slopePerMin, status)
            else -> "Thermal: %.1f°C, stable (no throttle expected)%s".format(cur, status)
        }
    }

    private fun battery(): String {
        val cur = lastBatt ?: return "Battery: no data"
        val f = fit(batt) ?: return "Battery: collecting data..."
        val perHour = f.first * 3600.0
        return if (charging) {
            if (perHour > 0.5) {
                val secs = (100 - cur) / f.first
                "Battery: %d%%, charging +%.0f%%/h -> full in ~%s".format(cur, perHour, eta(secs))
            } else "Battery: %d%%, charging".format(cur)
        } else {
            if (perHour < -0.5) {
                val secs = cur / -f.first
                val in30 = (cur + f.first * 1800.0).coerceIn(0.0, 100.0)
                "Battery: %d%%, drain %.0f%%/h -> empty in ~%s (%.0f%% in 30 min)".format(cur, -perHour, eta(secs), in30)
            } else "Battery: %d%%, drain too slow to estimate".format(cur)
        }
    }

    private fun fpsForecast(ctx: Context): String {
        val target = Prefs.targetFps(ctx).toDouble()
        val cur = lastFps ?: return "FPS: no frame data (grant DUMP via adb, see README)"
        val recent = fps.takeLast(30)
        val f = fit(fps.takeLast(60)) ?: return "FPS: collecting data..."
        val predicted = (f.first * 30.0 + cur).coerceIn(0.0, target + 5)
        val below = recent.count { it.v < target * 0.9 }.toDouble() / recent.size.coerceAtLeast(1)
        val uf = fit(util.takeLast(60))
        val rising = uf != null && uf.first * 30.0 > 5.0
        val hot = (util.lastOrNull()?.v ?: 0.0) > 90.0
        var score = below * 2.0
        if (f.first * 30.0 < -5.0) score += 0.7
        if (hot) score += 0.6
        if (rising) score += 0.3
        val risk = when {
            score >= 1.2 -> "HIGH"
            score >= 0.5 -> "medium"
            else -> "low"
        }
        return "FPS: %.0f now, ~%.0f in 30 s, drop risk %s".format(cur, predicted, risk)
    }
}
