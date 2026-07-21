package com.corewatch.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
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
    /** Overall CPU utilization 0..1 — the mean of the readable per-core busy fractions. This is the
     *  headline "CPU usage": it tracks real activity even when the big clusters are frequency-capped
     *  (so their clock sits pinned). `null` until the first /proc/stat delta lands, or if unreadable. */
    val overallLoad: Float?
        get() {
            val valid = perCoreLoad.filter { !it.isNaN() }
            return if (valid.isEmpty()) null else valid.sum() / valid.size
        }

    /** Mean current core clock in MHz (context caption); `null` when no live clocks were readable. */
    val avgMhz: Int?
        get() = perCoreMhz.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }

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

/** Whether a mounted storage volume is built-in or a removable card. */
enum class StorageKind { INTERNAL, REMOVABLE }

/** One mounted storage volume's capacity. Read via public APIs, so always available on every
 *  Android version — unlike throughput, which depends on unblocked kernel counters. */
data class StorageVolumeInfo(
    val label: String,
    val kind: StorageKind,
    val totalBytes: Long,
    /** Bytes in use = total − available. */
    val usedBytes: Long,
)

/** Read/write throughput for one physical block device (or one kind, after same-kind merge), in
 *  bytes/sec over the actual elapsed interval on the asking [IoChannel]. */
data class DiskIoRate(
    val kind: StorageKind,
    val label: String,
    val readBytesPerSec: Float,
    val writeBytesPerSec: Float,
)

/**
 * Disk state for a single sample. [volumes] (capacity) is always present; [io] (throughput) is only
 * populated when /proc/diskstats is readable by the app — SELinux blocks it on stock Android, so
 * this hides on most retail devices and lights up on permissive/custom ROMs, exactly like the CPU
 * load path. [io] is also empty on the very first sample of a channel (no baseline delta yet).
 */
data class DiskInfo(
    val volumes: List<StorageVolumeInfo>,
    val ioSupported: Boolean,
    val io: List<DiskIoRate>,
) {
    /** Device-wide read throughput (sum across devices) for the history series; `NaN` when no rate. */
    val aggReadBytesPerSec: Float
        get() = io.takeIf { it.isNotEmpty() }?.sumOf { it.readBytesPerSec.toDouble() }?.toFloat() ?: Float.NaN

    /** Device-wide write throughput (sum across devices) for the history series; `NaN` when no rate. */
    val aggWriteBytesPerSec: Float
        get() = io.takeIf { it.isNotEmpty() }?.sumOf { it.writeBytesPerSec.toDouble() }?.toFloat() ?: Float.NaN

    companion object {
        val EMPTY = DiskInfo(emptyList(), false, emptyList())
    }
}

/** The sampling cadence asking for a throughput reading. Each keeps its own counter baseline so the
 *  1 s live flow and the slower recorder loop never consume each other's deltas (see [MetricsReader]). */
enum class IoChannel { LIVE, RECORDER }

/** Snapshot of the values that refresh every second. */
data class LiveMetrics(
    val cpu: CpuClock,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val battery: BatteryInfo,
    val thermal: ThermalInfo,
    val disk: DiskInfo,
) {
    companion object {
        val EMPTY = LiveMetrics(CpuClock.EMPTY, 0L, 0L, BatteryInfo.EMPTY, ThermalInfo.EMPTY, DiskInfo.EMPTY)
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
    private val storageManager: StorageManager by lazy {
        appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }

    // Previous /proc/stat per-core counters, for computing busy fraction between samples.
    private var prevCpuTotals: LongArray? = null
    private var prevCpuIdles: LongArray? = null

    // Previous /proc/diskstats sector counters, per sampling channel then per device name. Separate
    // per channel so the 1 s live flow and the slower recorder loop never divide against each other's
    // deltas (a rate, unlike a ratio, does not survive interleaving on a shared baseline).
    private class IoBaseline(val atMs: Long, val readSectors: Long, val writeSectors: Long)
    private val ioPrev = HashMap<IoChannel, MutableMap<String, IoBaseline>>()

    // Cached storage-volume enumeration + labels (the costly part; near-static). StatFs itself is
    // cheap and runs every sample; only the volume list + descriptions are refreshed on a TTL.
    private class VolumeRef(val dir: File, val label: String, val kind: StorageKind)
    private var volumeRefs: List<VolumeRef> = emptyList()
    private var volumeRefsAtMs = 0L

    // Cached thermal headroom — getThermalHeadroom returns NaN if polled faster than ~1/s, so we
    // query it at most every HEADROOM_QUERY_INTERVAL_MS and reuse the last good value in between.
    private var lastHeadroom: Float? = null
    private var lastHeadroomAtMs: Long = 0L

    /** Whether this device lets the app read per-core busy from /proc/stat. Some OEM/Android builds
     *  (e.g. Android 16 on certain MediaTek/ColorOS devices) block it for untrusted apps via SELinux
     *  even though `adb shell` can read it — leaving cpufreq clocks as the only CPU signal. Probed
     *  once; when false the CPU charts fall back to clock instead of load. */
    val cpuLoadSupported: Boolean by lazy {
        try {
            File("/proc/stat").useLines { lines -> lines.any { it.startsWith("cpu0") } }
        } catch (_: Exception) {
            false
        }
    }

    /** Whether the app can read /proc/diskstats for real block-device I/O. Blocked by SELinux for
     *  untrusted apps on stock enforcing Android (12+), but often open on older/custom ROMs. Probed
     *  once; requires at least one *physical* whole-disk row so a diskstats that lists only virtual
     *  devices (zram/loop) doesn't count. When false, disk throughput is hidden and only capacity
     *  is shown — the same graceful degrade as [cpuLoadSupported]. */
    val diskIoSupported: Boolean by lazy {
        try {
            File("/proc/diskstats").useLines { lines ->
                lines.any { line ->
                    isPhysicalWholeDisk(line.trim().split(Regex("\\s+")).getOrNull(2))
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Number of logical CPUs, discovered from sysfs with a Runtime fallback. */
    val coreCount: Int = run {
        val dirs = File("/sys/devices/system/cpu")
            .listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu[0-9]+")) }
            ?.size
        if (dirs != null && dirs > 0) dirs else Runtime.getRuntime().availableProcessors()
    }

    fun sample(ioChannel: IoChannel = IoChannel.LIVE): LiveMetrics {
        val (used, total) = readRam()
        return LiveMetrics(
            cpu = readCpuClock().copy(perCoreLoad = readCpuLoad()),
            ramUsedBytes = used,
            ramTotalBytes = total,
            battery = readBattery(),
            thermal = readThermal(),
            disk = readDiskIo(ioChannel),
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

    /**
     * Disk capacity (per mounted volume, always) plus read/write throughput (per physical block
     * device, only when /proc/diskstats is readable). Throughput is a delta over the *actual*
     * elapsed time since this channel's previous call — never an assumed interval — so it stays
     * correct even though the live flow (1 s) and recorder (slower) both drive [sample]. Each
     * [channel] keeps its own per-device baseline. Synchronized like [readCpuLoad] because more than
     * one coroutine reaches here.
     */
    @Synchronized
    private fun readDiskIo(channel: IoChannel): DiskInfo {
        val volumes = readVolumes()
        if (!diskIoSupported) return DiskInfo(volumes, ioSupported = false, io = emptyList())

        val lines = try {
            File("/proc/diskstats").readLines()
        } catch (_: Exception) {
            return DiskInfo(volumes, ioSupported = true, io = emptyList())
        }
        val now = SystemClock.elapsedRealtime()
        val prevMap = ioPrev.getOrPut(channel) { mutableMapOf() }
        // Collect raw per-device rates first; classification needs the whole-disk set (an mmc device
        // is the SD card only if a UFS/SCSI internal disk is also present), which isn't known until
        // the pass finishes.
        val perDevice = ArrayList<DeviceRate>()
        for (line in lines) {
            val t = line.trim().split(Regex("\\s+"))
            val name = t.getOrNull(2)
            if (!isPhysicalWholeDisk(name)) continue // whole disks only → no parent+partition double count
            name!!
            val rd = t.getOrNull(5)?.toLongOrNull() ?: continue    // sectors read (field 3 after name)
            val wr = t.getOrNull(9)?.toLongOrNull() ?: continue    // sectors written (field 7 after name)
            val prev = prevMap[name]
            prevMap[name] = IoBaseline(now, rd, wr)
            if (prev == null) continue                              // first sample on this (channel, device)
            val dtSec = (now - prev.atMs) / 1000f
            if (dtSec <= 0f) continue
            perDevice.add(
                DeviceRate(
                    name = name,
                    readBytesPerSec = ((rd - prev.readSectors) * SECTOR_BYTES / dtSec).coerceAtLeast(0f),
                    writeBytesPerSec = ((wr - prev.writeSectors) * SECTOR_BYTES / dtSec).coerceAtLeast(0f),
                ),
            )
        }
        val hasScsiDisk = perDevice.any { it.name.startsWith("sd") || it.name.startsWith("nvme") }
        // Classify each device, then merge same-kind devices (e.g. multiple UFS LUNs) into one row.
        val merged = perDevice
            .groupBy { classifyKind(it.name, hasScsiDisk) }
            .map { (kind, rs) ->
                DiskIoRate(
                    kind = kind,
                    label = labelFor(kind),
                    readBytesPerSec = rs.sumOf { it.readBytesPerSec.toDouble() }.toFloat(),
                    writeBytesPerSec = rs.sumOf { it.writeBytesPerSec.toDouble() }.toFloat(),
                )
            }
            .sortedBy { it.kind } // INTERNAL before REMOVABLE
        return DiskInfo(volumes, ioSupported = true, io = merged)
    }

    /** Per-mounted-volume capacity. Enumeration + labels are TTL-cached (near-static, relatively
     *  costly); [StatFs] runs every call and is cheap. A volume that throws (e.g. an SD card pulled
     *  mid-session) is simply dropped from the returned list. */
    private fun readVolumes(): List<StorageVolumeInfo> {
        val now = SystemClock.elapsedRealtime()
        if (volumeRefs.isEmpty() || now - volumeRefsAtMs >= VOLUME_REFRESH_MS) {
            val refs = ArrayList<VolumeRef>()
            appContext.getExternalFilesDirs(null).forEachIndexed { i, dir ->
                dir ?: return@forEachIndexed // an unmounted / ejecting volume slot
                val sv = runCatching { storageManager.getStorageVolume(dir) }.getOrNull()
                val removable = sv?.isRemovable ?: (i > 0)
                val label = sv?.getDescription(appContext)?.takeIf { it.isNotBlank() }
                    ?: if (i == 0) "Internal" else "microSD"
                refs.add(VolumeRef(dir, label, if (removable) StorageKind.REMOVABLE else StorageKind.INTERNAL))
            }
            volumeRefs = refs
            volumeRefsAtMs = now
        }
        return volumeRefs.mapNotNull { ref ->
            runCatching {
                val fs = StatFs(ref.dir.path)
                val total = fs.totalBytes
                StorageVolumeInfo(ref.label, ref.kind, total, (total - fs.availableBytes).coerceAtLeast(0L))
            }.getOrNull()
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

    /** Raw per-device rate before kind classification/merge. */
    private class DeviceRate(val name: String, val readBytesPerSec: Float, val writeBytesPerSec: Float)

    /** A physical *whole* block device worth summing — excludes virtual devices (loop/ram/zram/dm/sr)
     *  and partition sub-devices (sda1, mmcblk0p1, nvme0n1p1). Counting only whole disks avoids
     *  double-counting a parent together with its partitions. */
    private fun isPhysicalWholeDisk(name: String?): Boolean {
        name ?: return false
        return when {
            name.matches(Regex("(loop|ram|zram|sr)\\d+")) || name.matches(Regex("dm-\\d+")) -> false
            name.matches(Regex("(sd[a-z]+|vd[a-z]+)\\d+")) -> false              // SCSI/UFS/virtio partition
            name.matches(Regex("(mmcblk\\d+|nvme\\d+n\\d+)p\\d+")) -> false       // eMMC/SD/NVMe partition
            name.matches(Regex("sd[a-z]+")) -> true                              // SCSI/UFS whole disk
            name.matches(Regex("vd[a-z]+")) -> true                              // virtio whole disk (VM/emulator/cloud)
            name.matches(Regex("mmcblk\\d+")) -> true                            // eMMC/SD whole disk
            name.matches(Regex("nvme\\d+n\\d+")) -> true                         // NVMe whole disk
            else -> false
        }
    }

    /** Best-effort Internal vs Removable. `sd*`/`nvme*` are internal UFS/NVMe; an `mmcblk*` is the SD
     *  card when a UFS/SCSI disk is also present ([hasScsiDisk]), otherwise `mmcblk0` is the internal
     *  eMMC and any higher-indexed mmc is removable. Used only for labels / the live split — never
     *  persisted, so a wrong guess is cheap. */
    private fun classifyKind(name: String, hasScsiDisk: Boolean): StorageKind = when {
        name.startsWith("sd") || name.startsWith("nvme") || name.startsWith("vd") -> StorageKind.INTERNAL
        name.startsWith("mmcblk") -> when {
            hasScsiDisk -> StorageKind.REMOVABLE
            name == "mmcblk0" -> StorageKind.INTERNAL
            else -> StorageKind.REMOVABLE
        }
        else -> StorageKind.INTERNAL
    }

    private fun labelFor(kind: StorageKind): String =
        if (kind == StorageKind.REMOVABLE) "microSD" else "Internal"

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
        // /proc/diskstats counts I/O in 512-byte sectors regardless of the device's real block size.
        const val SECTOR_BYTES = 512f
        // Storage-volume enumeration + labels are near-static; re-scan at most this often.
        const val VOLUME_REFRESH_MS = 30_000L
    }
}
