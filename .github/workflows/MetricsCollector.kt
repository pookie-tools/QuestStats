package com.example.queststats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class Snapshot(
    val timeMs: Long,
    val fps: Double?,
    val frameMs: Double?,
    val lateFrames: Int?,
    val cpuUtil: Double?,
    val cpuFreqMhz: Double?,
    val cpuLevel: Int?,
    val gpuUtil: Double?,
    val gpuFreqMhz: Double?,
    val gpuLevel: Int?,
    val memAvailMb: Long?,
    val battPct: Int?,
    val battTempC: Double?,
    val battCurrentMa: Double?,
    val charging: Boolean,
    val thermalStatus: Int?
)

/**
 * Reads whatever the device exposes to a normal app. Anything blocked by SELinux or a missing
 * permission comes back as null and is shown as "N/A" (see README for the adb setup).
 */
class MetricsCollector(private val ctx: Context) {

    private var lastCpu: LongArray? = null
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lastGpuBusy: LongArray? = null
    private var layerName: String? = null
    private var layerLookupAt = 0L

    fun sample(): Snapshot {
        val fpsInfo = readFps()
        val cpuFreq = readCpuFreqMhz()
        val cpuMax = readCpuMaxMhz()
        val gpuFreq = readGpuFreqMhz()
        val gpuMax = readGpuMaxMhz()

        val battIntent: Intent? =
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        return Snapshot(
            timeMs = System.currentTimeMillis(),
            fps = fpsInfo?.first,
            frameMs = fpsInfo?.second,
            lateFrames = fpsInfo?.third,
            cpuUtil = readCpuUtil(),
            cpuFreqMhz = cpuFreq,
            cpuLevel = levelOf(cpuFreq, cpuMax),
            gpuUtil = readGpuUtil(),
            gpuFreqMhz = gpuFreq,
            gpuLevel = levelOf(gpuFreq, gpuMax),
            memAvailMb = mi.availMem / (1024 * 1024),
            battPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            battTempC = if (temp != null && temp != Int.MIN_VALUE) temp / 10.0 else null,
            battCurrentMa = if (currentUa != Long.MIN_VALUE && currentUa != 0L) currentUa / 1000.0 else null,
            charging = charging,
            thermalStatus = try { pm.currentThermalStatus } catch (e: Throwable) { null }
        )
    }

    // ---------- helpers ----------

    private fun readText(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Throwable) { null }

    /** Oculus' real CPU/GPU "levels" are internal; this maps current/max frequency onto 0..5. */
    private fun levelOf(cur: Double?, max: Double?): Int? {
        if (cur == null || max == null || max <= 0) return null
        return Math.round(cur / max * 5.0).toInt().coerceIn(0, 5)
    }

    private fun readCpuFreqMhz(): Double? {
        val vals = ArrayList<Double>()
        for (i in 0 until 8) {
            val v = readText("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toDoubleOrNull()
            if (v != null) vals.add(v / 1000.0)
        }
        return if (vals.isEmpty()) null else vals.maxOrNull()
    }

    private fun readCpuMaxMhz(): Double? {
        val vals = ArrayList<Double>()
        for (i in 0 until 8) {
            val v = readText("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toDoubleOrNull()
            if (v != null) vals.add(v / 1000.0)
        }
        return vals.maxOrNull()
    }

    private fun readCpuUtil(): Double? {
        val line = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (e: Throwable) { return null } ?: return null
        val p = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (p.size < 4) return null
        val idle = p[3] + (p.getOrNull(4) ?: 0L)
        val total = p.sum()
        val prev = lastCpu
        lastCpu = longArrayOf(idle, total)
        if (prev == null) return null
        val dTotal = total - prev[1]
        val dIdle = idle - prev[0]
        return if (dTotal <= 0) null else (100.0 * (dTotal - dIdle) / dTotal)
    }

    // Quest 2 uses an Adreno 650 (kgsl driver). These nodes are often SELinux-blocked: null is normal.
    private fun readGpuFreqMhz(): Double? {
        val hz = readText("/sys/class/kgsl/kgsl-3d0/gpuclk")?.toDoubleOrNull()
        if (hz != null) return hz / 1_000_000.0
        val khz = readText("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")?.toDoubleOrNull()
        return khz?.let { it / 1_000_000.0 }
    }

    private fun readGpuMaxMhz(): Double? {
        val hz = readText("/sys/class/kgsl/kgsl-3d0/max_gpuclk")?.toDoubleOrNull()
        return hz?.let { it / 1_000_000.0 }
    }

    private fun readGpuUtil(): Double? {
        val pct = readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            ?.replace("%", "")?.trim()?.toDoubleOrNull()
        if (pct != null) return pct
        val parts = readText("/sys/class/kgsl/kgsl-3d0/gpubusy")?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.size < 2) return null
        val prev = lastGpuBusy
        lastGpuBusy = longArrayOf(parts[0], parts[1])
        if (prev == null) return null
        val dTotal = parts[1] - prev[1]
        return if (dTotal <= 0) null else 100.0 * (parts[0] - prev[0]) / dTotal
    }

    // ---------- FPS via SurfaceFlinger (needs the DUMP permission granted over adb) ----------

    private fun runDumpsys(vararg args: String): List<String>? = try {
        val p = ProcessBuilder(listOf("dumpsys") + args).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(p.inputStream)).use { it.readLines() }
        p.waitFor(2, TimeUnit.SECONDS)
        lines
    } catch (e: Throwable) { null }

    private fun findLayer(): String? {
        val now = System.currentTimeMillis()
        if (layerName != null && now - layerLookupAt < 10_000) return layerName
        layerLookupAt = now
        val layers = runDumpsys("SurfaceFlinger", "--list") ?: return layerName
        val target = Prefs.str(ctx, Prefs.K_TARGET_PKG, "").trim()
        layerName = if (target.isNotEmpty()) {
            layers.lastOrNull { it.contains(target) }
        } else {
            layers.lastOrNull {
                it.contains("com.") && !it.contains("systemui", true) &&
                    !it.contains("queststats", true) && !it.contains("Background", true)
            }
        } ?: layerName
        return layerName
    }

    /** Returns (fps over the last ~1s, avg frame ms, late frames in that window) or null. */
    private fun readFps(): Triple<Double, Double, Int>? {
        val layer = findLayer() ?: return null
        val lines = runDumpsys("SurfaceFlinger", "--latency", layer) ?: return null
        if (lines.size < 3) return null
        val refreshNs = lines[0].trim().toLongOrNull() ?: return null
        val times = lines.drop(1).mapNotNull {
            val cols = it.trim().split(Regex("\\s+"))
            if (cols.size < 3) null else cols[1].toLongOrNull()
        }.filter { it > 0 && it != Long.MAX_VALUE }.distinct().sorted()
        if (times.size < 3) return null
        val windowNs = 1_000_000_000L
        val recent = times.filter { it >= times.last() - windowNs }
        if (recent.size < 2) return null
        val span = (recent.last() - recent.first()).toDouble()
        if (span <= 0) return null
        val frames = recent.size - 1
        val fps = frames * 1e9 / span
        val avgMs = span / frames / 1e6
        var late = 0
        for (i in 1 until recent.size) {
            if (recent[i] - recent[i - 1] > refreshNs * 1.5) late++
        }
        return Triple(fps, avgMs, late)
    }
}
