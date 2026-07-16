package com.corewatch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.corewatch.monitor.DeviceInfo
import com.corewatch.monitor.LiveMetrics
import com.corewatch.monitor.MetricsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIVE_INTERVAL_MS = 1_000L
private const val HISTORY_BASE_INTERVAL_SEC = 5
private const val HISTORY_MAX_POINTS = 720   // ~1h @5s before the timeline is compressed

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val reader = MetricsReader(app)

    /** Read once — device identity and SoC facts don't change while the app runs. */
    val deviceInfo: DeviceInfo = DeviceInfo.read(app, reader)

    /**
     * Live values for the tiles — a fresh sample every second, paused shortly after the UI
     * stops observing (backgrounded).
     */
    val metrics: StateFlow<LiveMetrics> = flow {
        while (currentCoroutineContext().isActive) {
            emit(reader.sample())
            delay(LIVE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LiveMetrics.EMPTY,
        )

    // ---- Whole-session history (charts). Starts empty at launch; cleared on process death. ----

    /** CPU peak-clock samples in MHz; `NaN` where the live clock was unavailable. */
    val cpuHistory = mutableStateListOf<Float>()

    /** Used-memory samples in bytes. */
    val ramHistory = mutableStateListOf<Float>()

    var ramTotalBytes by mutableLongStateOf(0L)
        private set

    /** Seconds represented by each stored point; grows when the timeline is compressed. */
    var historyIntervalSec by mutableIntStateOf(HISTORY_BASE_INTERVAL_SEC)
        private set

    // ---- Whole-session battery aggregates (since launch). ----

    private var batteryTempMinC by mutableStateOf<Float?>(null)
    private var batteryTempMaxC by mutableStateOf<Float?>(null)
    private var batteryEnergyMwh by mutableFloatStateOf(0f)
    private var batteryElapsedSec by mutableIntStateOf(0)

    /** Snapshot of the session battery stats; reads the backing state so it recomposes on change. */
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

    init {
        // Independent recorder so history accumulates for the whole session, regardless of
        // whether the tiles are currently subscribed.
        viewModelScope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { reader.sample() }
                ramTotalBytes = sample.ramTotalBytes
                cpuHistory.add(sample.cpu.currentMaxMhz?.toFloat() ?: Float.NaN)
                ramHistory.add(sample.ramUsedBytes.toFloat())

                // Battery session aggregates.
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
                    historyIntervalSec *= 2
                }
                delay(historyIntervalSec * 1_000L)
            }
        }
    }

    /** Halve a series by keeping every other point — preserves whole-session coverage, bounded. */
    private fun decimate(series: MutableList<Float>) {
        val kept = ArrayList<Float>(series.size / 2 + 1)
        for (i in series.indices step 2) kept.add(series[i])
        series.clear()
        series.addAll(kept)
    }
}

/** Whole-session battery aggregates since launch. */
data class BatterySession(
    val minTempC: Float?,
    val maxTempC: Float?,
    val avgPowerW: Float?,
    val energyMwh: Float,
)
