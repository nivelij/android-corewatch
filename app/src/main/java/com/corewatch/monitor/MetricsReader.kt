package com.corewatch.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
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
    /** Per-core busy fraction 0..1 from /proc/stat; `NaN` per core if unknown. Often readable even
     *  when the clock nodes are SELinux-blocked. Empty on the first sample (needs a delta). */
    val perCoreLoad: List<Float>,
    /** Cluster minimum frequency in MHz (usually readable even when live is blocked). */
    val minMhz: Int?,
    /** Cluster maximum frequency in MHz. */
    val maxMhz: Int?,
    val cores: Int,
) {
    companion object {
        val EMPTY = CpuClock(false, null, emptyList(), emptyList(), null, null, 0)
    }
}

enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class Plug { AC, USB, WIRELESS, NONE }

enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN }

/** Thermal throttling severity, mirroring PowerManager.THERMAL_STATUS_*. */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

/** Device thermal state for a single sample. */
data class ThermalInfo(
    val status: ThermalStatus,
    /** Thermal headroom toward the throttling threshold: ~0 = cool, 1.0 = at threshold, >1 =
     *  throttling. `null` when the platform can't report it. */
    val headroom: Float?,
) {
    companion object {
        val EMPTY = ThermalInfo(ThermalStatus.UNKNOWN, null)
    }
}

/** Battery state for a single sample. */
data class BatteryInfo(
    val tempC: Float?,
    val status: ChargeStatus,
    val plug: Plug,
    val levelPct: Int?,
    /** Instantaneous current in mA, signed: `+` = into the battery (charging), `-` = draining.
     *  `null` when the device doesn't expose CURRENT_NOW. */
    val currentMa: Int?,
    /** Battery voltage in millivolts, or `null` when unavailable. */
    val voltageMv: Int?,
    val health: BatteryHealth,
) {
    val isCharging: Boolean get() = status == ChargeStatus.CHARGING

    /** Instantaneous power in watts (V × A), signed like [currentMa]; `null` if either is missing. */
    val powerW: Float?
        get() = if (voltageMv != null && currentMa != null) voltageMv * currentMa / 1_000_000f else null

    /** Running on battery (nothing plugged in) — the only state where power is real consumption. */
    val onBattery: Boolean get() = plug == Plug.NONE

    /** Power *draw* in watts, always ≥ 0; `null` while charging/plugged (draw is N/A then) or if
     *  voltage/current is unavailable. This is the consumption metric the charts and tiles show. */
    val drawW: Float?
        get() = if (onBattery) powerW?.let { abs(it) } else null

    companion object {
        val EMPTY = BatteryInfo(null, ChargeStatus.UNKNOWN, Plug.NONE, null, null, null, BatteryHealth.UNKNOWN)
    }
}

/** Snapshot of the values that refresh every second. */
data class LiveMetrics(
    val cpu: CpuClock,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val battery: BatteryInfo,
    val thermal: ThermalInfo,
) {
    companion object {
        val EMPTY = LiveMetrics(CpuClock.EMPTY, 0L, 0L, BatteryInfo.EMPTY, ThermalInfo.EMPTY)
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
    private val powerManager: PowerManager by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    // Previous /proc/stat per-core counters, for computing busy fraction between samples.
    private var prevCpuTotals: LongArray? = null
    private var prevCpuIdles: LongArray? = null

    // Cached thermal headroom — getThermalHeadroom returns NaN if polled faster than ~1/s, so we
    // query it at most every HEADROOM_QUERY_INTERVAL_MS and reuse the last good value in between.
    private var lastHeadroom: Float? = null
    private var lastHeadroomAtMs: Long = 0L

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
            cpu = readCpuClock().copy(perCoreLoad = readCpuLoad()),
            ramUsedBytes = used,
            ramTotalBytes = total,
            battery = readBattery(),
            thermal = readThermal(),
        )
    }

    /**
     * Per-core busy fraction (0..1) from /proc/stat, as a delta since the previous call. Returns
     * an empty list on the very first call (no baseline yet). Synchronized because [sample] is
     * invoked from more than one coroutine.
     */
    @Synchronized
    private fun readCpuLoad(): List<Float> {
        val lines = try {
            File("/proc/stat").readLines()
        } catch (_: Exception) {
            return emptyList()
        }
        val totals = LongArray(coreCount) { -1L }
        val idles = LongArray(coreCount) { -1L }
        for (line in lines) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(Regex("\\s+"))
            val idx = parts[0].removePrefix("cpu").toIntOrNull() ?: continue // skips the "cpu" total
            if (idx !in 0 until coreCount) continue
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 5) continue
            idles[idx] = nums[3] + nums[4]      // idle + iowait
            totals[idx] = nums.sum()
        }
        val prevT = prevCpuTotals
        val prevI = prevCpuIdles
        prevCpuTotals = totals
        prevCpuIdles = idles
        if (prevT == null || prevI == null) return emptyList()
        return (0 until coreCount).map { i ->
            if (totals[i] < 0 || prevT[i] < 0) return@map Float.NaN
            val dTotal = totals[i] - prevT[i]
            val dIdle = idles[i] - prevI[i]
            if (dTotal <= 0) 0f else ((dTotal - dIdle).toFloat() / dTotal).coerceIn(0f, 1f)
        }
    }

    @Synchronized
    private fun readThermal(): ThermalInfo {
        val status = when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }
        // Only re-query once the rate-limit window has passed; otherwise keep the last good value
        // so the UI gauge stays put instead of blinking to null between samples.
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadroomAtMs >= HEADROOM_QUERY_INTERVAL_MS) {
            lastHeadroomAtMs = now
            val hr = runCatching { powerManager.getThermalHeadroom(0) }.getOrDefault(Float.NaN)
            if (!hr.isNaN() && hr >= 0f) lastHeadroom = hr
        }
        return ThermalInfo(status, lastHeadroom)
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

        // CURRENT_NOW units aren't standardized: AOSP documents µA (Ayn Odin, most devices), but
        // several OEMs — notably BBK (Oppo/OnePlus/Realme/Vivo) — report mA. Dividing an mA reading
        // by 1000 truncates e.g. 450 → 0, so power reads a flat 0 W. Disambiguate by magnitude:
        // real battery current is tens of thousands of µA and up, but only tens–thousands in mA,
        // so a raw value below the threshold is already mA. Sign is device-specific → use status.
        val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (rawCurrent == Int.MIN_VALUE) {
            null
        } else {
            val mag = abs(rawCurrent)
            val milliAmps = if (mag >= CURRENT_MICROAMP_THRESHOLD) mag / 1000 else mag
            if (status == ChargeStatus.DISCHARGING) -milliAmps else milliAmps
        }

        // EXTRA_VOLTAGE is documented in mV, but some OEMs (e.g. Oppo/ColorOS) report whole volts
        // (e.g. 4 instead of ~4300), which collapses the power calc to ~0. Normalise: a value already
        // in the Li-ion mV band is used as-is; a small value is volts → ×1000; anything else is junk.
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val voltageMv = when {
            rawVoltage in 2_500..20_000 -> rawVoltage
            rawVoltage in 1..99 -> rawVoltage * 1000
            else -> null
        }

        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
            else -> BatteryHealth.UNKNOWN
        }

        return BatteryInfo(tempC, status, plug, levelPct, currentMa, voltageMv, health)
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
            perCoreLoad = emptyList(), // merged in by sample(); readCpuClock() is also used for static facts
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
        // getThermalHeadroom must not be polled faster than ~1/s (else NaN); keep a safe margin.
        const val HEADROOM_QUERY_INTERVAL_MS = 1_500L
        // CURRENT_NOW unit split: |raw| ≥ this ⇒ µA (÷1000 → mA); below ⇒ already mA. ~30 mA / 30 A
        // boundary cleanly separates AOSP µA (active current is far higher) from OEM mA (even fast
        // charge stays well under 30 A).
        const val CURRENT_MICROAMP_THRESHOLD = 30_000
    }
}
