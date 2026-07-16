package com.corewatch.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

/** A single per-core / cluster CPU clock reading. */
data class CpuClock(
    /** True when live per-core frequency was readable; false means we fell back to the range. */
    val live: Boolean,
    /** Highest current core frequency in MHz (live mode only). */
    val currentMaxMhz: Int?,
    /** Per-core current frequency in MHz (live mode only). */
    val perCoreMhz: List<Int>,
    /** Cluster minimum frequency in MHz (usually readable even when live is blocked). */
    val minMhz: Int?,
    /** Cluster maximum frequency in MHz. */
    val maxMhz: Int?,
    val cores: Int,
) {
    companion object {
        val EMPTY = CpuClock(false, null, emptyList(), null, null, 0)
    }
}

enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class Plug { AC, USB, WIRELESS, NONE }

/** Battery state for a single sample. */
data class BatteryInfo(
    val tempC: Float?,
    val status: ChargeStatus,
    val plug: Plug,
    val levelPct: Int?,
    /** Instantaneous current in mA, signed: `+` = into the battery (charging), `-` = draining.
     *  `null` when the device doesn't expose CURRENT_NOW. */
    val currentMa: Int?,
) {
    val isCharging: Boolean get() = status == ChargeStatus.CHARGING
    companion object {
        val EMPTY = BatteryInfo(null, ChargeStatus.UNKNOWN, Plug.NONE, null, null)
    }
}

/** Snapshot of the values that refresh every second. */
data class LiveMetrics(
    val cpu: CpuClock,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val battery: BatteryInfo,
) {
    companion object {
        val EMPTY = LiveMetrics(CpuClock.EMPTY, 0L, 0L, BatteryInfo.EMPTY)
    }
}

/**
 * Reads the live hardware metrics. All reads are cheap but touch the filesystem / system
 * services, so callers should invoke [sample] off the main thread.
 */
class MetricsReader(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager by lazy {
        appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    /** Number of logical CPUs, discovered from sysfs with a Runtime fallback. */
    val coreCount: Int = run {
        val dirs = File("/sys/devices/system/cpu")
            .listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu[0-9]+")) }
            ?.size
        if (dirs != null && dirs > 0) dirs else Runtime.getRuntime().availableProcessors()
    }

    fun sample(): LiveMetrics {
        val (used, total) = readRam()
        return LiveMetrics(
            cpu = readCpuClock(),
            ramUsedBytes = used,
            ramTotalBytes = total,
            battery = readBattery(),
        )
    }

    /** @return used bytes to total bytes. */
    private fun readRam(): Pair<Long, Long> {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        val used = (total - info.availMem).coerceAtLeast(0L)
        return used to total
    }

    private fun readBattery(): BatteryInfo {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tenths == Int.MIN_VALUE) null else tenths / 10f

        val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            else -> ChargeStatus.UNKNOWN
        }

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plug = when {
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> Plug.AC
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> Plug.USB
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> Plug.WIRELESS
            else -> Plug.NONE
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPct = if (level >= 0 && scale > 0) level * 100 / scale else null

        // CURRENT_NOW is µA; its sign is device-specific, so derive direction from status instead.
        val rawMicroAmp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (rawMicroAmp == Int.MIN_VALUE) {
            null
        } else {
            val mag = abs(rawMicroAmp) / 1000
            if (status == ChargeStatus.DISCHARGING) -mag else mag
        }

        return BatteryInfo(tempC, status, plug, levelPct, currentMa)
    }

    fun readCpuClock(): CpuClock {
        val perCore = ArrayList<Int>(coreCount)
        var maxKHz = 0L
        var minKHz = Long.MAX_VALUE
        for (i in 0 until coreCount) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            readFreqKHz("$base/scaling_cur_freq")?.let { perCore.add((it / 1000).toInt()) }
            readFreqKHz("$base/cpuinfo_max_freq")?.let { if (it > maxKHz) maxKHz = it }
            readFreqKHz("$base/cpuinfo_min_freq")?.let { if (it < minKHz) minKHz = it }
        }
        val live = perCore.isNotEmpty()
        return CpuClock(
            live = live,
            currentMaxMhz = perCore.maxOrNull(),
            perCoreMhz = perCore,
            minMhz = if (minKHz != Long.MAX_VALUE) (minKHz / 1000).toInt() else null,
            maxMhz = if (maxKHz > 0) (maxKHz / 1000).toInt() else null,
            cores = coreCount,
        )
    }

    /** Reads a cpufreq node in kHz, rejecting implausible values (e.g. emulator placeholders). */
    private fun readFreqKHz(path: String): Long? =
        readSysLong(path)?.takeIf { it >= MIN_PLAUSIBLE_KHZ }

    private fun readSysLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (_: Exception) {
        // Expected on modern Android where SELinux blocks these sysfs nodes for apps.
        null
    }

    private companion object {
        // 50 MHz floor: real cores idle far above this; filters emulator junk like "1"/"2" kHz.
        const val MIN_PLAUSIBLE_KHZ = 50_000L
    }
}
