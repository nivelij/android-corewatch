package com.corewatch.monitor

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Static device / SoC / OS facts, read once at startup. */
data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val socManufacturer: String?,
    val socModelRaw: String?,
    val socMarketing: String?,
    val cores: Int,
    val abi: String,
    val minClockMhz: Int?,
    val maxClockMhz: Int?,
    /** GPU model from OpenGL (e.g. "Adreno (TM) 740"), or null if it couldn't be read. */
    val gpuRenderer: String?,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val kernel: String?,
) {
    /** Best label for the SoC: marketing name if known, else the raw codename, else manufacturer. */
    val socLabel: String
        get() = socMarketing
            ?: socModelRaw?.takeIf { it.isNotBlank() && !it.equals(Build.UNKNOWN, true) }
            ?: socManufacturer?.takeIf { it.isNotBlank() && !it.equals(Build.UNKNOWN, true) }
            ?: "Unknown SoC"

    companion object {
        fun read(context: Context, reader: MetricsReader): DeviceInfo {
            val userName = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.takeIf { it.isNotBlank() }

            val manufacturer = Build.MANUFACTURER
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val deviceName = userName ?: "$manufacturer ${Build.MODEL}"

            val socMan = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && !it.equals(Build.UNKNOWN, true) }
            val socModel = Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals(Build.UNKNOWN, true) }

            val clock = reader.readCpuClock()

            return DeviceInfo(
                deviceName = deviceName,
                manufacturer = manufacturer,
                socManufacturer = socMan,
                socModelRaw = socModel,
                socMarketing = Soc.marketingName(socMan, socModel),
                cores = reader.coreCount,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                minClockMhz = clock.minMhz,
                maxClockMhz = clock.maxMhz,
                gpuRenderer = GpuInfo.read().renderer,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                sdkInt = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() },
                kernel = System.getProperty("os.version")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
