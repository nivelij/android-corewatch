package com.corewatch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import com.corewatch.monitor.MetricsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val LIVE_INTERVAL_MS = 1_000L
private const val HISTORY_BASE_INTERVAL_SEC = 5
private const val HISTORY_MAX_POINTS = 720   // ~1h @5s before the timeline is compressed

/**
 * Process-scoped telemetry collector. Lives beyond any single Activity so a foreground service can
 * keep it running in the background; the [MonitorViewModel] simply exposes its state to the UI.
 *
 * [start] is idempotent. The whole-session recorder (history + battery aggregates) runs in
 * [scope] independent of UI subscription; the per-second [metrics] flow pauses when nobody observes
 * it (battery-friendly), which is fine because the recorder is what accumulates the session.
 */
object SessionCollector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var reader: MetricsReader

    /** Device identity + SoC facts; read once when collection first starts. */
    lateinit var deviceInfo: DeviceInfo
        private set

    /** Live values for the tiles — sampled every second while observed. */
    val metrics: StateFlow<LiveMetrics> = flow {
        while (currentCoroutineContext().isActive) {
            emit(reader.sample())
            delay(LIVE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LiveMetrics.EMPTY)

    // ---- Whole-session history (charts). ----
    val cpuHistory = mutableStateListOf<Float>()
    val ramHistory = mutableStateListOf<Float>()
    val tempHistory = mutableStateListOf<Float>()

    var ramTotalBytes by mutableLongStateOf(0L)
        private set
    var historyIntervalSec by mutableIntStateOf(HISTORY_BASE_INTERVAL_SEC)
        private set

    // ---- Whole-session battery aggregates (since collection started). ----
    private var batteryTempMinC by mutableStateOf<Float?>(null)
    private var batteryTempMaxC by mutableStateOf<Float?>(null)
    private var batteryEnergyMwh by mutableFloatStateOf(0f)
    private var batteryElapsedSec by mutableIntStateOf(0)

    val batterySession: BatterySession
        get() = BatterySession(
            minTempC = batteryTempMinC,
            maxTempC = batteryTempMaxC,
            energyMwh = batteryEnergyMwh,
            avgPowerW = if (batteryElapsedSec > 0) {
                (batteryEnergyMwh / 1000f) / (batteryElapsedSec / 3600f)
            } else {
                null
            },
        )

    private var recorderJob: Job? = null

    /** Idempotent: initialises the reader on first call and starts the session recorder. */
    fun start(context: Context) {
        if (!::reader.isInitialized) {
            reader = MetricsReader(context.applicationContext)
            deviceInfo = DeviceInfo.read(context.applicationContext, reader)
        }
        if (recorderJob?.isActive == true) return
        recorderJob = scope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { reader.sample() }
                ramTotalBytes = sample.ramTotalBytes
                cpuHistory.add(sample.cpu.currentMaxMhz?.toFloat() ?: Float.NaN)
                ramHistory.add(sample.ramUsedBytes.toFloat())
                tempHistory.add(sample.battery.tempC ?: Float.NaN)

                sample.battery.tempC?.let { t ->
                    batteryTempMinC = batteryTempMinC?.let { min(it, t) } ?: t
                    batteryTempMaxC = batteryTempMaxC?.let { max(it, t) } ?: t
                }
                sample.battery.powerW?.let { p ->
                    batteryEnergyMwh += abs(p) * (historyIntervalSec / 3600f) * 1000f
                }
                batteryElapsedSec += historyIntervalSec

                if (cpuHistory.size > HISTORY_MAX_POINTS) {
                    decimate(cpuHistory)
                    decimate(ramHistory)
                    decimate(tempHistory)
                    historyIntervalSec *= 2
                }
                delay(historyIntervalSec * 1_000L)
            }
        }
    }

    /** Stops the recorder and clears the session (used on explicit app exit). */
    fun stop() {
        recorderJob?.cancel()
        recorderJob = null
        cpuHistory.clear()
        ramHistory.clear()
        tempHistory.clear()
        ramTotalBytes = 0L
        historyIntervalSec = HISTORY_BASE_INTERVAL_SEC
        batteryTempMinC = null
        batteryTempMaxC = null
        batteryEnergyMwh = 0f
        batteryElapsedSec = 0
    }

    /** Halve a series by keeping every other point — preserves whole-session coverage, bounded. */
    private fun decimate(series: MutableList<Float>) {
        val kept = ArrayList<Float>(series.size / 2 + 1)
        for (i in series.indices step 2) kept.add(series[i])
        series.clear()
        series.addAll(kept)
    }
}

/** Whole-session battery aggregates since collection started. */
data class BatterySession(
    val minTempC: Float?,
    val maxTempC: Float?,
    val avgPowerW: Float?,
    val energyMwh: Float,
)
