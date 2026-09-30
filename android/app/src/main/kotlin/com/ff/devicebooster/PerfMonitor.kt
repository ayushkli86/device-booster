package com.ff.devicebooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/** A single sample of device load. All rates are 0..1 unless the name says otherwise. */
data class PerfSample(
    val cpuLoad: Float,
    val cpuFreqMHz: Int,
    val cpuMaxMHz: Int,
    val ramUsedPercent: Int,
    val batteryTempC: Float?,
    val batteryPercent: Int,
    val charging: Boolean,
    val coreCount: Int,
)

/**
 * Polls kernel-exposed counters. Everything here is readable by an unprivileged
 * app - no Shizuku required - because these are world-readable sysfs/procfs
 * nodes. Fields that a given ROM does not expose are reported as null rather
 * than faked.
 */
object PerfMonitor {

    private var lastIdle = 0L
    private var lastTotal = 0L
    private var context: Context? = null

    fun sample(context: Context): PerfSample {
        this.context = context.applicationContext
        val (load, ok) = readCpuLoad()
        if (!ok) {
            lastIdle = 0
            lastTotal = 0
        }

        val (current, max) = readCpuFrequency()

        return PerfSample(
            cpuLoad = load,
            cpuFreqMHz = current,
            cpuMaxMHz = max,
            ramUsedPercent = readRamPercent(context),
            batteryTempC = readBatteryTemperature(),
            batteryPercent = readBatteryPercent(),
            charging = isCharging(),
            coreCount = Runtime.getRuntime().availableProcessors(),
        )
    }

    /** Differential read of /proc/stat, matching the `top` calculation. */
    private fun readCpuLoad(): Pair<Float, Boolean> {
        val line = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
            ?: return 0f to false
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 5 || parts[0] != "cpu") return 0f to false

        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return 0f to false

        val idle = values[0] + values[3]
        val total = values.sum()

        // A cold first read has no baseline to diff against, so report 0 and
        // let the next tick produce a real number.
        if (lastTotal == 0L) {
            lastIdle = idle
            lastTotal = total
            return 0f to true
        }

        val idleDelta = idle - lastIdle
        val totalDelta = total - lastTotal
        lastIdle = idle
        lastTotal = total

        if (totalDelta <= 0) return 0f to true
        return ((totalDelta - idleDelta).toFloat() / totalDelta).coerceIn(0f, 1f) to true
    }

    private fun readCpuFrequency(): Pair<Int, Int> {
        var current = 0
        var max = 0
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val cur = readInt("$base/scaling_cur_freq") ?: readInt("$base/cpuinfo_cur_freq")
            val hi = readInt("$base/cpuinfo_max_freq")
            // Report the busiest core, not the average - that is what the user feels.
            if (cur != null && cur > current) current = cur
            if (hi != null && hi > max) max = hi
        }
        if (current > 0) current /= 1000
        if (max > 0) max /= 1000
        return current to max
    }

    private fun readRamPercent(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return 0
        return (((info.totalMem - info.availMem) * 100) / info.totalMem).toInt()
    }

    private fun readBatteryTemperature(): Float? = batteryIntent()
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }
        ?.div(10f)

    private fun readBatteryPercent(): Int {
        val manager = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val fromManager = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (fromManager != null && fromManager >= 0) return fromManager
        return batteryIntent()
            ?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            ?: 0
    }

    private fun isCharging(): Boolean {
        if (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return true
        val manager = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return manager?.isCharging ?: false
    }

    /**
     * The sticky battery broadcast is the only source that works on every ROM.
     * Probing this device showed `/sys/class/power_supply/battery/{capacity,temp}`
     * are not world-readable, so sysfs alone would report a permanent 0%.
     */
    private fun batteryIntent(): Intent? {
        val ctx = context ?: return null
        return runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
    }

    private fun readInt(path: String): Int? =
        readString(path)?.trim()?.toIntOrNull()

    private fun readString(path: String): String? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()
    }.getOrNull()
}
