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
    private lateinit var appContext: Context

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
    val powerHistory = mutableStateListOf<Float>() // battery draw in W, ≥0 on battery; NaN while charging

    /**
     * Series indices marking a discontinuity: the point where a previous (killed) session ended and
     * a resumed one picks up. The charts draw an explicit "app was stopped / resumed" break here, so
     * a restored session never masquerades as continuous recording.
     */
    val gapIndices = mutableStateListOf<Int>()

    var ramTotalBytes by mutableLongStateOf(0L)
        private set
    var historyIntervalSec by mutableIntStateOf(HISTORY_BASE_INTERVAL_SEC)
        private set

    // ---- Whole-session battery aggregates (since collection started). ----
    private var batteryTempMinC by mutableStateOf<Float?>(null)
    private var batteryTempMaxC by mutableStateOf<Float?>(null)
    private var batteryEnergyMwh by mutableFloatStateOf(0f)
    private var batteryDischargeSec by mutableIntStateOf(0)

    val batterySession: BatterySession
        get() = BatterySession(
            minTempC = batteryTempMinC,
            maxTempC = batteryTempMaxC,
            energyMwh = batteryEnergyMwh,
            avgPowerW = if (batteryDischargeSec > 0) {
                (batteryEnergyMwh / 1000f) / (batteryDischargeSec / 3600f)
            } else {
                null
            },
        )

    private var recorderJob: Job? = null

    /**
     * Idempotent: initialises the reader on first call (restoring any persisted session) and starts
     * the recorder. `@Synchronized` because the foreground service and the ViewModel can both call
     * this from different threads on a fresh (post-kill) process — without it they could double-init.
     */
    @Synchronized
    fun start(context: Context) {
        if (!::reader.isInitialized) {
            appContext = context.applicationContext
            reader = MetricsReader(appContext)
            deviceInfo = DeviceInfo.read(appContext, reader)
            restoreSession()
        }
        if (recorderJob?.isActive == true) return
        recorderJob = scope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { reader.sample() }
                ramTotalBytes = sample.ramTotalBytes
                cpuHistory.add(sample.cpu.currentMaxMhz?.toFloat() ?: Float.NaN)
                ramHistory.add(sample.ramUsedBytes.toFloat())
                tempHistory.add(sample.battery.tempC ?: Float.NaN)
                // Power *draw* only exists on battery; while charging it's N/A (NaN → chart break).
                val drawW = sample.battery.drawW
                powerHistory.add(drawW ?: Float.NaN)

                sample.battery.tempC?.let { t ->
                    batteryTempMinC = batteryTempMinC?.let { min(it, t) } ?: t
                    batteryTempMaxC = batteryTempMaxC?.let { max(it, t) } ?: t
                }
                // Energy + time accrue only while discharging, so Energy = drawn-from-battery and
                // Avg = average draw, not diluted by charging / idle-plugged stretches.
                if (drawW != null) {
                    batteryEnergyMwh += drawW * (historyIntervalSec / 3600f) * 1000f
                    batteryDischargeSec += historyIntervalSec
                }

                if (cpuHistory.size > HISTORY_MAX_POINTS) {
                    decimate(cpuHistory)
                    decimate(ramHistory)
                    decimate(tempHistory)
                    decimate(powerHistory)
                    remapGapsAfterDecimate(cpuHistory.size)
                    historyIntervalSec *= 2
                }
                // Persist after each tick so a kill loses at most one interval of data.
                withContext(Dispatchers.IO) { SessionStore.save(appContext, snapshot()) }
                delay(historyIntervalSec * 1_000L)
            }
        }
    }

    /** Stops the recorder and clears the session, on disk too (used on explicit app exit). */
    @Synchronized
    fun stop() {
        recorderJob?.cancel()
        recorderJob = null
        cpuHistory.clear()
        ramHistory.clear()
        tempHistory.clear()
        powerHistory.clear()
        ramTotalBytes = 0L
        historyIntervalSec = HISTORY_BASE_INTERVAL_SEC
        batteryTempMinC = null
        batteryTempMaxC = null
        batteryEnergyMwh = 0f
        batteryDischargeSec = 0
        gapIndices.clear()
        if (::appContext.isInitialized) SessionStore.clear(appContext)
    }

    private fun snapshot() = SessionSnapshot(
        historyIntervalSec = historyIntervalSec,
        ramTotalBytes = ramTotalBytes,
        cpu = cpuHistory.toList(),
        ram = ramHistory.toList(),
        temp = tempHistory.toList(),
        power = powerHistory.toList(),
        battMinTempC = batteryTempMinC,
        battMaxTempC = batteryTempMaxC,
        battEnergyMwh = batteryEnergyMwh,
        battElapsedSec = batteryDischargeSec,
        gaps = gapIndices.toList(),
    )

    /**
     * Reload a session persisted before an OS kill. The restored points seed the same chart series
     * the UI observes; a gap boundary is recorded at the seam so the resumed run is drawn as an
     * explicit "stopped here / resumed here" break, never as continuous recording.
     */
    private fun restoreSession() {
        val snap = SessionStore.load(appContext) ?: return
        if (snap.cpu.isEmpty() && snap.ram.isEmpty() && snap.temp.isEmpty()) return
        historyIntervalSec = snap.historyIntervalSec.coerceAtLeast(HISTORY_BASE_INTERVAL_SEC)
        ramTotalBytes = snap.ramTotalBytes
        // Prior gaps from earlier kills stay valid — they index into the restored data.
        gapIndices.addAll(snap.gaps)
        cpuHistory.addAll(snap.cpu)
        ramHistory.addAll(snap.ram)
        tempHistory.addAll(snap.temp)
        powerHistory.addAll(snap.power)
        batteryTempMinC = snap.battMinTempC
        batteryTempMaxC = snap.battMaxTempC
        batteryEnergyMwh = snap.battEnergyMwh
        batteryDischargeSec = snap.battElapsedSec
        // The seam sits between the last restored point and the first upcoming live point.
        val boundary = cpuHistory.size
        if (boundary > 0) gapIndices.add(boundary)
    }

    /** Series were halved (step 2), so every gap index maps to i/2; drop any that fall out of range. */
    private fun remapGapsAfterDecimate(newSize: Int) {
        val mapped = gapIndices.map { it / 2 }.distinct().filter { it in 1 until newSize }
        gapIndices.clear()
        gapIndices.addAll(mapped)
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
